#!/usr/bin/env python3
"""`docs/ACCEPTANCE_TESTING.md` の `<domain>` 一覧が `features/` の実体と一致することを検証する(#1134)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

一覧は「次に `.feature` を足す人がどのディレクトリ名に従うか」を決める規約である。
実体と食い違うと、AT Issue ごとに別の名前が選ばれる(#935 `article-plan`、#937 `diagram`、
#938 `custom-tag` は一覧の `plans` / `diagrams` / `content` を参照せずに決まっていた)。

文書は web 側と拡張側の一覧を次の形の行で書く。この形を変えるときはこの検査も直す:

    - web(`apps/web/e2e/features/`): `a` / `b` / ...
    - 拡張(`apps/extension/e2e/features/`): `a` / `b` / ...

`stubs` / `harness` は製品ドメインではなく土台なので、一覧の対象外(別表で定義済み)。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
DOC = os.path.join(REPO_ROOT, "docs/ACCEPTANCE_TESTING.md")

FOUNDATION = {"stubs", "harness"}

LINE_RE = {
    "web": re.compile(r"^- web\(`apps/web/e2e/features/`\):(.*)$", re.M),
    "extension": re.compile(r"^- 拡張\(`apps/extension/e2e/features/`\):(.*)$", re.M),
}


def documented(side):
    with open(DOC, encoding="utf-8") as f:
        m = LINE_RE[side].search(f.read())
    if m is None:
        return None
    return set(re.findall(r"`([^`]+)`", m.group(1)))


def actual(side):
    root = os.path.join(REPO_ROOT, "apps", side, "e2e/features")
    return {d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d))}


class AcceptanceDomainDirs(unittest.TestCase):
    def check(self, side, exclude=frozenset()):
        docs = documented(side)
        self.assertIsNotNone(docs, f"{side} の <domain> 一覧の行が文書に無い")
        real = actual(side) - exclude
        self.assertEqual(
            docs, real,
            f"{side}: 文書のみ={sorted(docs - real)} 実体のみ={sorted(real - docs)}",
        )

    def test_web_list_matches_directories(self):
        self.check("web", FOUNDATION)

    def test_extension_list_matches_directories(self):
        self.check("extension")

    def test_naming_difference_between_sides_is_explained(self):
        """web と拡張で名前だけが違う領域は、揃えない理由を文書に書く。"""
        web, ext = documented("web"), documented("extension")
        self.assertIsNotNone(web)
        self.assertIsNotNone(ext)
        differing = {(n, n + "s") for n in web if n + "s" in ext}
        with open(DOC, encoding="utf-8") as f:
            text = f.read()
        self.assertIn("揃えない理由", text)
        for w, e in differing:
            self.assertRegex(text, re.escape(w) + r".*" + re.escape(e), f"{w}/{e}")


if __name__ == "__main__":
    unittest.main()
