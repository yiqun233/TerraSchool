"""从权威 Markdown 生成离线开发总览；--check 用于检查生成物是否过期。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re

try:
    from documents import bundle_documents
except ModuleNotFoundError as error:
    raise SystemExit("缺少文档渲染依赖，请先运行：python -m pip install -r tools/progress/requirements.txt") from error

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "doc"
PLAN = DOC / "04-执行计划"
OUTPUT = PLAN / "开发总览.html"
SOURCES = {
    "scope": DOC / "01-业务需求/业务功能清单.md",
    "modules": PLAN / "系统功能拆分与完成情况.md",
    "details": PLAN / "功能细项追踪.md",
    "route": PLAN / "后续开发执行路线.md",
    "questions": DOC / "05-决策与问题/待确认问题登记.md",
    "baseline": DOC / "05-决策与问题/待确认事项与技术选型议题.md",
    "guide": PLAN / "开发总览使用与维护.md",
    "archive": DOC / "99-归档/归档索引.md",
}
STATES = {"未开始", "进行中", "局部实现", "已实现", "暂缓"}
VERIFY = {"未验证", "局部有证据", "已验证"}
ACCEPTANCE = {"未提请", "待验收", "已验收"}
QUESTION_STATES = {"待确认", "部分已确认", "已确认"}
# 分类与优先度口径见 doc/05-决策与问题/待确认问题登记.md；枚举不合法即拒绝生成。
# 顺序即总览矩阵与筛选下拉的展示顺序；新增领域同时更新问题登记的领域表。
CATEGORIES = ["金额与结算", "审批与期间", "教务与排课", "学员与招生", "旧库迁移", "报表与导出", "平台与租户", "交付与运维"]
QUESTION_TYPES = {"规则口径", "证据缺失", "实现缺陷"}
IMPORTANCE = {"高", "中", "低"}
URGENCY = {"立即", "本阶段", "可后置"}


def priority(importance: str, urgency: str) -> str:
    """重要度与紧急度推导处理档位；规则只在这里定义一次。"""
    if importance == "高" and urgency == "立即":
        return "S"
    if importance == "高" or urgency == "立即":
        return "A"
    if importance == "低" and urgency == "可后置":
        return "C"
    return "B"


def cells(line):
    return [v.strip() for v in line.strip().strip("|").split("|")]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def build_data():
    texts = {key: path.read_text(encoding="utf-8") for key, path in SOURCES.items()}
    scopes = {}
    section = ""
    for line in texts["scope"].splitlines():
        if line.startswith("### "):
            section = line[4:]
        if re.match(r"\| REQ-[A-P]\d+ \|", line):
            row = cells(line)
            require(row[0] not in scopes, f"重复范围编号：{row[0]}")
            scopes[row[0]] = dict(id=row[0], name=row[1], legacy=row[2], phase=row[3], note=row[4], section=section)

    modules = []
    for line in texts["modules"].splitlines():
        if re.match(r"\| F\d{2} ", line):
            row = cells(line)
            code, title = row[0].split(" ", 1)
            if len(row) == 5:
                modules.append(dict(id=code, title=title, implemented=row[1], evidence=row[2], gap=row[3], tasks=row[4]))
            else:
                modules.append(dict(id=code, title=title, implemented=row[1], evidence="范围预留", gap=row[2], tasks="按依赖展开"))
    module_ids = {m["id"] for m in modules}
    require(scopes and modules, "未读取到功能范围或模块表")
    require(len(modules) == len(module_ids), "模块编号重复")
    details = []
    for line in texts["details"].splitlines():
        if re.match(r"\| (REQ-|SUP-|AI-)", line):
            row = cells(line)
            require(len(row) == 10, f"细项列数错误：{row[0]}")
            code, parent, state, verification, acceptance, current, gap, questions, materials, evidence = row
            require(parent in module_ids, f"未知父模块：{code}/{parent}")
            require(state in STATES and verification in VERIFY and acceptance in ACCEPTANCE, f"状态不合法：{code}")
            require(verification != "已验证" or evidence != "—", f"已验证缺证据：{code}")
            require(acceptance != "已验收" or "验收" in evidence, f"已验收缺结论：{code}")
            if code.startswith("REQ-"):
                base_id = code.split(".")[0]
                require(base_id in scopes and re.fullmatch(r"REQ-[A-P]\d+(?:\.\d+)*", code), f"范围中不存在：{code}")
                scope = dict(scopes[base_id], id=code)
                if "." in code:
                    scope["name"] += " / " + current.split("：")[0]
            else:
                scope = dict(id=code, name=current.split("：")[0], phase="支撑" if code.startswith("SUP-") else "未来", section="工程与交付" if code.startswith("SUP-") else "未来 AI", legacy="新工程", note="")
            details.append(dict(**scope, parent=parent, state=state, verification=verification, acceptance=acceptance, current=current, gap=gap, questions=questions, materials=materials, evidence=evidence))
    ids = [d["id"] for d in details]
    require(len(ids) == len(set(ids)), "细项编号重复")
    require(set(scopes) == {i for i in ids if i.startswith("REQ-") and "." not in i}, "范围与细项未一一映射")
    by_id = {detail["id"]: detail for detail in details}
    for detail in details:
        if "." in detail["id"]:
            parent_id = detail["id"].rsplit(".", 1)[0]
            require(parent_id in by_id and by_id[parent_id]["parent"] == detail["parent"], f"子细项缺父项或跨模块：{detail['id']}")
    require(module_ids == {d["parent"] for d in details}, "有模块尚未映射细项")
    # 问题正文和状态分别读取；不能把“已实现”或句中的“确认”自动当作问题关闭。
    questions, decisions, materials, tasks, gates = {}, {}, [], [], []
    in_state_index = False
    for line in texts["questions"].splitlines():
        if line.startswith("## 问题状态索引"):
            in_state_index = True
        row = cells(line)
        if not in_state_index and row and re.fullmatch(r"Q\d+|C[1-6]", row[0]) and len(row) in (4, 5):
            require(row[0] not in questions, f"重复问题正文：{row[0]}")
            questions[row[0]] = dict(id=row[0], title=row[1], current="；".join(row[2:-1]), impact=row[-1])
        if in_state_index and row and re.fullmatch(r"Q\d+|C[1-6]", row[0]) and len(row) == 9:
            require(row[0] not in decisions, f"重复问题状态：{row[0]}")
            require(row[1] in CATEGORIES, f"问题领域不合法：{row[0]}/{row[1]}")
            require(row[2] in QUESTION_TYPES, f"问题类型不合法：{row[0]}/{row[2]}")
            require(row[3] in IMPORTANCE, f"问题重要度不合法：{row[0]}/{row[3]}")
            require(row[4] in URGENCY, f"问题紧急度不合法：{row[0]}/{row[4]}")
            require(row[5] in QUESTION_STATES, f"问题状态不合法：{row[0]}")
            decisions[row[0]] = dict(category=row[1], kind=row[2], importance=row[3], urgency=row[4],
                                     priority=priority(row[3], row[4]),
                                     state=row[5], owner=row[6], next=row[7], conclusion=row[8])
    require(set(questions) == set(decisions), "问题状态索引与 Q/C 正文不一致")
    known = set(questions)
    known.update(re.findall(r"\| (BQ-\d+|DATA-\d+) \|", texts["baseline"]))
    for detail in details:
        for ref in re.findall(r"(?:Q\d+|C[1-6]|BQ-\d+|DATA-\d+)", detail["questions"] + " " + detail["materials"]):
            require(ref in known, f"未知问题/材料：{detail['id']}/{ref}")
    for line in texts["baseline"].splitlines():
        row = cells(line)
        if row and re.fullmatch(r"DATA-\d+", row[0]):
            require(len(row) == 6 and row[3] in {"待定位", "部分具备", "已齐备"}, f"资料状态不合法：{row[0]}")
            materials.append(dict(id=row[0], title=row[1], purpose=row[2], state=row[3], gap=row[4], owner=row[5]))
    for line in texts["route"].splitlines():
        row = cells(line)
        if row and re.match(r"REV-\d+ /", row[0]):
            tasks.append(dict(id=row[0].split(" / ")[0], gate=row[0].split(" / ")[1], scope=row[1], state=row[2], exit=row[3]))
        elif row and re.match(r"G\d+ ", row[0]) and len(row) == 3:
            gates.append(dict(title=row[0], state=row[1], exit=row[2]))
    # 文件链接必须真实存在且留在仓库内；忽略代码片段，避免把示例当作真实链接。
    for key, value in texts.items():
        clean = re.sub(r"```.*?```", "", value, flags=re.S)
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", clean):
            if target.startswith(("http:", "https:", "#", "mailto:")):
                continue
            path = (SOURCES[key].parent / target.split("#")[0]).resolve()
            require(path.is_relative_to(ROOT) and (path.exists() or path == OUTPUT), f"失效链接：{SOURCES[key].name} → {target}")
    fingerprint = hashlib.sha256("".join(texts.values()).encode()).hexdigest()[:16]
    task_ids = [task["id"] for task in tasks]
    require(tasks and gates and len(task_ids) == len(set(task_ids)), "路线任务为空或编号重复")
    require(len({g["title"].split()[0] for g in gates}) == len(gates), "路线关口编号重复")
    for module in modules:
        for task_id in re.findall(r"REV-\d+", module["tasks"]):
            require(task_id in task_ids, f"模块引用不存在的任务：{module['id']}/{task_id}")
    updated = max(re.findall(r"\| 更新日期 \| (\d{4}-\d{2}-\d{2}) \|", "\n".join(texts.values())))
    return dict(updated=updated, fingerprint=fingerprint, modules=modules, details=details,
                questions=[dict(**q, **decisions[code]) for code, q in questions.items()],
                categories=CATEGORIES, materials=materials, tasks=tasks, gates=gates,
                sources={key: Path(os.path.relpath(path, PLAN)).as_posix() for key, path in SOURCES.items()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = build_data()
    documents, document_fingerprint = bundle_documents(ROOT, OUTPUT)
    data["documents"] = documents
    data["fingerprint"] = hashlib.sha256((data["fingerprint"] + document_fingerprint).encode()).hexdigest()[:16]
    template = Path(__file__).with_name("template.html").read_text(encoding="utf-8")
    payload = json.dumps(data, ensure_ascii=False).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    output = template.replace("__PROGRESS_DATA__", payload)
    if args.check:
        require(OUTPUT.exists() and OUTPUT.read_text(encoding="utf-8") == output, "开发总览已过期，请运行 python -X utf8 tools/progress/build.py")
    else:
        OUTPUT.write_text(output, encoding="utf-8", newline="\n")
    print(f"{'检查通过' if args.check else '已生成'}：{len(data['modules'])} 模块 / {len(data['details'])} 细项 / {len(data['questions'])} 问题 / {len(data['tasks'])} 任务 / {len(documents)} 份可阅读文档；指纹 {data['fingerprint']}")


if __name__ == "__main__":
    main()
