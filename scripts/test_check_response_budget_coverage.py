#!/usr/bin/env python3
"""`scripts/check-response-budget-coverage.py` と `scripts/response_budget_list.py` の単体テスト(#1477)。

3秒予算の対象一覧(`docs/ACCEPTANCE_CRITERIA.md` §10.4 / §10.5)と、受け入れシナリオの
`@budget-page:` / `@budget-action:` タグの突き合わせを検証する。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

一覧の解析(`response_budget_list`)は #1544(一覧とコードの照合)も使うので、独立に検証する。
"""

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, HERE)

import response_budget_list as rbl  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "check_response_budget_coverage", os.path.join(HERE, "check-response-budget-coverage.py")
)
crb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(crb)

HEADER = "| 操作 | 計測点 | 閾値 | 閾値の根拠 | 分類 |\n| --- | --- | --- | --- | --- |\n"

DOC = (
    "## 10. 応答時間予算\n\n"
    "### 10.4 画面の初回表示(全2ページ)\n\n"
    + HEADER
    + "| `/projects` の初回表示 | ① | 3,000ms | 根拠 | 予算対象 |\n"
    + "| `/projects/[id]` の初回表示 | ① | 3,000ms | 根拠 | 予算対象外 |\n\n"
    "### 10.5 Server Action(全N件)\n\n"
    + HEADER
    + "| `app/a/actions.ts`<br>`fooAction`<br>`barAction` | ② | 3,000ms | 根拠 | 予算対象 |\n"
    + "| `app/b/actions.ts`<br>`slowAction`(サイト自動構築) | ② | 3,000ms | 根拠 | 非同期ハンドオフ待ち(#1478) |\n"
    + "| `app/c/actions.ts`<br>`llmAction` | — | — | 根拠 | 予算対象外 |\n\n"
    "### 10.6 一覧に載せない操作\n\n"
    "| `/ignored` の初回表示 | ① | 3,000ms | 根拠 | 予算対象 |\n"
)


class ParseList(unittest.TestCase):
    def setUp(self):
        self.rows = rbl.parse_budget_list(DOC)

    def test_pages_are_read_from_section_10_4(self):
        pages = [(r.ident, r.classification) for r in self.rows if r.kind == "page"]
        self.assertEqual(pages, [("/projects", "予算対象"), ("/projects/[id]", "予算対象外")])

    def test_actions_are_split_per_name_from_section_10_5(self):
        actions = {r.ident: r.classification for r in self.rows if r.kind == "action"}
        self.assertEqual(
            actions,
            {
                "fooAction": "予算対象",
                "barAction": "予算対象",
                "slowAction": "非同期ハンドオフ待ち(#1478)",
                "llmAction": "予算対象外",
            },
        )

    def test_rows_after_section_10_5_are_not_read(self):
        self.assertNotIn("/ignored", [r.ident for r in self.rows])

    def test_only_exact_budget_target_counts_as_target(self):
        targets = {r.ident for r in self.rows if r.is_budget_target}
        self.assertEqual(targets, {"/projects", "fooAction", "barAction"})

    def test_missing_section_is_an_error(self):
        with self.assertRaises(ValueError):
            rbl.parse_budget_list("# nothing here\n")

    def test_row_without_identifier_is_an_error(self):
        broken = DOC.replace("`/projects` の初回表示", "ただの文字列")
        with self.assertRaises(ValueError):
            rbl.parse_budget_list(broken)

    def test_row_with_wrong_cell_count_is_an_error(self):
        broken = DOC.replace("| 根拠 | 予算対象 |\n| `/projects/[id]`", "| 予算対象 |\n| `/projects/[id]`")
        with self.assertRaises(ValueError):
            rbl.parse_budget_list(broken)

    def test_action_row_without_names_is_an_error(self):
        broken = DOC.replace("<br>`fooAction`<br>`barAction`", "")
        with self.assertRaises(ValueError):
            rbl.parse_budget_list(broken)


UNREACHABLE_DOC = (
    "### 10.4 画面の初回表示(全1ページ)\n\n"
    + HEADER
    + "| `/projects` の初回表示 | ① | 3,000ms | 根拠 | 予算対象 |\n\n"
    "### 10.5 Server Action(全N件)\n\n"
    + HEADER
    + "| `app/a/actions.ts`<br>`fooAction`<br>"
    "`deadAction`(受け入れシナリオから到達できない: UI のどこからも呼ばれていない)<br>`barAction` "
    "| ② | 3,000ms | 根拠 | 予算対象 |\n"
)


class ParseUnreachable(unittest.TestCase):
    def test_note_after_a_name_marks_only_that_action_unreachable_with_its_reason(self):
        rows_ = {r.ident: r for r in rbl.parse_budget_list(UNREACHABLE_DOC)}
        self.assertEqual(rows_["deadAction"].unreachable, "UI のどこからも呼ばれていない")
        self.assertEqual(rows_["fooAction"].unreachable, "")
        self.assertEqual(rows_["barAction"].unreachable, "")

    def test_unreachable_action_is_still_a_budget_target(self):
        rows_ = {r.ident: r for r in rbl.parse_budget_list(UNREACHABLE_DOC)}
        self.assertTrue(rows_["deadAction"].is_budget_target)

    def test_other_notes_are_not_unreachable(self):
        doc = UNREACHABLE_DOC.replace("(受け入れシナリオから到達できない: UI のどこからも呼ばれていない)", "(環境間同期)")
        self.assertEqual({r.ident: r.unreachable for r in rbl.parse_budget_list(doc)}["deadAction"], "")

    def test_marker_without_a_reason_is_an_error(self):
        doc = UNREACHABLE_DOC.replace("到達できない: UI のどこからも呼ばれていない", "到達できない:")
        with self.assertRaises(ValueError):
            rbl.parse_budget_list(doc)


FEATURE = """# language: ja
@response-budget
機能: 例

  背景:
    前提 何か

  # コメント
  @budget-page:/projects
  シナリオ: 一覧
    もし 開く

  @budget-action:fooAction @budget-action:barAction
  # タグとシナリオの間のコメントは許す
  @other
  シナリオアウトライン: 操作
    もし 操作する

    例:
      | a |
      | 1 |

  シナリオ: タグなし
    もし 何か
"""


class ScanFeature(unittest.TestCase):
    def test_scenario_tags_are_collected_with_their_scenario_names(self):
        decls, errors = crb.scan_feature_text(FEATURE, "x.feature")
        self.assertEqual(errors, [])
        found = sorted((d.kind, d.ident, d.scenario) for d in decls)
        self.assertEqual(
            found,
            [
                ("action", "barAction", "操作"),
                ("action", "fooAction", "操作"),
                ("page", "/projects", "一覧"),
            ],
        )

    def test_declaration_remembers_file_and_line(self):
        decls, _ = crb.scan_feature_text(FEATURE, "x.feature")
        page = [d for d in decls if d.kind == "page"][0]
        self.assertEqual((page.path, page.line), ("x.feature", 9))

    def test_tag_on_feature_line_is_an_error(self):
        text = "@budget-page:/projects\n機能: 例\n\n  シナリオ: a\n    もし b\n"
        decls, errors = crb.scan_feature_text(text, "f.feature")
        self.assertEqual(decls, [])
        self.assertEqual(len(errors), 1)
        self.assertIn("f.feature:1", errors[0])

    def test_tag_on_examples_is_an_error(self):
        text = "機能: 例\n\n  シナリオアウトライン: a\n    もし b\n\n    @budget-page:/x\n    例:\n      | a |\n      | 1 |\n"
        decls, errors = crb.scan_feature_text(text, "f.feature")
        self.assertEqual(decls, [])
        self.assertEqual(len(errors), 1)
        self.assertIn("f.feature:6", errors[0])

    def test_dangling_tag_before_a_step_line_is_dropped(self):
        text = "機能: 例\n\n  @budget-page:/x\n  背景:\n    前提 a\n"
        decls, errors = crb.scan_feature_text(text, "f.feature")
        self.assertEqual(decls, [])
        self.assertEqual(len(errors), 1)

    def test_english_keywords_are_recognised(self):
        text = "Feature: e\n\n  @budget-page:/projects\n  Scenario: s\n    When x\n"
        decls, errors = crb.scan_feature_text(text, "e.feature")
        self.assertEqual(errors, [])
        self.assertEqual([d.ident for d in decls], ["/projects"])


def rows(*specs):
    return [rbl.BudgetRow(kind=k, ident=i, classification=c, source=k) for k, i, c in specs]


def unreachable_row(ident, reason="UI から呼ばれていない"):
    return rbl.BudgetRow(kind="action", ident=ident, classification="予算対象", source="action", unreachable=reason)


def decl(kind, ident):
    return crb.Declaration(kind=kind, ident=ident, path="a.feature", line=1, scenario="s")


T = "予算対象"
X = "予算対象外"
H = "非同期ハンドオフ待ち(#1478)"


class CheckMismatch(unittest.TestCase):
    def test_everything_covered_has_no_problem(self):
        problems = crb.check(rows(("page", "/p", T), ("action", "aAction", T)), [decl("page", "/p"), decl("action", "aAction")])
        self.assertEqual(problems, [])

    def test_budget_target_without_scenario_is_reported(self):
        problems = crb.check(rows(("page", "/p", T), ("action", "aAction", T)), [decl("page", "/p")])
        self.assertEqual(len(problems), 1)
        self.assertIn("aAction", problems[0])
        self.assertIn("シナリオがありません", problems[0])

    def test_scenario_for_unlisted_item_is_reported(self):
        problems = crb.check(rows(("page", "/p", T)), [decl("page", "/p"), decl("page", "/nowhere")])
        self.assertEqual(len(problems), 1)
        self.assertIn("/nowhere", problems[0])
        self.assertIn("一覧にありません", problems[0])

    def test_scenario_for_non_target_row_is_reported(self):
        for classification in (X, H):
            problems = crb.check(rows(("action", "aAction", classification)), [decl("action", "aAction")])
            self.assertEqual(len(problems), 1, classification)
            self.assertIn("予算対象ではありません", problems[0])

    def test_non_target_row_without_scenario_is_fine(self):
        self.assertEqual(crb.check(rows(("action", "aAction", X), ("action", "bAction", H)), []), [])

    def test_unreachable_budget_target_needs_no_scenario(self):
        self.assertEqual(crb.check([unreachable_row("deadAction")], []), [])

    def test_scenario_for_an_action_noted_unreachable_is_reported_as_a_stale_note(self):
        problems = crb.check([unreachable_row("deadAction")], [decl("action", "deadAction")])
        self.assertEqual(len(problems), 1)
        self.assertIn("deadAction", problems[0])
        self.assertIn("到達できない", problems[0])

    def test_unreachable_note_on_a_non_target_row_is_reported(self):
        row = rbl.BudgetRow(kind="action", ident="llmAction", classification=X, source="action", unreachable="理由")
        problems = crb.check([row], [])
        self.assertEqual(len(problems), 1)
        self.assertIn("予算対象ではありません", problems[0])

    def test_page_and_action_namespaces_do_not_mix(self):
        problems = crb.check(rows(("page", "x", T)), [decl("action", "x")])
        self.assertEqual(len(problems), 2)

    def test_pages_only_ignores_actions_both_ways(self):
        listed = rows(("page", "/p", T), ("action", "aAction", T))
        declared = [decl("page", "/p"), decl("action", "ghostAction")]
        self.assertEqual(crb.check(listed, declared, scope="pages"), [])
        self.assertEqual(len(crb.check(listed, declared, scope="all")), 2)

    def test_actions_only_ignores_pages_both_ways(self):
        listed = rows(("page", "/p", T), ("action", "aAction", T))
        declared = [decl("action", "aAction"), decl("page", "/ghost")]
        self.assertEqual(crb.check(listed, declared, scope="actions"), [])

    def test_problems_are_sorted_and_name_the_declaring_location(self):
        problems = crb.check(rows(("page", "/b", T), ("page", "/a", T)), [])
        self.assertLess(problems.index([p for p in problems if "/a" in p][0]), problems.index([p for p in problems if "/b" in p][0]))
        out = crb.check(rows(), [crb.Declaration("page", "/z", "d/z.feature", 7, "シナリオZ")])
        self.assertIn("d/z.feature:7", out[0])


class Main(unittest.TestCase):
    def _repo(self, doc, features):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = tmp.name
        os.makedirs(os.path.join(root, "docs"))
        with open(os.path.join(root, "docs", "ACCEPTANCE_CRITERIA.md"), "w", encoding="utf-8") as fh:
            fh.write(doc)
        fdir = os.path.join(root, "apps", "web", "e2e", "features", "response-budget")
        os.makedirs(fdir)
        for name, text in features.items():
            with open(os.path.join(fdir, name), "w", encoding="utf-8") as fh:
                fh.write(text)
        return root

    def _run(self, root, *argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            code = crb.main(list(argv), repo_root=root)
        return code, buf.getvalue()

    PAGES_FEATURE = (
        "機能: p\n\n  @budget-page:/projects\n  シナリオ: a\n    もし x\n\n"
    )
    ACTIONS_FEATURE = (
        "機能: a\n\n  @budget-action:fooAction\n  @budget-action:barAction\n  シナリオ: a\n    もし x\n\n"
    )

    def test_default_mode_fails_while_actions_are_missing(self):
        root = self._repo(DOC, {"p.feature": self.PAGES_FEATURE})
        code, out = self._run(root)
        self.assertEqual(code, 1, out)
        self.assertIn("fooAction", out)
        self.assertIn("barAction", out)

    def test_pages_only_passes_while_actions_are_pending(self):
        root = self._repo(DOC, {"p.feature": self.PAGES_FEATURE})
        code, out = self._run(root, "--pages-only")
        self.assertEqual(code, 0, out)
        self.assertIn("OK", out)

    def test_actions_only_fails_without_action_scenarios(self):
        root = self._repo(DOC, {"p.feature": self.PAGES_FEATURE})
        code, _ = self._run(root, "--actions-only")
        self.assertEqual(code, 1)

    def test_default_mode_passes_when_everything_is_covered(self):
        root = self._repo(DOC, {"p.feature": self.PAGES_FEATURE, "a.feature": self.ACTIONS_FEATURE})
        code, out = self._run(root)
        self.assertEqual(code, 0, out)

    def test_both_scope_flags_are_rejected(self):
        root = self._repo(DOC, {})
        with self.assertRaises(SystemExit):
            self._run(root, "--pages-only", "--actions-only")

    def test_malformed_tag_placement_fails(self):
        root = self._repo(DOC, {"p.feature": "@budget-page:/projects\n機能: p\n\n  シナリオ: a\n    もし x\n"})
        code, out = self._run(root, "--pages-only")
        self.assertEqual(code, 1)
        self.assertIn("p.feature:1", out)

    def test_unparsable_list_fails_with_a_message(self):
        root = self._repo("# no list\n", {})
        code, out = self._run(root)
        self.assertEqual(code, 2, out)

    def test_unreachable_note_keeps_the_list_green_and_is_counted_in_the_output(self):
        doc = DOC.replace("`fooAction`<br>", "`fooAction`(受け入れシナリオから到達できない: UI から呼ばれていない)<br>")
        feature = "機能: a\n\n  @budget-action:barAction\n  シナリオ: a\n    もし x\n\n"
        root = self._repo(doc, {"p.feature": self.PAGES_FEATURE, "a.feature": feature})
        code, out = self._run(root)
        self.assertEqual(code, 0, out)
        self.assertIn("到達できない 1 件", out)

    def test_non_feature_files_are_ignored(self):
        root = self._repo(DOC, {"p.feature": self.PAGES_FEATURE, "notes.txt": "@budget-page:/ghost"})
        code, _ = self._run(root, "--pages-only")
        self.assertEqual(code, 0)
    def test_main_fails_when_budget_feature_lacks_retries(self):
        root = self._repo(DOC, {"p.feature": "@response-budget\n" + self.PAGES_FEATURE})
        code, out = self._run(root, "--pages-only")
        self.assertEqual(code, 1, out)
        self.assertIn("@retries:2", out)

    def test_main_passes_when_budget_feature_has_retries(self):
        root = self._repo(DOC, {"p.feature": "@response-budget @retries:2\n" + self.PAGES_FEATURE})
        code, out = self._run(root, "--pages-only")
        self.assertEqual(code, 0, out)


class RetriesTag(unittest.TestCase):
    """#1554: `@response-budget` の feature だけが feature 単位の `@retries:2` を持つ。"""

    def scan(self, text, path="f.feature"):
        return crb.check_retries_text(text, path)

    def test_budget_feature_with_retries_2_is_ok(self):
        self.assertEqual([], self.scan("# language: ja\n@response-budget @retries:2\n機能: x\n"))

    def test_tags_on_separate_lines_are_ok(self):
        self.assertEqual([], self.scan("@response-budget\n@retries:2\n機能: x\n"))

    def test_budget_feature_without_retries_fails(self):
        problems = self.scan("@response-budget\n機能: x\n")
        self.assertEqual(1, len(problems))
        self.assertIn("f.feature", problems[0])
        self.assertIn("@retries:2", problems[0])

    def test_budget_feature_with_other_retries_value_fails(self):
        self.assertEqual(1, len(self.scan("@response-budget @retries:3\n機能: x\n")))

    def test_non_budget_feature_with_retries_fails(self):
        problems = self.scan("@retries:2\n機能: x\n")
        self.assertEqual(1, len(problems))
        self.assertIn("@response-budget", problems[0])

    def test_scenario_level_retries_fails_in_any_feature(self):
        self.assertEqual(1, len(self.scan("機能: x\n\n  @retries:2\n  シナリオ: a\n    もし x\n")))
        self.assertEqual(
            1, len(self.scan("@response-budget @retries:2\n機能: x\n\n  @retries:1\n  シナリオ: a\n    もし x\n"))
        )

    def test_scenario_tag_response_budget_alone_does_not_make_a_budget_feature(self):
        self.assertEqual([], self.scan("機能: x\n\n  @response-budget\n  シナリオ: a\n    もし x\n"))

    def test_plain_feature_is_ok(self):
        self.assertEqual([], self.scan("@slow\n機能: x\n"))


class RealRepository(unittest.TestCase):
    """実リポジトリの一覧とシナリオが一致していること(これが歯止めの本体)。"""

    def test_every_page_first_display_budget_target_has_a_scenario(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = crb.main(["--pages-only"], repo_root=REPO_ROOT)
        self.assertEqual(code, 0, buf.getvalue())

    def test_every_real_response_budget_feature_has_retries_2_and_no_other_feature_has_a_retries_tag(self):
        problems, _ = [], None
        root = os.path.join(REPO_ROOT, crb.FEATURES_DIR)
        for directory, _dirs, files in os.walk(root):
            for name in files:
                if name.endswith(".feature"):
                    full = os.path.join(directory, name)
                    with open(full, encoding="utf-8") as fh:
                        problems += crb.check_retries_text(fh.read(), os.path.relpath(full, REPO_ROOT))
        self.assertEqual([], problems)

    def test_the_real_list_has_all_24_pages_all_budget_targets(self):
        with open(os.path.join(REPO_ROOT, "docs", "ACCEPTANCE_CRITERIA.md"), encoding="utf-8") as fh:
            parsed = rbl.parse_budget_list(fh.read())
        pages = [r for r in parsed if r.kind == "page"]
        self.assertGreaterEqual(len(pages), 24)
        self.assertTrue(all(r.is_budget_target for r in pages))
        actions = {r.ident: r for r in parsed if r.kind == "action"}
        self.assertTrue(actions["updateProjectNameAction"].is_budget_target)
        self.assertFalse(actions["generateCustomTagAction"].is_budget_target)
        self.assertFalse(actions["installComfyUiCheckpointAction"].is_budget_target)


if __name__ == "__main__":
    unittest.main()
