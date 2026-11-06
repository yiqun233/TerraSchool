"""将仓库 Markdown 打包为总览内的只读文档；不加载远程脚本或执行原文 HTML。"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
from urllib.parse import quote, unquote, urlencode, urlsplit

from markdown_it import MarkdownIt


def document_hash(path: str, anchor: str = "") -> str:
    values = {"doc": path}
    if anchor:
        values["anchor"] = anchor
    return "#" + urlencode(values, quote_via=quote)


def bundle_documents(root: Path, output: Path):
    # Path 的原生排序在 Windows 忽略大小写、Linux 区分大小写；显式按 POSIX 字符串
    # 排序，使文档顺序、指纹与生成物可在本机和 Linux CI 之间逐字校验。
    paths = sorted(
        {root / "README.md", root / "AGENT.md", *(root / "doc").rglob("*.md")},
        key=lambda path: path.relative_to(root).as_posix(),
    )
    keys = {path.resolve(): path.relative_to(root).as_posix() for path in paths}
    renderer = MarkdownIt("commonmark", {"html": False}).enable(["table", "strikethrough"])
    documents = {}
    digest = hashlib.sha256()

    for path in paths:
        source = path.read_text(encoding="utf-8")
        key = keys[path.resolve()]
        digest.update(key.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
        env = {}
        tokens = renderer.parse(source, env)
        headings = []
        used_slugs = set()

        def rewrite_link(href):
            parsed = urlsplit(href)
            if parsed.scheme or parsed.netloc:
                return href
            destination = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path.resolve()
            if not destination.is_relative_to(root):
                raise ValueError(f"文档链接越过仓库边界：{key} → {href}")
            if destination in keys:
                return document_hash(keys[destination], unquote(parsed.fragment))
            # 非 Markdown 的仓库文件仍按真实路径访问，如既有 HTML 总览。
            if not destination.exists() and destination != output:
                raise ValueError(f"文档链接不存在：{key} → {href}")
            relative = Path(os.path.relpath(destination, output.parent)).as_posix()
            return quote(relative, safe="/.-_") + ("#" + parsed.fragment if parsed.fragment else "")

        def rewrite_inline(items):
            for token in items:
                if token.type == "link_open":
                    href = token.attrGet("href") or ""
                    token.attrSet("href", rewrite_link(href))
                    if urlsplit(href).scheme in {"http", "https"}:
                        token.attrSet("target", "_blank")
                        token.attrSet("rel", "noopener noreferrer")
                if token.children:
                    rewrite_inline(token.children)

        for index, token in enumerate(tokens):
            if token.type == "heading_open":
                inline = tokens[index + 1]
                title = "".join(child.content for child in (inline.children or []) if child.type in {"text", "code_inline"})
                base_slug = re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-") or "section"
                slug, repetition = base_slug, 0
                while slug in used_slugs:
                    repetition += 1
                    slug = f"{base_slug}-{repetition}"
                used_slugs.add(slug)
                token.attrSet("id", slug)
                headings.append(dict(id=slug, title=title, level=int(token.tag[1:])))
            if token.children:
                rewrite_inline(token.children)

        rendered = renderer.renderer.render(tokens, renderer.options, env)
        rendered = rendered.replace("<table>", '<div class="document-table"><table>').replace("</table>", "</table></div>")
        documents[key] = dict(
            title=next((heading["title"] for heading in headings if heading["level"] == 1), path.stem),
            html=rendered,
            headings=headings,
            source=quote(Path(os.path.relpath(path, output.parent)).as_posix(), safe="/.-_"),
        )
    return documents, digest.hexdigest()
