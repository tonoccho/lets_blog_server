#!/usr/bin/env python3
"""DOCUMENTATION.md の「リポジトリ構成」図がリポジトリの実態と一致することを検証する(#974)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

この図は「リポジトリのどこに何があるか」を読者に伝えるものである。図は手で書かれ、
ディレクトリ構成は別に変更される。**両者が同期している保証は無い**。実際 #963 で
`apps/extension/` `services/` `packages/` が新設され、`infra/` にも
`keycloak/` `mysql/` `wordpress/` `e2e-stubs/` などが増えた後も、この図は
`mcp-server/` `penpot-plugin/` `web/` `nginx/` しか載っていない #963 以前の
状態のまま陳腐化していた(#974)。

`scripts/test_docs_reverse_proxy_diagram.py` と同じ考え方で、図に実在ディレクトリ名の
トークンが載っているかを検査する。図の書式(ツリー描画の罫線や字下げ)までは検査せず、
主要な既存ディレクトリが図から欠落していないことだけを見る。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
DOC = os.path.join(REPO_ROOT, "docs/DOCUMENTATION.md")

SECTION_HEADING = "#### リポジトリ構成"

# 図に必ず登場していなければならないディレクトリ名トークン。
# apps/extension・services・packages は #974 時点で図から完全に欠落しており、
# infra 配下も nginx 以外(keycloak/mysql/wordpress/e2e-stubs)が欠落していた。
REQUIRED_TOKENS = (
    "extension",
    "services",
    "packages",
    "keycloak",
    "mysql",
    "wordpress",
    "e2e-stubs",
)


def repository_structure_section():
    """「リポジトリ構成」見出し配下の、最初のコードブロックの中身を返す。"""
    with open(DOC, encoding="utf-8") as f:
        lines = f.read().splitlines()

    start = None
    for i, line in enumerate(lines):
        if line.strip() == SECTION_HEADING:
            start = i
            break
    if start is None:
        raise AssertionError("見出し %r が見つからない" % SECTION_HEADING)

    inside = False
    collected = []
    for line in lines[start + 1 :]:
        if line.strip().startswith("```"):
            if inside:
                break
            inside = True
            continue
        if inside:
            collected.append(line)
    return "\n".join(collected)


class DiagramMatchesRepository(unittest.TestCase):
    def test_every_required_directory_appears_in_the_diagram(self):
        section = repository_structure_section()
        # ディレクトリ名トークンとして現れていることを見る(部分文字列一致で十分)。
        offenders = [token for token in REQUIRED_TOKENS if not re.search(re.escape(token), section)]
        self.assertEqual(
            [],
            offenders,
            "「リポジトリ構成」の図に無い実在ディレクトリ:\n  " + "\n  ".join(offenders),
        )


class ParserSanity(unittest.TestCase):
    """検査そのものが空振りしていないこと。"""

    def test_the_section_is_found_and_non_empty(self):
        section = repository_structure_section()
        self.assertTrue(section.strip(), "「リポジトリ構成」のコードブロックが空")
        # 既存の(陳腐化前から変わらない)エントリが読めていることの健全性チェック。
        self.assertIn("web", section)


if __name__ == "__main__":
    unittest.main()
