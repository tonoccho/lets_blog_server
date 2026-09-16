#!/usr/bin/env python3
"""DOCUMENTATION.md の「本番環境への デプロイ」節が実在するファイル・コマンドだけを
案内していることを検証する(#1067)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

この節は「本番デプロイでどのコマンドを打てばよいか」を読者に伝えるものである。節は手で
書かれ、compose ファイルや npm script は別に変更される。**両者が同期している保証は無い**。
実際この節は `docker-compose.prod.yml` と `.env.production` という、リポジトリに存在した
ことのないファイルを案内し続けており、`apps/web/package.json` に無い `npm run db:migrate`
を案内していた(#1067)。

`scripts/test_documentation_tree_structure.py` と同じ考え方で、節の中に現れる
`docker-compose*.yml` ファイル名がリポジトリに実在すること、`npm run <script>` が
`apps/web/package.json` の scripts に実在することを検査する。
"""

import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
DOC = os.path.join(REPO_ROOT, "docs/DOCUMENTATION.md")
WEB_PACKAGE_JSON = os.path.join(REPO_ROOT, "apps/web/package.json")

SECTION_HEADING = "#### 本番環境への デプロイ"

COMPOSE_FILE_RE = re.compile(r"docker-compose[\w.-]*\.yml")
NPM_RUN_RE = re.compile(r"npm run ([\w:-]+)")


def production_deploy_section():
    """「本番環境への デプロイ」見出し配下、次の見出しまでの本文を返す。"""
    with open(DOC, encoding="utf-8") as f:
        lines = f.read().splitlines()

    start = None
    for i, line in enumerate(lines):
        if line.strip() == SECTION_HEADING:
            start = i
            break
    if start is None:
        raise AssertionError("見出し %r が見つからない" % SECTION_HEADING)

    collected = []
    inside_fence = False
    for line in lines[start + 1 :]:
        if line.strip().startswith("```"):
            inside_fence = not inside_fence
            collected.append(line)
            continue
        if not inside_fence and line.strip().startswith("#"):
            break
        collected.append(line)
    return "\n".join(collected)


def web_npm_scripts():
    with open(WEB_PACKAGE_JSON, encoding="utf-8") as f:
        data = json.load(f)
    return set(data.get("scripts", {}).keys())


class ProductionDeploySectionMatchesRepository(unittest.TestCase):
    def test_referenced_compose_files_exist(self):
        section = production_deploy_section()
        referenced = set(COMPOSE_FILE_RE.findall(section))
        missing = [
            name for name in referenced if not os.path.exists(os.path.join(REPO_ROOT, name))
        ]
        self.assertEqual(
            [],
            missing,
            "「本番環境への デプロイ」節が案内する、実在しない compose ファイル:\n  "
            + "\n  ".join(missing),
        )

    def test_referenced_npm_scripts_exist(self):
        section = production_deploy_section()
        referenced = set(NPM_RUN_RE.findall(section))
        scripts = web_npm_scripts()
        missing = [name for name in referenced if name not in scripts]
        self.assertEqual(
            [],
            missing,
            "「本番環境への デプロイ」節が案内する、apps/web/package.json に無い npm script:\n  "
            + "\n  ".join(missing),
        )


class ParserSanity(unittest.TestCase):
    """検査そのものが空振りしていないこと。"""

    def test_the_section_is_found_and_non_empty(self):
        section = production_deploy_section()
        self.assertTrue(section.strip(), "「本番環境への デプロイ」節が空")


if __name__ == "__main__":
    unittest.main()
