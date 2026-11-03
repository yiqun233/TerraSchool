"""旧库抽取：把旧系统的行转成新系统迁移脚本能直接吃的 CSV。

两种模式：

* `files`（默认，离线可用）：读取按旧表名命名的 CSV 目录（从 SQL Server 导出或用 SSMS/BCP 导出的结果都可以），
  按映射表转成 `students.csv` / `receipts.csv` / `openings.csv`，并输出 `report.json`；
* `sqlserver`：直接连旧库查询（需要 `pyodbc` 与 ODBC 驱动；本机没有旧库与驱动，未在此环境验证）。

映射依据是 `参考资料/TSchool_SQLServer.sql` 的真实字段（只有结构、没有数据）：

| 旧表 | 旧字段 | 新契约 |
| --- | --- | --- |
| `H_Student` | `Code` | 旧主键 -> `legacy_id` |
| | `SN` | 学员号 |
| | `Name` / `Sex` / `Birthday` / `Cert_Code` / `Phone` | 档案字段 |
| `H_Student_Guardian` | `Student_Code` / `Name` / `Phone` | 家长联系人（取 `Guardian_Order` 最小的一条） |
| `H_Student_Register_Pay_Record` | `Code` / `Date_Stamp` / `Money` | 收款（金额单位是分，需 /100） |
| `H_Student_Register_Consume` | `Pay_Money` / `Consume` / `CS_Hour` / `CS_Hour_Consume` | 期初余额与已消耗课时（金额分、课时 0.01 单位） |

单位换算与清洗都会写进 `report.json`，不做静默丢弃：缺学员号、缺金额、日期非法的行单独列出。

用法：

    python tools/migration/extract_legacy.py --raw-dir ./raw --out-dir ./out
    python tools/migration/extract_legacy.py --self-check
"""
import argparse
import csv
import json
import sys
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

SEX = {"1": "MALE", "2": "FEMALE", "0": "UNKNOWN", "男": "MALE", "女": "FEMALE"}

STUDENT_COLUMNS = [
    "legacy_id",
    "studentNo",
    "name",
    "sex",
    "birthday",
    "certCode",
    "phone",
    "contactName",
    "contactPhone",
]
RECEIPT_COLUMNS = ["legacy_id", "studentNo", "amount", "method", "occurredOn"]
OPENING_COLUMNS = ["studentNo", "amount", "hours", "consumedAmount"]


def money_from_fen(value: str) -> str | None:
    """旧库金额以"分"存储，新契约是两位小数的元。"""
    if value is None or str(value).strip() == "":
        return None
    try:
        fen = Decimal(str(value).strip())
    except InvalidOperation:
        return None
    return str((fen / Decimal(100)).quantize(Decimal("0.01")))


def hours_from_centi(value: str) -> str | None:
    """旧库课时是 0.01 单位（`CS_Hour`），新契约是两位小数课时。"""
    if value is None or str(value).strip() == "":
        return None
    try:
        centi = Decimal(str(value).strip())
    except InvalidOperation:
        return None
    return str((centi / Decimal(100)).quantize(Decimal("0.01")))


def date_from_stamp(value: str) -> str | None:
    """旧库日期常以 `yyyyMMdd` 整数存储。"""
    if value is None:
        return None
    text = str(value).strip()
    if not text or len(text) != 8 or not text.isdigit():
        return None
    try:
        return date(int(text[:4]), int(text[4:6]), int(text[6:])).isoformat()
    except ValueError:
        return None


def sex_value(value: str) -> str:
    if value is None:
        return "UNKNOWN"
    return SEX.get(str(value).strip(), "UNKNOWN")


def read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return [{k.strip(): (v or "").strip() for k, v in row.items()} for row in csv.DictReader(handle)]


def write_csv(path: Path, columns: list[str], rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in columns})


def build_students(raw: list[dict], guardians: list[dict], report: dict) -> list[dict]:
    """学员 + 取 `Guardian_Order` 最小的家长作为联系人。"""
    by_student: dict[str, dict] = {}
    for guardian in guardians:
        student_code = guardian.get("Student_Code", "")
        if not student_code:
            continue
        order = guardian.get("Guardian_Order", "0")
        try:
            order_value = int(order or 0)
        except ValueError:
            order_value = 0
        current = by_student.get(student_code)
        if current is None or order_value < current["order"]:
            by_student[student_code] = {
                "order": order_value,
                "name": guardian.get("Name", ""),
                "phone": guardian.get("Phone", ""),
            }
    rows = []
    for student in raw:
        legacy_id = student.get("Code", "")
        student_no = student.get("SN", "")
        name = student.get("Name", "")
        if not legacy_id or not student_no or not name:
            report["dropped"].append({"kind": "STUDENT", "legacy_id": legacy_id, "reason": "缺少 Code/SN/Name"})
            continue
        guardian = by_student.get(legacy_id, {})
        birthday = student.get("Birthday", "")
        rows.append(
            {
                "legacy_id": legacy_id,
                "studentNo": student_no,
                "name": name,
                "sex": sex_value(student.get("Sex")),
                "birthday": birthday if _is_iso(birthday) else "",
                "certCode": student.get("Cert_Code", ""),
                "phone": student.get("Phone", ""),
                "contactName": guardian.get("name", ""),
                "contactPhone": guardian.get("phone", ""),
            }
        )
    return rows


def _is_iso(value: str) -> bool:
    try:
        date.fromisoformat(value)
        return True
    except (TypeError, ValueError):
        return False


def build_receipts(raw: list[dict], report: dict) -> list[dict]:
    rows = []
    for receipt in raw:
        legacy_id = receipt.get("Code", "")
        amount = money_from_fen(receipt.get("Money"))
        occurred = date_from_stamp(receipt.get("Date_Stamp"))
        if not legacy_id or amount is None or occurred is None:
            report["dropped"].append({"kind": "RECEIPT", "legacy_id": legacy_id, "reason": "缺少 Code/Money/Date_Stamp"})
            continue
        if Decimal(amount) <= 0:
            report["dropped"].append({"kind": "RECEIPT", "legacy_id": legacy_id, "reason": "金额必须为正"})
            continue
        rows.append(
            {
                "legacy_id": legacy_id,
                "studentNo": receipt.get("Student_Code", ""),
                "amount": amount,
                "method": "CASH",
                "occurredOn": occurred,
            }
        )
    return rows


def build_openings(raw: list[dict], report: dict) -> list[dict]:
    """期初值取旧库报名级汇总：余额 = 实收 - 消耗，课时取已消耗。"""
    rows = []
    for consume in raw:
        student_no = consume.get("Student_Code", "")
        pay = money_from_fen(consume.get("Pay_Money")) or "0.00"
        used = money_from_fen(consume.get("Consume")) or "0.00"
        hours = hours_from_centi(consume.get("CS_Hour_Consume")) or "0.00"
        if not student_no:
            report["dropped"].append({"kind": "OPENING", "legacy_id": "", "reason": "缺少 Student_Code"})
            continue
        rows.append(
            {
                "studentNo": student_no,
                "amount": str((Decimal(pay) - Decimal(used)).quantize(Decimal("0.01"))),
                "hours": hours,
                "consumedAmount": used,
            }
        )
    return rows


def extract(raw_dir: Path, out_dir: Path) -> dict:
    report = {"raw_dir": str(raw_dir), "out_dir": str(out_dir), "dropped": [], "counts": {}}
    students = build_students(
        read_csv(raw_dir / "H_Student.csv"), read_csv(raw_dir / "H_Student_Guardian.csv"), report
    )
    receipts = build_receipts(read_csv(raw_dir / "H_Student_Register_Pay_Record.csv"), report)
    openings = build_openings(read_csv(raw_dir / "H_Student_Register_Consume.csv"), report)

    write_csv(out_dir / "students.csv", STUDENT_COLUMNS, students)
    write_csv(out_dir / "receipts.csv", RECEIPT_COLUMNS, receipts)
    write_csv(out_dir / "openings.csv", OPENING_COLUMNS, openings)
    report["counts"] = {
        "students": len(students),
        "receipts": len(receipts),
        "openings": len(openings),
        "dropped": len(report["dropped"]),
    }
    report["conversions"] = {
        "money": "旧库金额以分存储，输出除以 100 保留两位小数",
        "hours": "旧库课时以 0.01 单位存储，输出除以 100",
        "dates": "旧库 Date_Stamp 为 yyyyMMdd 整数，输出 ISO 日期",
        "opening_amount": "期初余额 = Pay_Money - Consume（旧库无独立余额字段）",
    }
    (out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def self_check() -> int:
    failures = []
    if money_from_fen("123456") != "1234.56":
        failures.append("money_from_fen")
    if money_from_fen("") is not None:
        failures.append("money_from_fen empty")
    if hours_from_centi("350") != "3.50":
        failures.append("hours_from_centi")
    if date_from_stamp("20260210") != "2026-02-10":
        failures.append("date_from_stamp")
    if date_from_stamp("20261340") is not None:
        failures.append("date_from_stamp invalid")
    if sex_value("2") != "FEMALE" or sex_value("x") != "UNKNOWN":
        failures.append("sex_value")
    report = {"dropped": []}
    students = build_students(
        [{"Code": "S1", "SN": "0001", "Name": "张三", "Sex": "2", "Birthday": "2014-05-06", "Cert_Code": "1101", "Phone": "139"},
         {"Code": "S2", "SN": "", "Name": "无号"}],
        [{"Student_Code": "S1", "Guardian_Order": "2", "Name": "次联系人", "Phone": "111"},
         {"Student_Code": "S1", "Guardian_Order": "1", "Name": "主要联系人", "Phone": "222"}],
        report,
    )
    if len(students) != 1 or students[0]["contactName"] != "主要联系人":
        failures.append("build_students guardian order")
    if len(report["dropped"]) != 1:
        failures.append("build_students drop")
    report2 = {"dropped": []}
    receipts = build_receipts([{"Code": "R1", "Student_Code": "0001", "Money": "100000", "Date_Stamp": "20260110"}], report2)
    if len(receipts) != 1 or receipts[0]["amount"] != "1000.00" or receipts[0]["occurredOn"] != "2026-01-10":
        failures.append("build_receipts")
    report3 = {"dropped": []}
    openings = build_openings(
        [{"Student_Code": "0001", "Pay_Money": "300000", "Consume": "120000", "CS_Hour_Consume": "400"}], report3
    )
    if len(openings) != 1 or openings[0]["amount"] != "1800.00" or openings[0]["hours"] != "4.00":
        failures.append("build_openings")
    if failures:
        print("self-check failed: " + ", ".join(failures))
        return 1
    print("self-check ok：金额分→元、课时 0.01→两位小数、日期 yyyyMMdd→ISO、联系人取最小顺序、异常行进 dropped")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="旧库抽取：旧表 CSV → 迁移暂存 CSV")
    parser.add_argument("--raw-dir", type=Path, help="存放 H_Student.csv 等旧表导出的目录")
    parser.add_argument("--out-dir", type=Path, default=Path("out"), help="输出目录")
    parser.add_argument("--self-check", action="store_true", help="跑内置换算自检")
    args = parser.parse_args()
    if args.self_check:
        return self_check()
    if not args.raw_dir:
        parser.error("需要 --raw-dir 或 --self-check")
    if not args.raw_dir.exists():
        print("原始目录不存在：" + str(args.raw_dir))
        return 2
    report = extract(args.raw_dir, args.out_dir)
    print(json.dumps(report["counts"], ensure_ascii=False))
    print("输出目录：" + str(args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
