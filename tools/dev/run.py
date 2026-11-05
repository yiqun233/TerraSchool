"""本地查看环境：WSL PostgreSQL专用项目库、迁移角色、合成账号与前后端进程。"""

from contextlib import contextmanager
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / '.local/dev'
SETTINGS = json.loads((ROOT / 'tools/dev/settings.json').read_text(encoding='utf-8'))
WORKSPACE = hashlib.sha256(str(ROOT).lower().encode()).hexdigest()[:16]
STATE = LOCAL / 'environment.json'
RUNNING = LOCAL / 'running.json'
JAR = ROOT / 'backend/applications/server/target/server-0.1.0-SNAPSHOT.jar'
sys.path.insert(0, str(ROOT / 'tools/verification/core-stack'))
from process_tree import stop_tree


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def private_directory():
    LOCAL.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        import csv
        identity = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'], text=True).strip()
        sid = next(csv.reader([identity]))[1]
        # 仅保护本工具新建的本地目录，不改变用户缓存或全局权限。
        subprocess.run(['icacls', str(LOCAL), '/inheritance:r', '/grant:r',
                        '*' + sid + ':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    else:
        LOCAL.chmod(0o700)


def docker():
    candidate = shutil.which('docker') or str(Path(os.environ.get('LOCALAPPDATA', '')) /
                  'Programs/DockerDesktop/resources/bin/docker.exe')
    if not Path(candidate).exists():
        raise RuntimeError('找不到 Docker，请先安装并启动 Docker Desktop 的 Linux 引擎。')
    return candidate


def redact(text, config=None):
    if config:
        for value in config['passwords'].values():
            text = text.replace(value, '[REDACTED]')
    return text


def command(args, config=None, env=None, input_text=None, log=None, cwd=ROOT, timeout=600):
    process = subprocess.Popen(args, cwd=cwd, env=env,
        stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
        errors='replace', start_new_session=os.name != 'nt',
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    timed_out = False
    try:
        try:
            output, _ = process.communicate(input=input_text, timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            stop_tree(process)
            output, _ = process.communicate(timeout=15)
    finally:
        stop_tree(process)
    output = redact(output, config)
    if log:
        (LOCAL / log).write_text(output, encoding='utf-8')
    if timed_out:
        raise RuntimeError('命令超时，已清理本次进程树。' + (' 日志：' + log if log else ''))
    if process.returncode:
        raise RuntimeError(output[-2500:] or '命令执行失败：' + str(args[0]))
    return output.strip()


@contextmanager
def operation_lock(wait=0):
    # 进程退出即释放锁，不根据一个可能过期的PID终止进程或删除锁文件。
    LOCAL.mkdir(parents=True, exist_ok=True)
    with (LOCAL / 'operation.lock').open('a+b') as handle:
        handle.seek(0)
        handle.write(b'0')
        handle.flush()
        handle.seek(0)
        deadline = time.monotonic() + wait
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError as error:
                if time.monotonic() >= deadline:
                    raise RuntimeError('正在准备/启动或停止，请等待，或在启动终端按Ctrl+C；未停止数据库。') from error
                time.sleep(0.1)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def configuration():
    if STATE.exists():
        value = json.loads(STATE.read_text(encoding='utf-8'))
        if value['workspace'] != WORKSPACE or value['settings'] != SETTINGS:
            raise RuntimeError('开发配置与当前目录/settings.json不一致；旧Docker环境请先运行tools/dev/migrate_wsl.py。')
        if 'audit' not in value['passwords']:
            value['passwords']['audit'] = secrets.token_urlsafe(30)
            write_json(STATE, value)
        if 'scheduler' not in value['passwords']:
            value['passwords']['scheduler'] = secrets.token_urlsafe(30)
            write_json(STATE, value)
        return value
    value = {'workspace': WORKSPACE, 'environment_id': secrets.token_hex(16),
             'settings': SETTINGS, 'passwords': {key: secrets.token_urlsafe(30)
             for key in ['migrator', 'app', 'identity', 'session', 'audit', 'scheduler', 'login']}}
    write_json(STATE, value)
    (LOCAL / 'accounts.txt').write_text(
        '仅供本机合成演示，不是正式管理员。\n维护账号：local-admin\n只读账号：local-reader\n两账号密码：' +
        value['passwords']['login'] + '\n', encoding='utf-8')
    return value


def inspect_owned(kind, name, config):
    rows = json.loads(command([docker(), kind, 'inspect', name], config))
    labels = rows[0].get('Config', {}).get('Labels', {}) if kind == 'container' else rows[0].get('Labels', {})
    if labels.get('org.teraschool.dev') != config['environment_id']:
        raise RuntimeError('资源不属于当前本地环境，拒绝操作：' + name)
    return rows[0]


def available(port):
    with socket.socket() as listener:
        try:
            listener.bind(('127.0.0.1', port))
        except OSError as error:
            raise RuntimeError('端口 ' + str(port) + ' 已被占用：可能上一次启动的进程没有退出。'
                               '请先运行 stop-local.cmd；仍被占用时，在任务管理器结束残留的 java / node 进程后重试。') from error


def wsl_args(program, *arguments):
    local_socket = ['--host=/var/run/postgresql'] if program in ['psql', 'pg_dump', 'pg_restore'] else []
    return ['wsl', '-d', SETTINGS['wsl_distribution'], '-u', 'root', '--',
            'runuser', '-u', 'postgres', '--', program, *local_socket, *arguments]


def wsl_admin(config, text, database='postgres'):
    # 仅本机Ubuntu的Unix套接字管理入口，应用进程仍用受限账号走5432。
    if database not in ['postgres', 'teraschool_dev', 'vectordb']:
        raise RuntimeError('不允许操作该数据库。')
    return command(wsl_args('psql', '-X', '-qAt', '-p', str(SETTINGS['database_port']),
                   '-v', 'ON_ERROR_STOP=1', '-d', database), config, input_text=text, timeout=60)


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def database_marker(config):
    return 'teraschool-local-dev:' + config['environment_id']


def assert_wsl_database(config):
    marker = wsl_admin(config, "SELECT coalesce(shobj_description(oid,'pg_database'),'') "
                       "FROM pg_database WHERE datname='teraschool_dev';")
    if marker != database_marker(config):
        raise RuntimeError('WSL项目库标记不匹配，拒绝接管或写入已有数据库。')


def sql(config, text, database=None):
    if database not in [None, 'teraschool_dev']:
        raise RuntimeError('普通开发操作只能访问teraschool_dev。')
    assert_wsl_database(config)
    return wsl_admin(config, text, 'teraschool_dev')


def ensure_database(config):
    if SETTINGS['database_name'] != 'teraschool_dev' or SETTINGS['database_provider'] != 'wsl':
        raise RuntimeError('本地入口只支持WSL内的teraschool_dev。')
    try:
        version = wsl_admin(config, "SELECT current_setting('server_version');")
    except RuntimeError:
        command(['wsl', '-d', SETTINGS['wsl_distribution'], '-u', 'root', '--',
                 'systemctl', 'start', 'postgresql'], config, timeout=60)
        version = wsl_admin(config, "SELECT current_setting('server_version');")
    if not version.startswith('18.'):
        raise RuntimeError('当前工程需要已验证的PostgreSQL18。')
    roles = json.loads(wsl_admin(config, "SELECT coalesce(json_object_agg(rolname, "
        "coalesce(shobj_description(oid,'pg_authid'),'')),'{}'::json) FROM pg_roles "
        "WHERE rolname IN ('ts_migrator','ts_app','ts_identity','ts_session','ts_audit','ts_scheduler');"))
    for name, marker in roles.items():
        if marker != database_marker(config):
            raise RuntimeError('已有角色不属于当前项目，拒绝更改：' + name)
    existing = wsl_admin(config, "SELECT count(*) FROM pg_database WHERE datname='teraschool_dev';")
    if existing == '1':
        assert_wsl_database(config)
    statements = []
    for key, role in [('migrator','ts_migrator'),('app','ts_app'),('identity','ts_identity'),('session','ts_session'),('audit','ts_audit'),('scheduler','ts_scheduler')]:
        if role not in roles:
            statements.append('CREATE ROLE ' + role + ' LOGIN PASSWORD ' + literal(config['passwords'][key]) +
                ' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS; COMMENT ON ROLE ' + role + ' IS ' +
                literal(database_marker(config)) + ';')
    if statements:
        wsl_admin(config, 'BEGIN;\n' + '\n'.join(statements) + '\nCOMMIT;')
    if existing == '0':
        wsl_admin(config, 'CREATE DATABASE teraschool_dev OWNER ts_migrator; COMMENT ON DATABASE teraschool_dev IS ' +
                  literal(database_marker(config)) + ';')
    assert_wsl_database(config)
    wsl_admin(config, 'REVOKE ALL ON DATABASE teraschool_dev FROM PUBLIC; '
              'GRANT CONNECT ON DATABASE teraschool_dev TO ts_app,ts_identity,ts_session,ts_audit,ts_scheduler;')


def tools():
    home = os.environ.get('JAVA_HOME', '')
    java = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    if not java.exists() or '25.' not in command([str(java), '--version']).splitlines()[0]:
        raise RuntimeError('请配置 JDK25 的 JAVA_HOME。')
    pnpm = shutil.which('pnpm.cmd' if os.name == 'nt' else 'pnpm')
    if not pnpm:
        raise RuntimeError('请安装 pnpm 11.23.0。')
    return str(java), pnpm


def fingerprint():
    digest = hashlib.sha256()
    # 数据库提供方式/地址变化也使旧构建准备记录失效，防止沿用旧连接文件。
    digest.update(json.dumps(SETTINGS, sort_keys=True).encode())
    paths = [p for p in (ROOT / 'backend').rglob('*') if p.is_file() and 'target' not in p.parts]
    paths += [ROOT / 'frontend/package.json', ROOT / 'frontend/pnpm-lock.yaml', ROOT / 'tools/dev/seed.sql']
    for path in sorted(paths):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def mail_credential_key(config):
    """保留稳定主密钥；已有密文但文件丢失时绝不生成替代密钥。"""
    path = LOCAL / 'mail-credential-key.json'
    if not path.exists():
        count = sql(config, 'SELECT count(*) FROM organization.employee_email_config WHERE credential_ciphertext IS NOT NULL;')
        verifier_count = sql(config, 'SELECT count(*) FROM infra.mail_key_verifier;')
        if count != '0' or verifier_count != '0':
            raise RuntimeError('邮件凭据主密钥缺失且已有密钥记录，请从受保护备份恢复原密钥。')
        write_json(path, {'version': 1, 'key': base64.b64encode(secrets.token_bytes(32)).decode('ascii')})
    value = json.loads(path.read_text(encoding='utf-8'))
    try:
        key = base64.b64decode(value['key'], validate=True)
        if value.get('version') != 1 or len(key) != 32:
            raise ValueError('invalid')
    except (ValueError, KeyError, TypeError):
        raise RuntimeError('邮件凭据主密钥文件无效，请从受保护备份恢复。') from None
    stored = sql(config, "SELECT encode(verifier,'hex') FROM infra.mail_key_verifier WHERE id=1;")
    expected = hmac.new(key, b'teraschool:employee-email-master-key:v1', hashlib.sha256).hexdigest()
    if stored and not hmac.compare_digest(stored, expected):
        raise RuntimeError('邮件凭据主密钥与数据库记录不匹配，请从受保护备份恢复原密钥。')
    return value['key']


def write_runtime_config(config):
    """每次启动都从受限文件复核并生成配置，避免旧配置掩盖主密钥丢失。"""
    config['passwords'].setdefault('scheduler', secrets.token_urlsafe(30))
    lines = ['# 自动生成，仅供本机开发，勿提交Git。', 'server:', '  address: 127.0.0.1',
             '  port: ' + str(SETTINGS['backend_port']), '  servlet:', '    session:', '      cookie:',
             '        secure: false', 'teraschool:', '  database:',
             '    url: jdbc:postgresql://127.0.0.1:' + str(SETTINGS['database_port']) + '/teraschool_dev']
    for kind in ['app', 'identity', 'session', 'audit', 'scheduler']:
        lines += ['    ' + kind + ':', '      username: ts_' + kind,
                  '      password: ' + json.dumps(config['passwords'][kind])]
    lines += ['  mail-credentials:', '    master-key: ' + json.dumps(mail_credential_key(config))]
    storage_path = LOCAL / 'storage.json'
    if storage_path.exists():
        storage = json.loads(storage_path.read_text(encoding='utf-8'))
        required = ['endpoint', 'region', 'bucket', 'access-key', 'secret-key']
        if any(not isinstance(storage.get(key), str) or not storage[key].strip() for key in required):
            raise RuntimeError('本地对象存储配置缺项，请检查受限目录中的 storage.json。')
        lines.append('  storage:')
        lines += ['    ' + key + ': ' + json.dumps(storage[key]) for key in required]
    (LOCAL / 'application-local.yml').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def prepare(config):
    java, pnpm = tools()
    print('[1/4] 检查WSL PostgreSQL及专用项目库', flush=True)
    ensure_database(config)
    env = dict(os.environ, MAVEN_USER_HOME=str(ROOT / '.local/maven'),
               TS_JDBC_URL='jdbc:postgresql://127.0.0.1:' + str(SETTINGS['database_port']) + '/teraschool_dev',
               TS_ADMIN_PASSWORD=config['passwords']['migrator'])
    wrapper = ROOT / 'backend' / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    mvn = [str(wrapper)] if os.name == 'nt' else ['sh', str(wrapper)]
    mvn += ['-B', '-ntp', '-s', str(ROOT / 'backend/.mvn/settings.xml'), '-gs',
            str(ROOT / 'backend/.mvn/settings.xml'), '-Dmaven.repo.local=' + str(ROOT / '.local/maven/repository'),
            '-Dmigration.user=ts_migrator']
    print('[2/4] 以独立迁移账号迁移、生成类型并构建后端', flush=True)
    for label, arguments in [('backend-clean', ['clean']), ('migration', ['-N', 'flyway:migrate']), ('codegen', ['-N', 'jooq-codegen:generate']),
                             ('backend-build', ['package', '-DskipTests'])]:
        command(mvn + arguments, config, env=env, cwd=ROOT / 'backend', log=label + '.log')
    print('[3/4] 首次装载合成资料，已有修改不会重置', flush=True)
    seed = (ROOT / 'tools/dev/seed.sql').read_text(encoding='utf-8')
    sql(config, seed.replace('@@LOGIN_PASSWORD@@', literal(config['passwords']['login'])))
    write_runtime_config(config)
    print('[4/4] 安装锁定的前端依赖', flush=True)
    command([pnpm, 'install', '--frozen-lockfile', '--ignore-scripts', '--strict-peer-dependencies',
             '--store-dir', str(ROOT / '.local/pnpm-store')], config, cwd=ROOT / 'frontend', log='frontend-install.log')
    write_json(LOCAL / 'build.json', {'fingerprint': fingerprint()})
    print('开发环境已准备。演示账号密码见 ' + str(LOCAL / 'accounts.txt'), flush=True)


def control(action):
    if not RUNNING.exists():
        return None
    state = json.loads(RUNNING.read_text(encoding='utf-8'))
    if state.get('workspace') != WORKSPACE:
        raise RuntimeError('运行记录不属于当前工程。')
    request = Request('http://127.0.0.1:' + str(state['port']) + '/' + action,
                      headers={'Authorization': 'Bearer ' + state['token']},
                      method='POST' if action == 'stop' else 'GET')
    try:
        with urlopen(request, timeout=2) as response:
            result = json.load(response)
            return result if result.get('workspace') == WORKSPACE else None
    except (OSError, URLError, ValueError):
        return None


def open_browser(url):
    """启动成功后打开页面；打不开浏览器不影响服务本身。"""
    try:
        if os.name == 'nt':
            os.startfile(url)
        else:
            import webbrowser
            webbrowser.open(url)
    except Exception:
        print('无法自动打开浏览器，请手动访问：' + url, flush=True)


def wait_http(url, processes):
    for _ in range(120):
        if any(p.poll() is not None for p in processes):
            raise RuntimeError('服务提前退出，请查看.local/dev的backend.log和frontend.log。')
        try:
            with urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except (URLError, OSError):
            pass
        time.sleep(0.5)
    raise RuntimeError('启动超时：' + url)


def urls():
    return {'frontend': 'http://127.0.0.1:' + str(SETTINGS['frontend_port']),
            'backend': 'http://127.0.0.1:' + str(SETTINGS['backend_port']),
            'database': '127.0.0.1:' + str(SETTINGS['database_port']) + '/teraschool_dev'}


def start(config):
    java, pnpm = tools()
    for port in [SETTINGS['backend_port'], SETTINGS['frontend_port']]:
        available(port)
    build = LOCAL / 'build.json'
    required = [JAR, LOCAL / 'application-local.yml', ROOT / 'frontend/node_modules/.modules.yaml']
    if not build.exists() or not all(path.exists() for path in required) or json.loads(build.read_text())['fingerprint'] != fingerprint():
        prepare(config)
    else:
        ensure_database(config)
    write_runtime_config(config)
    done = threading.Event()
    token = secrets.token_urlsafe(30)

    class Handler(BaseHTTPRequestHandler):
        def handle_control(self):
            if not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.send_error(404)
                return
            if self.path not in ['/status', '/stop'] or (self.path == '/stop' and self.command != 'POST'):
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'workspace': WORKSPACE, 'urls': urls()}).encode())
            if self.path == '/stop':
                done.set()
        do_GET = handle_control
        do_POST = handle_control
        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    serving = False
    children = []
    logs = []
    try:
        for label, args, cwd, env in [
            ('backend', [java, '-jar', str(JAR), '--spring.config.additional-location=file:' +
                         (LOCAL / 'application-local.yml').as_posix()], ROOT, dict(os.environ)),
            ('frontend', [pnpm, 'dev', '--port', str(SETTINGS['frontend_port'])], ROOT / 'frontend',
             dict(os.environ, TERASCHOOL_BACKEND_URL=urls()['backend']))]:
            handle = (LOCAL / (label + '.log')).open('w', encoding='utf-8')
            logs.append(handle)
            children.append(subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                stdout=handle, stderr=subprocess.STDOUT, start_new_session=os.name != 'nt',
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0))
        wait_http(urls()['backend'] + '/api/session/csrf', children)
        wait_http(urls()['frontend'], children)
        # 契约从实际开发后端导出，前端派生仍由固定生成器维护。
        client = login(config, 'local-admin')
        with client.open(urls()['backend'] + '/v3/api-docs') as response:
            (ROOT / 'backend/applications/server/target/openapi.json').write_bytes(response.read())
        command([pnpm, 'run', 'generate'], config, cwd=ROOT / 'frontend', log='contract.log')
        write_json(RUNNING, {'workspace': WORKSPACE, 'port': server.server_port, 'token': token})
        threading.Thread(target=server.serve_forever, daemon=True).start()
        serving = True
        print('启动完成：' + urls()['frontend'], flush=True)
        if not getattr(start, 'no_open', False):
            open_browser(urls()['frontend'])
        print('后端：' + urls()['backend'] + '；数据库：' + urls()['database'], flush=True)
        print('账号密码：' + str(LOCAL / 'accounts.txt'), flush=True)
        print('保持此终端运行；Ctrl+C或stop-local.cmd停止前后端，WSL数据库继续运行。', flush=True)
        while not done.wait(0.5):
            if any(p.poll() is not None for p in children):
                raise RuntimeError('服务退出，请查看.local/dev日志。')
    finally:
        for child in children:
            stop_tree(child)
        for handle in logs:
            handle.close()
        if RUNNING.exists():
            RUNNING.unlink()
        if serving:
            server.shutdown()
        server.server_close()


def login(config, username):
    client = build_opener(HTTPCookieProcessor(CookieJar()))
    base = urls()['backend']
    with client.open(base + '/api/session/csrf') as response:
        csrf = json.load(response)
    body = urlencode({'username': username, 'password': config['passwords']['login'],
                      csrf['parameterName']: csrf['token']}).encode()
    with client.open(Request(base + '/api/session/login', data=body)) as response:
        if response.status != 204:
            raise RuntimeError('演示登录失败。')
    return client


def smoke(config, write=False):
    result = {}
    roles = json.loads(sql(config, "SELECT json_build_object("
        "'migration_super',(SELECT rolsuper FROM pg_roles WHERE rolname='ts_migrator'),"
        "'app_bypass',(SELECT rolbypassrls FROM pg_roles WHERE rolname='ts_app'),"
        "'unit_owner',(SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='organization.unit'::regclass),"
        "'seed_runs',(SELECT count(*) FROM dev.seed_history));"))
    if roles != {'migration_super': False, 'app_bypass': False, 'unit_owner': 'ts_migrator', 'seed_runs': 1}:
        raise RuntimeError('开发角色或重复装载检查不符合预期。')
    result['roles'] = roles
    for username, expected in [('local-admin', 6), ('local-reader', 3)]:
        client = login(config, username)
        with client.open(urls()['frontend'] + '/api/organization/units?size=100') as response:
            # 前后端同主机Cookie通用，通过Vite代理真实读取数据库。
            units = json.load(response)
        with client.open(urls()['frontend'] + '/api/organization/capabilities') as response:
            grants = json.load(response)['editableSchoolIds']
        if units['total'] < expected or (username == 'local-reader' and grants) or (username == 'local-admin' and len(grants) < 2):
            raise RuntimeError('演示范围/权限自检不符合预期。')
        result[username] = {'visible_units': units['total'], 'editable_schools': len(grants)}
        if username == 'local-reader':
            import uuid
            with client.open(urls()['frontend'] + '/api/session/csrf') as response:
                token = json.load(response)
            request = Request(urls()['frontend'] + '/api/organization/units',
                data=json.dumps({'requestId': str(uuid.uuid4()), 'schoolId': units['items'][0]['schoolId'],
                                 'kind': 'CAMPUS', 'name': '无权提交不应产生'}).encode(),
                headers={'Content-Type': 'application/json', token['headerName']: token['token']})
            try:
                client.open(request).close()
                raise RuntimeError('只读账号意外获得维护能力。')
            except HTTPError as error:
                if error.code != 403:
                    raise
            result[username]['write_denied'] = True
        if write and username == 'local-admin':
            record = LOCAL / 'smoke-request.json'
            if not record.exists():
                import uuid
                write_json(record, {'requestId': str(uuid.uuid4()), 'schoolId': grants[0],
                                    'kind': 'CAMPUS', 'name': '启动自检校区'})
            body = record.read_bytes()
            def post(path, payload):
                with client.open(urls()['frontend'] + '/api/session/csrf') as response:
                    token = json.load(response)
                request = Request(urls()['frontend'] + path, data=payload, headers={
                    'Content-Type': 'application/json', token['headerName']: token['token']})
                with client.open(request) as response:
                    return json.load(response)
            a = post('/api/organization/units', body)
            b = post('/api/organization/units', body)
            if a != b:
                raise RuntimeError('新增重试结果不一致。')
            with client.open(urls()['frontend'] + '/api/organization/units?size=100') as response:
                current = next(row for row in json.load(response)['items'] if row['id'] == a['id'])
            target_name = '启动自检校区（已改名）'
            if current['name'] != target_name:
                current = post('/api/organization/units/' + a['id'] + '/rename',
                    json.dumps({'expectedVersion': current['version'], 'name': target_name}).encode())
            result['write_check'] = {'created_id': a['id'], 'idempotent': True, 'rename': current['name'] == target_name}
    write_json(LOCAL / 'check-result.json', result)
    print('演示账号、学校范围、Vite代理' + ('及真实新增幂等' if write else '') + '检查通过。', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'start', 'stop', 'status', 'check'])
    parser.add_argument('--write', action='store_true', help='check时实际创建一条合成校区并验证重试')
    parser.add_argument('--no-open', action='store_true', help='启动成功后不自动打开浏览器')
    args = parser.parse_args()
    if args.action == 'status':
        print('运行中' if control('status') else '前后端未由本工具运行')
        print(json.dumps(urls(), ensure_ascii=False, indent=2))
        return
    if args.action == 'stop':
        was_running = bool(control('status'))
        if was_running:
            control('stop')
            for _ in range(40):
                if not control('status'):
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError('停止尚未完成，请查看启动终端。')
        with operation_lock(wait=10 if was_running else 0):
            pass
        lingering = []
        for port in (SETTINGS['backend_port'], SETTINGS['frontend_port']):
            with socket.socket() as listener:
                try:
                    listener.bind(('127.0.0.1', port))
                except OSError:
                    lingering.append(port)
        if lingering:
            print('警告：端口 ' + '、'.join(str(port) for port in lingering) +
                  ' 仍有进程监听，说明是上次异常退出留下的服务。'
                  '请在任务管理器结束残留的 java / node 进程后再启动。')
        print('前后端已停止，WSL PostgreSQL服务保持运行，开发数据保留。')
        return
    if args.action == 'check':
        if not STATE.exists() or not control('status'):
            raise RuntimeError('请先通过start-local启动本项目，再执行check。')
        smoke(configuration(), args.write)
        return
    if control('status'):
        print('前后端已经运行：' + urls()['frontend'])
        print('如果浏览器没有自动打开，请手动访问上面的地址。')
        print('需要重启时，先运行 stop-local.cmd 再运行本脚本。')
        if not args.no_open:
            open_browser(urls()['frontend'])
        # 专用退出码：批处理据此区分“已在运行”和“启动失败”，避免窗口一闪而过。
        sys.exit(3)
    private_directory()
    with operation_lock():
        config = configuration()
        if args.action == 'prepare':
            prepare(config)
        else:
            start.no_open = args.no_open
            start(config)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n前后端已停止；WSL PostgreSQL保持运行，数据保留。')
    except Exception as error:
        config = json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else None
        print('错误：' + redact(str(error), config), file=sys.stderr)
        # 2 = 启动/准备失败；与“服务已结束”(0) 和“已在运行”(3) 区分开。
        sys.exit(2)
