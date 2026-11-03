"""生产首次初始化：创建机构、第一所学校与首个管理员账号。

只用于空库或新机构；已有同名机构或同名登录时拒绝执行，不覆盖任何既有数据。

用法（在数据库主机或能连到数据库的机器上）：

    python tools/ops/bootstrap_admin.py --tenant "示例培训" --school "示例校区" --login admin
    python tools/ops/bootstrap_admin.py --tenant ... --school ... --login admin --password-file ./pwd.txt

凭据通过环境变量提供，不写命令行、不落仓库：

    TS_ADMIN_URL   例如 jdbc:postgresql://127.0.0.1:5432/teraschool
    TS_ADMIN_USER  拥有建表权限的角色（通常是迁移角色或 DBA）
    TS_ADMIN_PASSWORD

脚本需要 `psycopg2` 与 `bcrypt`。密码不给 --password-file 时自动生成并只打印一次。
"""
import argparse
import secrets
import string
import sys
from pathlib import Path

try:
    import bcrypt
    import psycopg2
except ImportError as error:  # pragma: no cover - 环境缺失时直接说明
    raise SystemExit("需要 psycopg2 与 bcrypt：" + str(error))

ACTIONS = [
    "ORGANIZATION_READ",
    "ORGANIZATION_MAINTAIN",
    "AUTHORIZATION_MANAGE",
    "EMPLOYEE_READ",
    "EMPLOYEE_MAINTAIN",
    "TEACHER_READ",
    "TEACHER_MAINTAIN",
    "ROOM_READ",
    "ROOM_MAINTAIN",
    "PERSONNEL_READ",
    "PERSONNEL_MAINTAIN",
    "STUDENT_READ",
    "STUDENT_MAINTAIN",
    "STUDENT_SENSITIVE_READ",
    "PRODUCT_READ",
    "PRODUCT_MAINTAIN",
    "PRICE_PUBLISH",
    "ENROLLMENT_READ",
    "ENROLLMENT_MAINTAIN",
    "ENROLLMENT_APPROVE",
    "FUNDS_READ",
    "FUNDS_RECEIPT",
    "FUNDS_ALLOCATE",
    "TEACHING_READ",
    "TEACHING_MAINTAIN",
    "ATTENDANCE_RECORD",
    "LESSON_CLOSE",
    "REFUND_READ",
    "REFUND_APPLY",
    "REFUND_PAY",
    "APPROVAL_READ",
    "APPROVAL_MANAGE",
    "APPROVAL_DECIDE",
    "PERIOD_READ",
    "PERIOD_LOCK",
    "MIGRATION_MANAGE",
    "CHANNEL_READ",
    "CHANNEL_MAINTAIN",
    "CUSTOMER_READ",
    "CUSTOMER_MAINTAIN",
    "CUSTOMER_PHONE_READ",
]

FIELD_KEYS = [
    "STUDENT_PHONE",
    "STUDENT_CERT",
    "STUDENT_BIRTHDAY",
    "STUDENT_EMAIL",
    "CONTACT_PHONE",
]


def jdbc_to_dsn(url: str) -> dict:
    """把 jdbc:postgresql://host:port/db[?params] 转成 psycopg2 参数。"""
    if not url.startswith("jdbc:postgresql://"):
        raise SystemExit("TS_ADMIN_URL 必须是 jdbc:postgresql://host:port/db")
    rest = url[len("jdbc:postgresql://") :]
    host_port, _, db = rest.partition("/")
    db = db.split("?")[0]
    host, _, port = host_port.partition(":")
    if not host or not db:
        raise SystemExit("TS_ADMIN_URL 缺少主机或数据库名")
    return {"host": host, "port": int(port or 5432), "dbname": db}


def generate_password(length: int = 20) -> str:
    alphabet = string.ascii_letters + string.digits + "!@#%^*-_=+"
    return "".join(secrets.choice(alphabet) for _ in range(length))


def main() -> int:
    parser = argparse.ArgumentParser(description="创建机构、首所学校与首个管理员账号")
    parser.add_argument("--tenant", required=True, help="机构名称")
    parser.add_argument("--school", required=True, help="首所学校名称")
    parser.add_argument("--login", required=True, help="管理员登录名")
    parser.add_argument("--password-file", help="从文件读取初始密码（不落命令行）")
    parser.add_argument("--dry-run", action="store_true", help="只检查能否初始化，不写库")
    args = parser.parse_args()

    import os

    url = os.environ.get("TS_ADMIN_URL")
    user = os.environ.get("TS_ADMIN_USER")
    password = os.environ.get("TS_ADMIN_PASSWORD")
    if not url or not user or not password:
        raise SystemExit("需要环境变量 TS_ADMIN_URL / TS_ADMIN_USER / TS_ADMIN_PASSWORD")

    initial = (
        Path(args.password_file).read_text(encoding="utf-8").strip()
        if args.password_file
        else generate_password()
    )
    if len(initial) < 12:
        raise SystemExit("初始密码至少 12 位")
    if len(args.login.strip()) < 3:
        raise SystemExit("登录名至少 3 个字符")

    connection = psycopg2.connect(**jdbc_to_dsn(url), user=user, password=password)
    connection.autocommit = False
    try:
        with connection.cursor() as cursor:
            cursor.execute("select id from identity.tenant where name=%s", (args.tenant.strip(),))
            if cursor.fetchone():
                raise SystemExit("已存在同名机构，拒绝重复初始化")
            cursor.execute("select 1 from identity.account where login=%s", (args.login.strip(),))
            if cursor.fetchone():
                raise SystemExit("已存在同名登录，请换一个登录名")
            if args.dry_run:
                print("dry-run：可以初始化（未写库）")
                return 0

            import uuid

            tenant_id = str(uuid.uuid4())
            school_id = str(uuid.uuid4())
            account_id = str(uuid.uuid4())
            cursor.execute("insert into identity.tenant(id,name) values (%s,%s)", (tenant_id, args.tenant.strip()))
            # 业务表启用了 FORCE ROW LEVEL SECURITY：先设定租户上下文，后续写入才不会被策略拒绝。
            cursor.execute("select set_config('app.tenant_id', %s, true)", (tenant_id,))
            cursor.execute(
                "insert into organization.unit(id,tenant_id,school_id,kind,name)"
                " values (%s,%s,%s,'SCHOOL',%s)",
                (school_id, tenant_id, school_id, args.school.strip()),
            )
            hashed = bcrypt.hashpw(initial.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")
            cursor.execute(
                "insert into identity.account(id,tenant_id,login,password_hash,active)"
                " values (%s,%s,%s,%s,true)",
                (account_id, tenant_id, args.login.strip(), "{bcrypt}" + hashed),
            )
            for action in ACTIONS:
                cursor.execute(
                    "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
                    " values (%s,%s,%s,%s,'ALLOW')",
                    (tenant_id, account_id, school_id, action),
                )
            for field in FIELD_KEYS:
                cursor.execute(
                    "insert into identity.field_grant(tenant_id,account_id,school_id,field_key,effect)"
                    " values (%s,%s,%s,%s,'ALLOW')",
                    (tenant_id, account_id, school_id, field),
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

    print("初始化完成：")
    print("  机构：" + args.tenant.strip() + " (" + tenant_id + ")")
    print("  学校：" + args.school.strip() + " (" + school_id + ")")
    print("  登录：" + args.login.strip())
    print("  密码：" + (initial if not args.password_file else "（来自 --password-file）"))
    print("请立即登录并修改密码；本输出不会再次显示。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
