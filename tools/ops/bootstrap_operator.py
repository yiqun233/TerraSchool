"""平台运营账号初始化：创建第一个平台操作员。

平台账号与租户账号是两套身份（DEC-024），所以这个脚本只写 `platform` schema，不碰任何业务表。
已有同名登录时拒绝执行，不覆盖既有账号。

用法：

    python tools/ops/bootstrap_operator.py --login ops --name "运营甲"
    python tools/ops/bootstrap_operator.py --login ops --name "运营甲" --password-file ./pwd.txt

凭据同 bootstrap_admin.py，通过环境变量提供：

    TS_ADMIN_URL   例如 jdbc:postgresql://127.0.0.1:5432/teraschool
    TS_ADMIN_USER  迁移角色或 DBA
    TS_ADMIN_PASSWORD

需要 `psycopg2` 与 `bcrypt`。不给 --password-file 时自动生成并只打印一次。
"""
import argparse
import secrets
import string
import sys

try:
    import bcrypt
    import psycopg2
except ImportError as error:  # pragma: no cover
    raise SystemExit("需要 psycopg2 与 bcrypt：" + str(error))

# 首版平台动作目录；与 V81 的 CHECK 约束保持一致。
ACTIONS = ["TENANT_READ", "TENANT_CREATE", "TENANT_SUSPEND", "TENANT_RESUME", "PROVISIONING_READ"]


def jdbc_to_dsn(url: str) -> dict:
    if not url.startswith("jdbc:postgresql://"):
        raise SystemExit("TS_ADMIN_URL 必须是 jdbc:postgresql://host:port/db")
    rest = url[len("jdbc:postgresql://") :]
    host_port, _, database = rest.partition("/")
    database = database.split("?")[0]
    host, _, port = host_port.partition(":")
    if not host or not database:
        raise SystemExit("TS_ADMIN_URL 缺少主机或库名")
    return {"host": host, "port": int(port or 5432), "dbname": database}


def generate_password(length: int = 16) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def main() -> int:
    import os

    parser = argparse.ArgumentParser()
    parser.add_argument("--login", required=True, help="平台登录名")
    parser.add_argument("--name", required=True, help="显示名")
    parser.add_argument("--password-file", help="从文件读取初始密码，不落命令行")
    args = parser.parse_args()

    login = args.login.strip()
    name = args.name.strip()
    if len(login) < 3:
        raise SystemExit("登录名至少 3 个字符")
    if not name:
        raise SystemExit("显示名不能为空")

    url = os.environ.get("TS_ADMIN_URL")
    user = os.environ.get("TS_ADMIN_USER")
    password = os.environ.get("TS_ADMIN_PASSWORD")
    if not url or not user or not password:
        raise SystemExit("需要 TS_ADMIN_URL / TS_ADMIN_USER / TS_ADMIN_PASSWORD")

    initial = (
        open(args.password_file, encoding="utf-8").read().strip()
        if args.password_file
        else generate_password()
    )
    if len(initial) < 12:
        raise SystemExit("初始密码至少 12 位")
    hashed = "{bcrypt}" + bcrypt.hashpw(initial.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")

    connection = psycopg2.connect(**jdbc_to_dsn(url), user=user, password=password)
    connection.autocommit = False
    try:
        with connection.cursor() as cursor:
            cursor.execute("select 1 from platform.operator where login=%s", (login,))
            if cursor.fetchone():
                raise SystemExit("已存在同名平台登录，拒绝重复初始化")
            cursor.execute(
                "insert into platform.operator(login,password_hash,display_name)"
                " values (%s,%s,%s) returning id",
                (login, hashed, name),
            )
            operator_id = cursor.fetchone()[0]
            for action in ACTIONS:
                cursor.execute(
                    "insert into platform.operator_permission(operator_id,action,effect)"
                    " values (%s,%s,'ALLOW')",
                    (operator_id, action),
                )
            cursor.execute(
                "insert into platform.audit_event(operator_id,action,changed_fields,trace_id)"
                " values (%s,'PLATFORM_OPERATOR_CREATED',%s,uuidv7())",
                (operator_id, "login=" + login),
            )
        connection.commit()
    except SystemExit:
        connection.rollback()
        raise
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()

    print("平台操作员已创建：")
    print("  登录：" + login)
    print("  显示名：" + name)
    print("  密码：" + (initial if not args.password_file else "（来自 --password-file）"))
    print("请立即妥善保存；本输出不会再次显示。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
