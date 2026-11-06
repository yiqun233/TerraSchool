"""历史升级路径演练：用真实 PostgreSQL 从旧版本带数据升到最新，验证既有业务行不被破坏。

为什么需要：Flyway 只保证“干净库从头执行”通过，不保证“已有数据的旧版本就地升级”通过。
V77 重建 `creation_request_kind_check` 时漏掉 V58 已支持的 `CLASS_WITHDRAWAL`，干净建库看不出来，
但一个 V76 库只要存在退班请求行，`ALTER TABLE ... ADD CONSTRAINT` 就会失败，升级中断。

本脚本每次使用可销毁的临时容器，不接受已有数据库：
1. 建库与受限角色（与 verify.py 同口径）；
2. 迁移到 `--from` 指定版本；
3. 写入目标版本合法的业务行（默认一条 `CLASS_WITHDRAWAL` 请求）；
4. 继续迁移到最新；
5. 断言旧行仍在、约束存在且可用。

用法：python -X utf8 tools/ops/upgrade_drill.py [--from 76] [--keep]
结果写入 `.local/upgrade-drill/<run>/result.json`，失败时保留日志路径。
"""
from pathlib import Path
import argparse, json, os, secrets, shutil, subprocess, sys, time, uuid

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".local" / "upgrade-drill" / uuid.uuid4().hex[:12]
OUT.mkdir(parents=True)

parser = argparse.ArgumentParser()
parser.add_argument("--from", dest="from_version", default="76", help="起始版本，默认 76")
parser.add_argument("--keep", action="store_true", help="演练结束后保留容器")
args = parser.parse_args()

env = dict(os.environ)
java = env.get("JAVA_HOME", "")
java_bin = Path(java) / ("bin/java.exe" if os.name == "nt" else "bin/java")
if not java_bin.exists():
    raise SystemExit("请先配置 JDK 25 的 JAVA_HOME")
env["MAVEN_USER_HOME"] = str(ROOT / ".local/maven")

for name in ["ADMIN", "APP", "IDENTITY", "SESSION", "AUDIT", "SCHEDULER"]:
    env["TS_" + name + "_PASSWORD"] = secrets.token_urlsafe(30)
private = [v for k, v in env.items() if k.startswith("TS_") and k.endswith("_PASSWORD")]


def safe(text: str) -> str:
    for value in private:
        text = text.replace(value, "[REDACTED]")
    return text


docker = shutil.which("docker") or str(
    Path(env.get("LOCALAPPDATA", "")) / "Programs/DockerDesktop/resources/bin/docker.exe"
)


def run(command, label=None, check=True, stdin=None, cwd=ROOT):
    """执行命令；输出写入日志，失败时抛出带日志路径的异常。"""
    p = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        input=stdin,
        timeout=900,
    )
    if label:
        (OUT / (label + ".log")).write_text(
            safe(p.stdout) + "\n" + safe(p.stderr), encoding="utf-8"
        )
    if check and p.returncode:
        name = label or Path(command[0]).name
        raise RuntimeError(f"{name} 失败（exit {p.returncode}）：{safe(p.stderr)[-800:]}")
    return p


def psql(sql):
    return run(
        [
            docker,
            "exec",
            "-i",
            container,
            "psql",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-d",
            "teraschool_test",
            "-tAc",
            sql,
        ],
        check=True,
    )


def flyway(target=None):
    mvn = [
        str(ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
        "-B",
        "-ntp",
        "-s",
        str(ROOT / "backend/.mvn/settings.xml"),
        "-gs",
        str(ROOT / "backend/.mvn/settings.xml"),
        "-Dmaven.repo.local=" + str(ROOT / ".local/maven/repository"),
        "-N",
        "flyway:migrate",
    ]
    if target:
        mvn.append("-Dflyway.target=" + target)
    # flyway 插件声明在 backend/pom.xml，必须在 backend 目录执行才能解析插件前缀。
    return run(mvn, "flyway-" + (target or "head"), check=False, cwd=ROOT / "backend")


container = None
report = {"run_id": OUT.name, "from": args.from_version, "status": "running"}
try:
    context = run([docker, "context", "show"]).stdout.strip()
    env["DOCKER_HOST"] = run(
        [docker, "context", "inspect", context, "--format", "{{.Endpoints.docker.Host}}"]
    ).stdout.strip()
    image = "postgres@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4"
    env["POSTGRES_PASSWORD"] = env["TS_ADMIN_PASSWORD"]
    env["POSTGRES_DB"] = "teraschool_test"
    container = run(
        [
            docker, "run", "-d", "--rm", "--name", "teraschool-upgrade-" + OUT.name,
            "--label", "org.teraschool.upgrade=" + OUT.name,
            "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB",
            "-p", "127.0.0.1::5432", image, "-c", "max_connections=100",
        ]
    ).stdout.strip()
    details = json.loads(run([docker, "inspect", container]).stdout)[0]
    port = details["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
    env["TS_JDBC_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/teraschool_test"
    for _ in range(40):
        if run([docker, "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
               check=False).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("PostgreSQL 未就绪")

    roles = "\n".join(
        "CREATE ROLE ts_" + kind + " LOGIN PASSWORD '" + env["TS_" + kind.upper() + "_PASSWORD"] +
        "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;"
        for kind in ["app", "identity", "session", "audit", "scheduler"]
    )
    run(
        [docker, "exec", "-i", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres",
         "-d", "teraschool_test"],
        "roles",
        stdin=roles,
    )

    # 1) 迁移到起始版本
    if flyway(args.from_version).returncode:
        raise RuntimeError("起始版本迁移失败，无法构造升级前状态")
    version_query = (
        "select version from flyway_schema_history where success order by installed_rank desc limit 1"
    )
    version_before = psql(version_query).stdout.strip()

    # 2) 写入该版本合法的业务行：退班请求（V58 起合法）
    seed = (
        "insert into teaching.creation_request(tenant_id,actor_id,request_id,kind,resource_id,fingerprint)"
        f" values ('{uuid.uuid4()}','{uuid.uuid4()}','{uuid.uuid4()}','CLASS_WITHDRAWAL',"
        f"'{uuid.uuid4()}','drill');"
    )
    run(
        [docker, "exec", "-i", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres",
         "-d", "teraschool_test"],
        "seed",
        stdin=seed,
    )

    # 3) 升级到最新
    upgrade = flyway()
    report["upgrade_exit"] = upgrade.returncode
    if upgrade.returncode:
        report["status"] = "failed"
        report["error"] = "带数据升级失败：" + safe((upgrade.stderr or upgrade.stdout)[-1500:])
        raise RuntimeError(report["error"])

    # 4) 断言旧行仍在、约束有效
    version_after = psql(version_query).stdout.strip()
    survived = psql(
        "select count(*) from teaching.creation_request where fingerprint='drill'"
    ).stdout.strip()
    # 同名约束在多个 schema 的 creation_request 上都有，必须按 schema+表限定。
    constraint_query = (
        "select pg_get_constraintdef(c.oid) from pg_constraint c"
        " join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace"
        " where c.conname='creation_request_kind_check' and n.nspname='teaching'"
        " and t.relname='creation_request'"
    )
    constraint = psql(constraint_query).stdout.strip()
    valid = psql(
        "select count(*) from pg_constraint c"
        " join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace"
        " where c.conname='creation_request_kind_check' and n.nspname='teaching'"
        " and t.relname='creation_request' and c.convalidated"
    ).stdout.strip()

    report.update(
        version_before=version_before,
        version_after=version_after,
        preserved_rows=int(survived),
        constraint=constraint,
        constraint_validated=valid == "1",
    )
    ok = int(survived) == 1 and "CLASS_WITHDRAWAL" in constraint and valid == "1"
    report["status"] = "passed" if ok else "failed"
    if not ok:
        report["error"] = "升级后旧行或约束不符合预期"
except BaseException as exc:  # noqa: BLE001 - 记录任何失败原因
    report["status"] = "failed"
    report.setdefault("error", safe(str(exc)))
    raise
finally:
    if container and not args.keep:
        try:
            run([docker, "stop", container], check=False)
            report["container_removed"] = True
        except Exception:
            report["container_removed"] = False
    (OUT / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(str(OUT / "result.json"))
