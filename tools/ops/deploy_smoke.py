"""交付链冒烟：构建镜像、迁移、启动整套栈，验证「入口→后端→库」真的通了，然后清理。

只记录可观察事实，不推断生产可用：
1. 镜像构建成功；
2. 迁移把库推进到源码里的最高版本（与迁移文件比对，不只看“非空”）；
3. Nginx `/healthz` 返回 200（只证明入口进程活着）；
4. `/api/session/me` 返回 401 且响应体是后端自己的 RFC 9457 问题响应
   （含 `AUTHENTICATION_REQUIRED` 与 `X-Trace-Id`），证明请求确实到达后端而不是被入口拦掉；
5. 后端日志出现 `Started SchoolApplication` 与连接池建立记录，证明上下文与数据库连接真的起来了。

迁移方式（--migrate）：
- container：用 compose 的 migrate 服务（需要能拉取 flyway 镜像）；
- host：用宿主机 Maven Flyway 连 compose 映射出的回环端口；
- auto：先容器，任一失败再退回宿主机。

用法：python -X utf8 tools/ops/deploy_smoke.py [--migrate auto] [--keep]
结果写入 `.local/deploy-smoke/<run>/result.json`，默认结束时 `down -v` 并删除本次构建的镜像。
"""
from pathlib import Path
import argparse, json, os, re, secrets, shutil, socket, subprocess, sys, time, uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy" / "docker-compose.yml"
MIGRATIONS = ROOT / "backend/applications/server/src/main/resources/db/migration"
OUT = ROOT / ".local" / "deploy-smoke" / uuid.uuid4().hex[:12]
OUT.mkdir(parents=True)

parser = argparse.ArgumentParser()
parser.add_argument("--migrate", choices=["auto", "container", "host"], default="auto")
parser.add_argument("--keep", action="store_true")
args = parser.parse_args()

docker = shutil.which("docker") or str(
    Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/DockerDesktop/resources/bin/docker.exe"
)
project = "teraschool-smoke-" + OUT.name[:6]

# 源码里最高的迁移版本，作为“迁移到位”的唯一判据。
versions = [
    tuple(int(part) for part in re.findall(r"\d+", p.name.split("__")[0][1:]))
    for p in MIGRATIONS.glob("V*.sql")
]
EXPECTED_VERSION = ".".join(str(part) for part in max(versions))

# 每次随机口令，避免把开发口令带进演练。
env = dict(os.environ)
env.update(
    {
        "TS_ADMIN_PASSWORD": secrets.token_urlsafe(24),
        "TS_MIGRATOR_PASSWORD": secrets.token_urlsafe(24),
        "TS_APP_PASSWORD": secrets.token_urlsafe(24),
        "TS_IDENTITY_PASSWORD": secrets.token_urlsafe(24),
        "TS_SESSION_PASSWORD": secrets.token_urlsafe(24),
        "TS_AUDIT_PASSWORD": secrets.token_urlsafe(24),
        "TS_SCHEDULER_PASSWORD": secrets.token_urlsafe(24),
        "TS_PLATFORM_PASSWORD": secrets.token_urlsafe(24),
        "TS_COOKIE_SECURE": "false",
        "COMPOSE_PROJECT_NAME": project,
    }
)
DB_NAME = env.get("TS_DATABASE_NAME", "teraschool")
private = [v for k, v in env.items() if k.startswith("TS_") and k.endswith("_PASSWORD")]


def safe(text: str) -> str:
    for value in private:
        text = text.replace(value, "[REDACTED]")
    return text


def free_port(used: set[int]) -> int:
    while True:
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        if port not in used:
            used.add(port)
            return port


http_port, db_port = free_port(set()), free_port(set())
env["TS_HTTP_PORT"] = str(http_port)
env["TS_DB_PORT"] = str(db_port)
base = f"http://127.0.0.1:{http_port}"

report = {"run_id": OUT.name, "project": project, "port": http_port, "status": "running"}
checks = {}


def compose(*extra, timeout=1200):
    return subprocess.run(
        [docker, "compose", "-f", str(COMPOSE), "--project-name", project, *extra],
        cwd=ROOT, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )


def http(path):
    """返回 (状态码, 响应体, 响应头小写字典)。"""
    import urllib.error, urllib.request
    try:
        with urllib.request.urlopen(base + path, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "replace")[:400], {
                k.lower(): v for k, v in r.headers.items()
            }
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400], {
            k.lower(): v for k, v in e.headers.items()
        }
    except Exception as e:  # noqa: BLE001 - 记录真实连接错误
        return None, str(e), {}


def wait_port(port: int, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(1)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(2)
    return False


def wait_db_init():
    for _ in range(60):
        state = compose("ps", "-a", "--format", "{{.Service}}:{{.State}}:{{.ExitCode}}").stdout
        if "db-init:exited:0" in state.replace(" ", ""):
            return
        time.sleep(2)
    raise RuntimeError("db-init 未成功完成：" + compose("ps", "-a").stdout)


def migrate_container():
    run = compose("--profile", "migrate", "run", "--rm", "migrate", timeout=900)
    (OUT / "migrate-container.log").write_text(safe(run.stdout + run.stderr), encoding="utf-8")
    return run.returncode


def migrate_host():
    mvn = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    cmd = [
        str(mvn), "-B", "-ntp",
        "-s", str(ROOT / "backend/.mvn/settings.xml"),
        "-gs", str(ROOT / "backend/.mvn/settings.xml"),
        "-Dmaven.repo.local=" + str(ROOT / ".local/maven/repository"),
        "-Dmigration.user=ts_migrator",
        "-N", "flyway:migrate",
    ]
    run_env = dict(env)
    run_env["TS_JDBC_URL"] = f"jdbc:postgresql://127.0.0.1:{db_port}/{DB_NAME}"
    run_env["TS_ADMIN_PASSWORD"] = env["TS_MIGRATOR_PASSWORD"]
    p = subprocess.run(cmd, cwd=ROOT / "backend", env=run_env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=900)
    (OUT / "migrate-host.log").write_text(safe(p.stdout + p.stderr), encoding="utf-8")
    return p.returncode


try:
    build = compose("build", timeout=2400)
    (OUT / "build.log").write_text(safe(build.stdout + build.stderr), encoding="utf-8")
    if build.returncode:
        raise RuntimeError("镜像构建失败，见 " + str(OUT / "build.log"))
    checks["image_build"] = "passed"

    up_db = compose("up", "-d", "db", "db-init", timeout=600)
    (OUT / "up-db.log").write_text(safe(up_db.stdout + up_db.stderr), encoding="utf-8")
    if up_db.returncode:
        raise RuntimeError("启动 db/db-init 失败，见 " + str(OUT / "up-db.log"))
    wait_db_init()
    checks["db_init"] = "passed"

    mode = args.migrate
    if mode == "container":
        if migrate_container() != 0:
            raise RuntimeError("容器迁移失败，见 " + str(OUT / "migrate-container.log"))
        checks["migrate"] = "container"
    elif mode == "host":
        if not wait_port(db_port):
            raise RuntimeError("等待宿主机数据库端口超时")
        if migrate_host() != 0:
            raise RuntimeError("宿主机迁移失败，见 " + str(OUT / "migrate-host.log"))
        checks["migrate"] = "host"
    else:
        # auto：容器迁移失败的原因很多（镜像不可拉、版本不兼容、网络），
        # 一律退回宿主机路径，避免只认某一种报错文本。
        ok = False
        try:
            ok = migrate_container() == 0
        except Exception as exc:  # noqa: BLE001
            checks["container_migrate_error"] = safe(str(exc))[:200]
        if ok:
            checks["migrate"] = "container"
        else:
            checks.setdefault("container_migrate_error", "容器迁移返回非零")
            if not wait_port(db_port):
                raise RuntimeError("等待宿主机数据库端口超时")
            if migrate_host() != 0:
                raise RuntimeError("宿主机迁移失败，见 " + str(OUT / "migrate-host.log"))
            checks["migrate"] = "host"

    up = compose("up", "-d", "--wait", "--wait-timeout", "300")
    (OUT / "up.log").write_text(safe(up.stdout + up.stderr), encoding="utf-8")
    if up.returncode:
        raise RuntimeError("compose 启动失败，见 " + str(OUT / "up.log"))
    checks["compose_up"] = "passed"

    status = None
    for _ in range(60):
        status, _body, _headers = http("/healthz")
        if status == 200:
            break
        time.sleep(2)
    checks["healthz"] = status

    # 真实存在的会话端点：401 且响应体是后端自己的 RFC 9457 问题响应。
    session_status, body, headers = http("/api/session/me")
    checks["session_status"] = session_status
    checks["session_problem_code"] = (
        "AUTHENTICATION_REQUIRED" if "AUTHENTICATION_REQUIRED" in body else None
    )
    checks["session_trace_id"] = bool(headers.get("x-trace-id"))

    logs = compose("logs", "--no-color", "backend", timeout=120).stdout
    (OUT / "backend.log").write_text(safe(logs), encoding="utf-8")
    checks["backend_started"] = "Started SchoolApplication" in logs
    checks["backend_db_pool"] = "HikariPool" in logs and "Start completed" in logs

    psql = compose(
        "exec", "-T", "db", "psql", "-U", "postgres", "-d", DB_NAME, "-tAc",
        "select version from flyway_schema_history where success order by installed_rank desc limit 1",
    )
    version = psql.stdout.strip()
    checks["schema_version"] = version
    checks["schema_expected"] = EXPECTED_VERSION

    ok = (
        checks.get("healthz") == 200
        and checks.get("session_status") == 401
        and checks.get("session_problem_code") == "AUTHENTICATION_REQUIRED"
        and checks.get("session_trace_id") is True
        and checks.get("backend_started") is True
        and version == EXPECTED_VERSION
    )
    report["checks"] = checks
    report["status"] = "passed" if ok else "failed"
    if not ok:
        report["error"] = "入口、后端或迁移未达预期"
except BaseException as exc:  # noqa: BLE001
    report["status"] = "failed"
    report.setdefault("error", safe(str(exc))[:1500])
    raise
finally:
    if not args.keep:
        compose("down", "-v", "--remove-orphans", timeout=300)
        # down -v 不会删除镜像；本次构建的两个镜像每次约 1.1GB，不收就会堆积。
        removed = []
        for name in (f"{project}-backend", f"{project}-web"):
            r = subprocess.run([docker, "rmi", "-f", name], capture_output=True, text=True)
            removed.append(r.returncode == 0)
        report["images_removed"] = all(removed)
    (OUT / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(str(OUT / "result.json"))
