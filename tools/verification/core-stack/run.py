"""R3-CORE-001：Windows/CI 可重复运行的隔离验证，绝不使用现有业务数据库。"""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from process_tree import stop_tree

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN_ID = uuid.uuid4().hex[:12]
ARTIFACTS = ROOT / '.local' / 'verification' / 'core-stack' / RUN_ID
ARTIFACTS.mkdir(parents=True, exist_ok=True)
environment = dict(os.environ)
environment.update({
    'PROBE_ADMIN_PASSWORD': secrets.token_urlsafe(30),
    'PROBE_APP_PASSWORD': secrets.token_urlsafe(30),
    'PROBE_SESSION_PASSWORD': secrets.token_urlsafe(30),
    'PROBE_FIXTURE_PASSWORD': secrets.token_urlsafe(30),
})
private_values = [environment[k] for k in ('PROBE_ADMIN_PASSWORD', 'PROBE_APP_PASSWORD', 'PROBE_SESSION_PASSWORD', 'PROBE_FIXTURE_PASSWORD')]
java_home = environment.get('PROBE_JAVA_HOME') or environment.get('JAVA_HOME')
if not java_home:
    raise SystemExit('需要本轮验证基线 JDK 21；通过 PROBE_JAVA_HOME 指定，不修改全局环境。')
environment['JAVA_HOME'] = java_home
environment['PATH'] = str(Path(java_home) / 'bin') + os.pathsep + environment['PATH']
environment['MAVEN_USER_HOME'] = str(ROOT / '.local/maven')
temporary_files = ROOT / '.local/tmp'
temporary_files.mkdir(parents=True, exist_ok=True)
environment['TEMP'] = environment['TMP'] = str(temporary_files)
environment['JDK_JAVA_OPTIONS'] = environment.get('JDK_JAVA_OPTIONS', '') + ' -Djava.io.tmpdir="' + str(temporary_files) + '"'
environment['MAVEN_OPTS'] = environment.get('MAVEN_OPTS', '') + ' -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US'
settings = str(HERE / 'backend/.mvn/verification-settings.xml')
maven_arguments = ['-B', '-ntp', '-s', settings, '-gs', settings, '-Dmaven.repo.local=' + str(ROOT / '.local/maven/repository')]
report = {'task': 'R3-CORE-001', 'run_id': RUN_ID, 'started_utc': datetime.now(timezone.utc).isoformat(), 'status': 'running', 'steps': [], 'java_home': java_home}
container_id = None

def redact(value):
    for private in private_values:
        value = value.replace(private, '[REDACTED]')
    return value

def capture(args, timeout=30):
    completed = subprocess.run(args, env=environment, cwd=HERE, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if completed.returncode:
        raise RuntimeError(redact((completed.stdout + completed.stderr)[-3000:]))
    return completed.stdout.strip()

def step(label, args, cwd, timeout=600):
    print(f'[{label}] started', flush=True)
    start = time.monotonic()
    log = ARTIFACTS / (label + '.log')
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(args, env=environment, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', start_new_session=(os.name != 'nt'))
        # Timer 在子进程超时后终止本次构建；输出流不泄漏环境中的随机凭据。
        import threading
        timer = threading.Timer(timeout, stop_tree, args=(process,))
        timer.start()
        try:
            for line in process.stdout:
                safe = redact(line)
                output.write(safe)
                if any(marker in safe for marker in ('[ERROR]', 'BUILD SUCCESS', 'BUILD FAILURE', 'Tests run:', 'ERROR', 'Done in', 'built in')):
                    print(safe.rstrip()[:1000], flush=True)
            code = process.wait()
        finally:
            timer.cancel()
            stop_tree(process)
    result = {'step': label, 'exit_code': code, 'seconds': round(time.monotonic() - start, 2), 'log': str(log.relative_to(ROOT))}
    report['steps'].append(result)
    if code:
        tail = '\n'.join(log.read_text(encoding='utf-8').splitlines()[-45:])
        print(tail, flush=True)
        raise RuntimeError(f'{label} failed with exit code {code}')
    print(f'[{label}] passed', flush=True)

def native(command, *args):
    # Windows 的 cmd 脚本通过 subprocess 直接调用；不拼接用户输入或秘密。
    executable = shutil.which(command + '.cmd' if os.name == 'nt' else command)
    if not executable:
        raise RuntimeError(f'找不到工具：{command}')
    return [executable, *args]

try:
    step('runner-process-cleanup', [sys.executable, 'process_tree_test.py'], HERE, timeout=20)
    java = capture([str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')), '--version'])
    if '21.' not in java.splitlines()[0]:
        raise RuntimeError('本轮可用基线是 Java 21；Java 25 的本机命名管道限制另记，不静默切换。')
    report['java_version'] = java
    report['docker_version'] = json.loads(capture(['docker', 'version', '--format', '{{json .}}']))
    if report['docker_version']['Server']['Os'] != 'linux':
        raise RuntimeError('需要 Linux 容器引擎。')
    # Testcontainers 不一定识别 Docker CLI 的当前 context；显式使用同一受控端点。
    context = capture(['docker', 'context', 'show'])
    endpoint = capture(['docker', 'context', 'inspect', context, '--format', '{{.Endpoints.docker.Host}}'])
    if endpoint.startswith(('npipe://', 'unix://')):
        environment['DOCKER_HOST'] = endpoint
    report['docker_endpoint'] = endpoint
    image = 'postgres@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4'
    try:
        image_info = json.loads(capture(['docker', 'image', 'inspect', image]))[0]
        report['steps'].append({'step': 'docker-image', 'exit_code': 0, 'source': 'existing immutable digest'})
        print('[docker-image] verified local digest', flush=True)
    except RuntimeError:
        step('docker-image', ['docker', 'pull', image], HERE)
        image_info = json.loads(capture(['docker', 'image', 'inspect', image]))[0]
    # 使用本次解析出的不可变镜像 ID 启动迁移库；记录仓库 digest 供复核。
    report['postgres_image'] = {'tag': image, 'id': image_info['Id'], 'repo_digests': image_info.get('RepoDigests', [])}
    environment['PROBE_POSTGRES_IMAGE'] = (image_info.get('RepoDigests') or [image])[0]
    environment['POSTGRES_PASSWORD'] = environment['PROBE_ADMIN_PASSWORD']
    environment['POSTGRES_DB'] = 'core_probe'
    container_id = capture(['docker', 'run', '--detach', '--rm', '--name', 'teraschool-core-probe-' + RUN_ID,
        '--label', 'org.teraschool.verification=core-stack', '--env', 'POSTGRES_PASSWORD', '--env', 'POSTGRES_DB',
        '--publish', '127.0.0.1::5432', image_info['Id']])
    details = json.loads(capture(['docker', 'inspect', container_id]))[0]
    assert details['Config']['Labels'].get('org.teraschool.verification') == 'core-stack'
    port = details['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
    environment['PROBE_JDBC_URL'] = f'jdbc:postgresql://127.0.0.1:{port}/core_probe'
    report['temporary_database'] = {'name': 'core_probe', 'port': int(port), 'container_id': container_id, 'existing_database_used': False}
    for attempt in range(30):
        ready = subprocess.run(['docker', 'exec', container_id, 'pg_isready', '-U', 'postgres', '-d', 'core_probe'], capture_output=True, env=environment, timeout=5)
        if ready.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('临时 PostgreSQL 未就绪。')

    backend = HERE / 'backend'
    wrapper = backend / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    if not wrapper.exists():
        step('maven-wrapper', native('mvn', *maven_arguments, 'org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper', '-Dmaven=3.9.12', '-Dtype=only-script'), backend)
    step('backend', [str(wrapper), *maven_arguments, 'clean', 'flyway:migrate', 'jooq-codegen:generate', 'test'], backend)
    suites = []
    for path in (backend / 'target/surefire-reports').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        suites.append({k: suite.attrib[k] for k in ('name', 'tests', 'failures', 'errors', 'skipped')})
    report['test_suites'] = suites
    assert suites and all(s['failures'] == s['errors'] == s['skipped'] == '0' for s in suites), '测试不能失败或跳过'
    schema = backend / 'target/openapi.json'
    assert schema.is_file(), '必须由实际 HTTP 接口导出 OpenAPI'
    report['openapi_sha256'] = hashlib.sha256(schema.read_bytes()).hexdigest()
    frontend = HERE / 'frontend'
    install = ['install', '--ignore-scripts', '--strict-peer-dependencies', '--store-dir', str(ROOT / '.local/pnpm-store')]
    if (frontend / 'pnpm-lock.yaml').exists():
        install.append('--frozen-lockfile')
    step('frontend-install', native('pnpm', *install), frontend)
    for label, command in [('openapi-types', 'generate'), ('typescript', 'typecheck'), ('vite-build', 'build')]:
        step(label, native('pnpm', 'run', command), frontend)
    step('resolved-dependencies', [str(wrapper), *maven_arguments, 'dependency:list', '-DincludeScope=test', '-DoutputFile=target/resolved-dependencies.txt'], backend)
    report['status'] = 'passed'
except (Exception, KeyboardInterrupt) as error:
    report['status'] = 'failed'
    report['error'] = redact(str(error))
    print('[verification] ' + report['error'], flush=True)
finally:
    if container_id:
        try:
            detail = json.loads(capture(['docker', 'inspect', container_id]))[0]
            if detail['Config']['Labels'].get('org.teraschool.verification') != 'core-stack':
                raise RuntimeError('清理标签不匹配，停止自动清理。')
            capture(['docker', 'stop', '--time', '5', container_id])
            report['temporary_container_removed'] = True
        except Exception as error:
            report['temporary_container_removed'] = False
            report['cleanup_error'] = redact(str(error))
            report['status'] = 'failed'
    report['finished_utc'] = datetime.now(timezone.utc).isoformat()
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    assert all(private not in encoded for private in private_values)
    (ARTIFACTS / 'result.json').write_text(encoded, encoding='utf-8')
    print(json.dumps({'status': report['status'], 'result': str(ARTIFACTS / 'result.json'), 'temporary_container_removed': report.get('temporary_container_removed')}, ensure_ascii=False), flush=True)
sys.exit(0 if report['status'] == 'passed' else 1)
