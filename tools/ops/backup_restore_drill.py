"""备份恢复演练：真的把库 dump 出来、恢复到另一个库、再逐表核对。

目的不是"有个备份脚本"，而是给出可复现的数字：dump 花了多久、restore 花了多久、恢复后行数是否一致。

用法（本机 WSL PostgreSQL）：

    python tools/ops/backup_restore_drill.py --source teraschool_dev --target teraschool_restore_drill

会依次执行：

1. `pg_dump --format=custom` 到临时文件，记录耗时与体积；
2. 建一个空的演练库；
3. `pg_restore` 进去，记录耗时；
4. 核对：`flyway_schema_history` 条数、每张业务表行数、RLS 是否仍然启用；
5. 输出报告（`report.json` + 控制台），最后删掉演练库。

失败即非零退出；核对不一致也算失败——恢复出来的库必须是**同一个库**，不是"导入成功"。
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

# 只核对业务表，迁移/审计表单独看条数即可。
BUSINESS_TABLES = [
    "identity.tenant",
    "organization.unit",
    "identity.account",
    "archive.student",
    "product.product",
    "enrollment.enrollment",
    "funds.receipt",
    "funds.account_entry",
    "teaching.lesson",
    "teaching.consumption",
]


def run(command: list[str], *, stdin_text: str | None = None, timeout: int = 900) -> subprocess.CompletedProcess:
    return subprocess.run(command, input=stdin_text, capture_output=True, text=True, timeout=timeout)


class WslRunner:
    """在 WSL 里以 postgres 用户执行 psql/pg_dump；Windows 上没有这些客户端。"""

    def __init__(self, distro: str, port: int, user: str = "postgres"):
        self.distro = distro
        self.port = port
        self.user = user

    def psql(self, database: str, sql: str, *, timeout: int = 300) -> str:
        command = [
            "wsl",
            "-d",
            self.distro,
            "-u",
            "root",
            "--",
            "runuser",
            "-u",
            self.user,
            "--",
            "psql",
            "-X",
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-p",
            str(self.port),
            "-d",
            database,
        ]
        result = run(command, stdin_text=sql, timeout=timeout)
        if result.returncode != 0:
            raise RuntimeError("psql 失败：" + (result.stderr or result.stdout)[-500:])
        return result.stdout.strip()

    def pg_dump(self, database: str, target: Path) -> float:
        # 先 dump 到 WSL 内的临时文件，再拷出来，避免 Windows/WSL 路径混用。
        inner = "/tmp/teraschool-drill.dump"
        started = time.perf_counter()
        command = [
            "wsl",
            "-d",
            self.distro,
            "-u",
            "root",
            "--",
            "runuser",
            "-u",
            self.user,
            "--",
            "pg_dump",
            "-Fc",
            "-p",
            str(self.port),
            "-d",
            database,
            "-f",
            inner,
        ]
        result = run(command)
        if result.returncode != 0:
            raise RuntimeError("pg_dump 失败：" + (result.stderr or result.stdout)[-500:])
        elapsed = time.perf_counter() - started
        # WSL 的盘符目录是小写 /mnt/d，Windows 路径带大写盘符，必须转小写才能拷出来。
        windows_path = str(target.resolve()).replace("\\", "/")
        drive = windows_path[0].lower()
        wsl_target = "/mnt/" + drive + windows_path[2:]
        self.dump_path = inner
        self.local_copy_ok = (
            run(["wsl", "-d", self.distro, "-u", "root", "--", "cp", inner, wsl_target]).returncode
            == 0
        )
        self.dump_size = int(
            run(
                ["wsl", "-d", self.distro, "-u", "root", "--", "stat", "-c", "%s", inner]
            ).stdout.strip()
            or 0
        )
        return elapsed

    def pg_restore(self, database: str) -> float:
        started = time.perf_counter()
        command = [
            "wsl",
            "-d",
            self.distro,
            "-u",
            "root",
            "--",
            "runuser",
            "-u",
            self.user,
            "--",
            "pg_restore",
            "--no-owner",
            "--no-privileges",
            "-p",
            str(self.port),
            "-d",
            database,
            self.dump_path,
        ]
        result = run(command)
        if result.returncode != 0:
            raise RuntimeError("pg_restore 失败：" + (result.stderr or result.stdout)[-800:])
        return time.perf_counter() - started


def table_counts(runner: WslRunner, database: str) -> dict[str, int]:
    sql = "\n".join(
        f"select '{table}=' || coalesce(count(*),0)::text from {table};" for table in BUSINESS_TABLES
    )
    sql += "\nselect 'flyway_schema_history=' || count(*)::text from flyway_schema_history;"
    output = runner.psql(database, sql)
    counts: dict[str, int] = {}
    for line in output.splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            counts[key] = int(value)
    return counts


def rls_enabled(runner: WslRunner, database: str, table: str) -> bool:
    schema, _, name = table.partition(".")
    output = runner.psql(
        database,
        f"select relrowsecurity || '/' || relforcerowsecurity from pg_class c"
        f" join pg_namespace n on n.oid=c.relnamespace"
        f" where n.nspname='{schema}' and c.relname='{name}';",
    )
    return output.strip() == "true/true"


def main() -> int:
    parser = argparse.ArgumentParser(description="备份恢复演练：dump → restore → 核对")
    parser.add_argument("--source", default="teraschool_dev", help="源库名")
    parser.add_argument("--target", default="teraschool_restore_drill", help="演练目标库名")
    parser.add_argument("--distro", default="Ubuntu", help="WSL 发行版")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--out-dir", type=Path, default=Path(".local/ops"))
    parser.add_argument("--keep", action="store_true", help="演练后保留目标库（默认删除）")
    args = parser.parse_args()

    runner = WslRunner(args.distro, args.port)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    report: dict = {"source": args.source, "target": args.target, "steps": []}
    target_created = False
    try:
        started = time.perf_counter()
        source_counts = table_counts(runner, args.source)
        report["source_counts"] = source_counts
        report["source_tables"] = len(source_counts)

        dump_seconds = runner.pg_dump(args.source, args.out_dir / "drill.dump")
        report["dump_seconds"] = round(dump_seconds, 2)
        report["dump_bytes"] = runner.dump_size
        report["dump_copied_to_windows"] = runner.local_copy_ok

        runner.psql("postgres", f'drop database if exists "{args.target}" with (force);')
        runner.psql("postgres", f'create database "{args.target}";')
        target_created = True

        restore_seconds = runner.pg_restore(args.target)
        report["restore_seconds"] = round(restore_seconds, 2)

        target_counts = table_counts(runner, args.target)
        report["target_counts"] = target_counts
        report["counts_match"] = target_counts == source_counts

        rls = {table: rls_enabled(runner, args.target, table) for table in BUSINESS_TABLES}
        report["rls_enforced"] = rls
        report["all_rls_enforced"] = all(rls.values())

        report["total_seconds"] = round(time.perf_counter() - started, 2)
        report["status"] = (
            "passed" if report["counts_match"] and report["all_rls_enforced"] else "failed"
        )
    except Exception as error:  # noqa: BLE001 - 演练失败要把原因写进报告
        report["status"] = "failed"
        report["error"] = str(error)
    finally:
        if target_created and not args.keep:
            try:
                runner.psql("postgres", f'drop database if exists "{args.target}" with (force);')
                report["target_dropped"] = True
            except Exception as error:  # noqa: BLE001
                report["target_dropped"] = False
                report["drop_error"] = str(error)
        (args.out_dir / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    print(
        json.dumps(
            {
                "status": report["status"],
                "dump_seconds": report.get("dump_seconds"),
                "restore_seconds": report.get("restore_seconds"),
                "counts_match": report.get("counts_match"),
                "all_rls_enforced": report.get("all_rls_enforced"),
                "error": report.get("error"),
            },
            ensure_ascii=False,
        )
    )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
