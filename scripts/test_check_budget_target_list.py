#!/usr/bin/env python3
"""`scripts/check-budget-target-list.py`(3秒予算の対象一覧とコードの照合、#1544)の単体テスト。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

合成した小さなリポジトリ(一時ディレクトリ)で、「コードにあるのに一覧に無い」「一覧にあるのに
コードに無い」の両方向、見出しの件数のずれ、一覧の節が読めないときの失敗を確かめる。
最後に、実リポジトリの `docs/ACCEPTANCE_CRITERIA.md` §10 とコードが一致していること自体も確かめる
(これが落ちるのは、ページ・Server Action を足したのに一覧へ行を足し忘れたとき)。
"""

import contextlib
import importlib.util
import io
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "check-budget-target-list.py")

_spec = importlib.util.spec_from_file_location("check_budget_target_list", SCRIPT)
cbl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cbl)

DOC_TEMPLATE = """## 10. 応答時間予算

### 10.4 画面の初回表示(全{pages}ページ)

| 操作 | 計測点 | 閾値 | 閾値の根拠 | 分類 |
| --- | --- | --- | --- | --- |
{page_rows}

### 10.5 Server Action(全{actions}件)

| 操作(Server Action) | 計測点 | 閾値 | 閾値の根拠 | 分類 |
| --- | --- | --- | --- | --- |
{action_rows}

### 10.6 一覧に載せない操作(予算対象外)

`/api/preview/skeleton` の説明。`notAnAction` は載せない。
"""


def page_row(route):
    return f"| `{route}` の初回表示 | ① | 3,000ms | 根拠 | 予算対象 |"


def action_row(file, *names):
    cell = f"`app/{file}`" + "".join(f"<br>`{n}`" for n in names)
    return f"| {cell} | ② | 3,000ms | 根拠 | 予算対象 |"


def make_doc(pages, actions, pages_heading=None, actions_heading=None):
    rows_p = "\n".join(page_row(p) for p in pages)
    rows_a = "\n".join(action_row("x/actions.ts", a) for a in actions)
    return DOC_TEMPLATE.format(
        pages=len(pages) if pages_heading is None else pages_heading,
        actions=len(actions) if actions_heading is None else actions_heading,
        page_rows=rows_p,
        action_rows=rows_a,
    )


class Repo:
    """一時ディレクトリに合成した最小のリポジトリ。"""

    def __init__(self):
        self.root = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.root, "docs"))

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def page(self, rel_dir):
        self.write(f"apps/web/src/app/{rel_dir}/page.tsx".replace("//", "/"), "export default function P() {}\n")

    def actions(self, rel, *names):
        body = "".join(f"export async function {n}() {{}}\n" for n in names)
        self.write(f"apps/web/src/{rel}", body)

    def doc(self, text):
        self.write("docs/ACCEPTANCE_CRITERIA.md", text)


class CodeExtraction(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.close)

    def test_page_routes_strip_route_groups_and_root(self):
        self.repo.page("")
        self.repo.page("projects")
        self.repo.page("projects/[id]/(detail)")
        self.repo.page("projects/[id]/article-review")
        self.assertEqual(
            cbl.code_pages(self.repo.root),
            {"/", "/projects", "/projects/[id]", "/projects/[id]/article-review"},
        )

    def test_actions_are_found_in_any_file_under_src_and_tests_are_ignored(self):
        self.repo.actions("app/a/actions.ts", "fooAction", "barAction")
        self.repo.actions("app/infoRailActions.ts", "bazAction")
        self.repo.actions("app/a/__tests__/actions.test.ts", "ignoredAction")
        self.repo.actions("app/a/helper.ts", "notActionHelper")
        self.repo.actions("app/a/actions.spec.tsx", "ignoredSpecAction")
        self.repo.actions("app/a/actions.test.ts", "ignoredTestAction")
        self.repo.write("apps/web/src/app/a/notes.md", "export async function mdAction() {}\n")
        self.assertEqual(cbl.code_actions(self.repo.root), {"fooAction", "barAction", "bazAction"})

    def test_non_page_files_are_not_routes(self):
        self.repo.write("apps/web/src/app/projects/layout.tsx", "export default function L() {}\n")
        self.assertEqual(cbl.code_pages(self.repo.root), set())

    def test_missing_src_directory_yields_nothing(self):
        self.assertEqual(cbl.code_pages(self.repo.root), set())
        self.assertEqual(cbl.code_actions(self.repo.root), set())


class DocExtraction(unittest.TestCase):
    def test_pages_and_actions_come_only_from_their_own_sections(self):
        doc = make_doc(["/", "/a/[id]"], ["fooAction", "barAction"])
        self.assertEqual(cbl.listed_pages(doc), {"/", "/a/[id]"})
        self.assertEqual(cbl.listed_actions(doc), {"fooAction", "barAction"})

    def test_multiple_actions_in_one_cell_are_all_read(self):
        doc = DOC_TEMPLATE.format(
            pages=0, actions=2, page_rows="", action_rows=action_row("x/actions.ts", "aAction", "bAction")
        )
        self.assertEqual(cbl.listed_actions(doc), {"aAction", "bAction"})

    def test_headings_counts(self):
        doc = make_doc(["/"], ["fooAction", "barAction"])
        self.assertEqual(cbl.heading_counts(doc), (1, 2))

    def test_missing_sections_are_reported_as_none(self):
        self.assertIsNone(cbl.listed_pages("## 10. なし\n"))
        self.assertIsNone(cbl.listed_actions("## 10. なし\n"))
        self.assertEqual(cbl.heading_counts("## 10. なし\n"), (None, None))


class Verdict(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.close)
        self.repo.page("")
        self.repo.page("projects")
        self.repo.actions("app/a/actions.ts", "fooAction", "barAction")

    def run_check(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cbl.main(["--root", self.repo.root])
        return code, out.getvalue()

    def test_consistent_repo_passes(self):
        self.repo.doc(make_doc(["/", "/projects"], ["fooAction", "barAction"]))
        code, _ = self.run_check()
        self.assertEqual(code, 0)

    def test_page_in_code_but_not_in_list_fails(self):
        self.repo.doc(make_doc(["/"], ["fooAction", "barAction"]))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("/projects", out)

    def test_action_in_code_but_not_in_list_fails(self):
        self.repo.doc(make_doc(["/", "/projects"], ["fooAction"]))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("barAction", out)

    def test_page_in_list_but_not_in_code_fails(self):
        self.repo.doc(make_doc(["/", "/projects", "/gone"], ["fooAction", "barAction"]))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("/gone", out)

    def test_action_in_list_but_not_in_code_fails(self):
        self.repo.doc(make_doc(["/", "/projects"], ["fooAction", "barAction", "goneAction"]))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("goneAction", out)

    def test_heading_count_mismatch_fails_even_when_sets_match(self):
        self.repo.doc(make_doc(["/", "/projects"], ["fooAction", "barAction"], pages_heading=24))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("24", out)
        self.repo.doc(make_doc(["/", "/projects"], ["fooAction", "barAction"], actions_heading=100))
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("100", out)

    def test_unreadable_list_section_fails(self):
        self.repo.doc("## 10. 応答時間予算\n\n節が無い\n")
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("10.4", out)

    def test_missing_doc_fails(self):
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("ACCEPTANCE_CRITERIA.md", out)

    def test_default_root_is_the_repository(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cbl.main([])
        self.assertIn(code, (0, 1))


class RealRepository(unittest.TestCase):
    """実リポジトリのコードと §10 の一覧が一致している(#1544)。"""

    def test_code_and_list_agree(self):
        problems = cbl.find_problems(REPO_ROOT)
        self.assertEqual(problems, [], "\n".join(problems))


if __name__ == "__main__":
    unittest.main()
