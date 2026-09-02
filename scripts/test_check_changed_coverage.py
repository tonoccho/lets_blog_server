#!/usr/bin/env python3
"""`scripts/check-changed-coverage.py` のゲート判定の単体テスト(#988)。

`scripts/` は既存のどのテストランナー(jest / playwright-bdd / Gradle)の対象にも
なっていないため、`.claude/hooks/test_paths.py` と同じく Python 標準の unittest で回す。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

検査対象は `main()` の判定そのもの。git と各カバレッジレポートの読み取りは
差し替え、「変更ファイルの一覧」と「レポートの中身」だけを入力として与える。
"""

import contextlib
import importlib.util
import io
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "check-changed-coverage.py")

_spec = importlib.util.spec_from_file_location("check_changed_coverage", SCRIPT)
ccc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ccc)


def run_main(changed, coverage=None):
    """変更ファイル一覧とカバレッジ実測値を与えて main() を回し、(終了コード, 標準出力) を返す。"""
    coverage = coverage or {}
    with mock.patch.object(ccc, "run", return_value=""), mock.patch.object(
        ccc, "changed_production_files", return_value=list(changed)
    ), mock.patch.object(ccc, "jacoco_branches", return_value={}), mock.patch.object(
        ccc, "jest_branches", return_value=dict(coverage)
    ), mock.patch.object(
        sys, "argv", ["check-changed-coverage.py"]
    ):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = ccc.main()
    return code, buf.getvalue()


class UnmeasurableProductionCode(unittest.TestCase):
    """カバレッジ計測の仕組みが原理的に存在しないプロダクションコード(#988)。

    `paths.is_production()` はテストファーストとフェーズ分離のための分類であり、
    「jest / JaCoCo で計測できるか」とは別軸である。後者に当たらないファイルは
    レポートが出ないので、レポート必須の対象から外れていなければならない。
    """

    def test_extension_webviews_only_does_not_fail(self):
        code, out = run_main(["apps/extension/webviews/diagramGallery.js"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_extension_webviews_vendor_bundle_does_not_fail(self):
        code, out = run_main(["apps/extension/webviews/vendor/prism/prism-bundle.min.js"])
        self.assertEqual(code, 0, out)

    def test_infra_e2e_stubs_only_does_not_fail(self):
        code, out = run_main(["infra/e2e-stubs/llm/server.js"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_infra_e2e_stubs_shared_lib_does_not_fail(self):
        code, out = run_main(["infra/e2e-stubs/lib/stub.js"])
        self.assertEqual(code, 0, out)

    def test_next_config_does_not_fail(self):
        """`apps/web/next.config.ts` も src/ の外にあり jest の計測対象ではない。"""
        code, out = run_main(["apps/web/next.config.ts"])
        self.assertEqual(code, 0, out)

    def test_unmeasurable_files_are_reported_as_skipped(self):
        """黙って消えるのではなく、計測対象外として扱った旨が出力に残ること。"""
        _, out = run_main(["infra/e2e-stubs/llm/server.js"])
        self.assertIn("infra/e2e-stubs/llm/server.js", out)
        self.assertIn("計測対象外", out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)


class MeasurableProductionCodeStillGated(unittest.TestCase):
    """回帰ガード: 計測可能なツリーの扱いは従来どおりであること。"""

    JVM = "services/identity/src/main/java/com/example/identity/UserService.java"
    WEB = "apps/web/src/lib/format.ts"

    def test_jvm_missing_report_fails(self):
        code, out = run_main([self.JVM])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)
        self.assertIn(self.JVM, out)

    def test_web_missing_report_fails(self):
        code, out = run_main([self.WEB])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)

    def test_extension_src_missing_report_fails(self):
        code, out = run_main(["apps/extension/src/proofreadLogic.ts"])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)

    def test_packages_missing_report_fails(self):
        code, out = run_main(["packages/lbs-common/src/main/java/com/example/common/Json.java"])
        self.assertEqual(code, 1)

    def test_jvm_below_threshold_fails(self):
        code, out = run_main([self.JVM], {self.JVM: (5, 5)})
        self.assertEqual(code, 1)
        self.assertIn("下回っています", out)

    def test_web_below_threshold_fails(self):
        code, out = run_main([self.WEB], {self.WEB: (2, 8)})
        self.assertEqual(code, 1)
        self.assertIn("下回っています", out)

    def test_web_at_threshold_passes(self):
        code, out = run_main([self.WEB], {self.WEB: (1, 9)})
        self.assertEqual(code, 0, out)

    def test_branchless_measurable_file_passes(self):
        code, out = run_main([self.WEB], {self.WEB: (0, 0)})
        self.assertEqual(code, 0, out)

    def test_exemption_does_not_mask_a_measurable_file(self):
        """免除対象と混在しても、計測可能な側の不足は従来どおり落ちること。"""
        code, out = run_main(["infra/e2e-stubs/llm/server.js", self.JVM])
        self.assertEqual(code, 1)
        self.assertIn(self.JVM, out)

    def test_exempt_and_covered_measurable_file_passes(self):
        code, out = run_main(
            ["apps/extension/webviews/diagramGallery.js", self.WEB], {self.WEB: (0, 10)}
        )
        self.assertEqual(code, 0, out)


class NoProductionChange(unittest.TestCase):
    def test_no_changed_production_files_skips(self):
        code, out = run_main([])
        self.assertEqual(code, 0, out)
        self.assertIn("スキップ", out)

    def test_only_unmeasurable_changes_is_not_treated_as_a_failure(self):
        code, out = run_main(
            ["infra/e2e-stubs/llm/server.js", "apps/extension/webviews/plan.js"]
        )
        self.assertEqual(code, 0, out)

    def test_git_diff_failure_still_fails(self):
        with mock.patch.object(ccc, "run", return_value=""), mock.patch.object(
            ccc, "changed_production_files", return_value=None
        ), mock.patch.object(sys, "argv", ["check-changed-coverage.py"]):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = ccc.main()
        self.assertEqual(code, 1)
        self.assertIn("git diff", buf.getvalue())


class MeasurabilityClassifier(unittest.TestCase):
    """`is_measurable()` は「計測できるか」だけを決め、プロダクション判定を代替しない。"""

    MEASURABLE = [
        "services/identity/src/main/java/com/example/identity/UserService.java",
        "services/content/src/main/kotlin/com/example/content/Post.kt",
        "packages/lbs-common/src/main/java/com/example/common/Json.java",
        "apps/web/src/app/page.tsx",
        "apps/extension/src/config.ts",
        "apps/mcp-server/src/tools/designSuggestion.js",
    ]

    UNMEASURABLE = [
        "apps/extension/webviews/diagramGallery.js",
        "apps/extension/webviews/vendor/prism/prism-bundle.min.js",
        "infra/e2e-stubs/llm/server.js",
        "infra/e2e-stubs/lib/stub.js",
        "apps/web/next.config.ts",
    ]

    def test_measurable_trees(self):
        for path in self.MEASURABLE:
            with self.subTest(path=path):
                self.assertTrue(ccc.is_measurable(path))

    def test_unmeasurable_trees(self):
        for path in self.UNMEASURABLE:
            with self.subTest(path=path):
                self.assertFalse(ccc.is_measurable(path))

    def test_unmeasurable_files_are_still_production_code(self):
        """免除はカバレッジゲートに限る。フェーズ分離とテストファーストは効いたままであること。"""
        sys.path.insert(0, os.path.join(REPO_ROOT, ".claude", "hooks"))
        import paths  # noqa: E402

        for path in self.UNMEASURABLE:
            with self.subTest(path=path):
                self.assertTrue(paths.is_production(path))


if __name__ == "__main__":
    unittest.main()
