#!/usr/bin/env python3
"""`scripts/check-worktree-match.py` の単体テスト(#1202)。

## なぜ Gherkin ではないのか

本Issueの受入基準が対象にしているのは「共有 docker compose スタックを**どの作業ツリーが
作ったか**」という、Web UI から一切観測できない前提である。判定には
`docker compose ls --format json` と、実行中の compose プロジェクトという実機の docker 状態
そのものが要る。この開発環境(サンドボックス)には docker デーモンが無く、複数の作業ツリーを
実際に用意してスタックを起動し分けることもできない。

`scripts/test_rebuild_acceptance_env.py`(#965)・`scripts/test_shared_host_proxy.py`(#1038)・
`scripts/test_git_hooks_binding.py`(#1039)と同じ、文書化された例外としてここで表現する。
本Issューの Scope 自体も「Unit tests: `scripts/test_*.py`」とだけ書いており、Gherkin シナリオ
を求めていない。

`docker` / `git` を実際に呼ぶ箇所は、モジュール内で `_docker_compose_ls()` / `_git()` の
2関数に閉じ込めてあり、ここではそれらを差し替えて入力(`docker compose ls` の JSON、
`git` の標準出力)だけを与える。プロダクションコードかどうかの判定は
`.claude/hooks/paths.py` の `classify()` を実際に import して使う(分類規則を書き写さない)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import importlib.util
import json
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "check-worktree-match.py")

_spec = importlib.util.spec_from_file_location("check_worktree_match", SCRIPT)
cwm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cwm)

MY_WORKTREE = "/home/seiji/src/lets_blog_server"
OTHER_WORKTREE = "/home/seiji/src/lets_blog_server-worker2"


def compose_ls_json(name="lets_blog_server", config_dir=MY_WORKTREE):
    config_files = ",".join(
        os.path.join(config_dir, f)
        for f in (
            "docker-compose.yml",
            "docker-compose.host-tests.yml",
            "docker-compose.e2e-stubs.yml",
        )
    )
    return json.dumps([{"Name": name, "ConfigFiles": config_files}])


def git_fake(rev_parse_root=MY_WORKTREE, merge_base="abc123", diff_lines=None, fail_on=()):
    """`_git(args, cwd)` の代わり。(code, stdout, stderr) の3値タプルを返す。"""
    diff_lines = diff_lines if diff_lines is not None else []

    def _fake(args, cwd):
        if args[:2] == ["rev-parse", "--show-toplevel"]:
            if "rev-parse" in fail_on:
                return 1, "", "not a git repository"
            return 0, rev_parse_root + "\n", ""
        if args[:1] == ["merge-base"]:
            if "merge-base" in fail_on:
                return 1, "", "fatal: no merge base"
            return 0, merge_base + "\n", ""
        if args[:2] == ["diff", "--name-only"]:
            if "diff" in fail_on:
                return 1, "", "fatal: bad revision"
            return 0, "\n".join(diff_lines) + ("\n" if diff_lines else ""), ""
        raise AssertionError("unexpected git args: %r" % (args,))

    return _fake


class GetStackWorktreeRoots(unittest.TestCase):
    """`docker compose ls --format json` の `ConfigFiles` から作業ツリーを割り出す。"""

    def test_parses_comma_separated_absolute_paths(self):
        with mock.patch.object(cwm, "_docker_compose_ls", return_value=compose_ls_json()):
            roots = cwm.get_stack_worktree_roots()
        self.assertEqual({MY_WORKTREE}, roots)

    def test_ignores_projects_with_a_different_name(self):
        raw = compose_ls_json(name="some_other_project")
        with mock.patch.object(cwm, "_docker_compose_ls", return_value=raw):
            roots = cwm.get_stack_worktree_roots()
        self.assertEqual(set(), roots)

    def test_returns_empty_set_when_docker_is_unavailable(self):
        with mock.patch.object(cwm, "_docker_compose_ls", return_value=""):
            roots = cwm.get_stack_worktree_roots()
        self.assertEqual(set(), roots)

    def test_returns_empty_set_on_malformed_json(self):
        with mock.patch.object(cwm, "_docker_compose_ls", return_value="not json"):
            roots = cwm.get_stack_worktree_roots()
        self.assertEqual(set(), roots)


class ProductionChangesUseThePathsClassifier(unittest.TestCase):
    """プロダクション/テストの判定は `.claude/hooks/paths.py` の `classify()` に委ねる。"""

    def test_feature_file_change_is_not_production(self):
        fake_git = git_fake(diff_lines=["apps/web/e2e/features/article/publish.feature"])
        with mock.patch.object(cwm, "_git", side_effect=fake_git):
            prod = cwm.production_changes_since_develop(cwd=MY_WORKTREE)
        self.assertEqual([], prod)

    def test_step_definition_change_is_not_production(self):
        fake_git = git_fake(diff_lines=["apps/web/e2e/steps/article.steps.ts"])
        with mock.patch.object(cwm, "_git", side_effect=fake_git):
            prod = cwm.production_changes_since_develop(cwd=MY_WORKTREE)
        self.assertEqual([], prod)

    def test_service_src_change_is_production(self):
        fake_git = git_fake(
            diff_lines=["services/identity/src/main/java/com/example/identity/UserService.java"]
        )
        with mock.patch.object(cwm, "_git", side_effect=fake_git):
            prod = cwm.production_changes_since_develop(cwd=MY_WORKTREE)
        self.assertEqual(
            ["services/identity/src/main/java/com/example/identity/UserService.java"], prod
        )

    def test_mixed_change_returns_only_the_production_paths(self):
        fake_git = git_fake(
            diff_lines=[
                "apps/web/e2e/features/article/publish.feature",
                "services/identity/src/main/java/com/example/identity/UserService.java",
                "docs/README.md",
            ]
        )
        with mock.patch.object(cwm, "_git", side_effect=fake_git):
            prod = cwm.production_changes_since_develop(cwd=MY_WORKTREE)
        self.assertEqual(
            ["services/identity/src/main/java/com/example/identity/UserService.java"], prod
        )

    def test_raises_when_merge_base_fails(self):
        fake_git = git_fake(fail_on=("merge-base",))
        with mock.patch.object(cwm, "_git", side_effect=fake_git):
            with self.assertRaises(cwm.WorktreeCheckError):
                cwm.production_changes_since_develop(cwd=MY_WORKTREE)


class CheckAtStart(unittest.TestCase):
    """受入基準: 受け入れテスト開始前のチェック(`at-start`)。"""

    def setUp(self):
        self.addCleanup(mock.patch.dict(os.environ, {}, clear=False).stop)
        os.environ.pop(cwm.BYPASS_ENV, None)

    def test_mismatch_with_production_changes_is_refused(self):
        """別作業ツリーが作ったスタックに対し、プロダクションコードを変更したブランチは拒否される。"""
        fake_git = git_fake(
            rev_parse_root=OTHER_WORKTREE,
            diff_lines=["services/identity/src/main/java/com/example/identity/UserService.java"],
        )
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("at-start")
        self.assertFalse(ok)
        self.assertIn(MY_WORKTREE, message)
        self.assertIn(OTHER_WORKTREE, message)
        self.assertIn(
            "services/identity/src/main/java/com/example/identity/UserService.java", message
        )

    def test_mismatch_with_only_test_changes_proceeds(self):
        """変更が .feature とステップ定義だけなら、作業ツリーが不一致でも通常どおり開始される。"""
        fake_git = git_fake(
            rev_parse_root=OTHER_WORKTREE,
            diff_lines=[
                "apps/web/e2e/features/article/publish.feature",
                "apps/web/e2e/steps/article.steps.ts",
            ],
        )
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("at-start")
        self.assertTrue(ok, message)

    def test_same_worktree_proceeds_even_with_production_changes(self):
        """スタックを作った作業ツリー自身から実行した場合は、プロダクション変更があっても通す。"""
        fake_git = git_fake(
            rev_parse_root=MY_WORKTREE,
            diff_lines=["services/identity/src/main/java/com/example/identity/UserService.java"],
        )
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("at-start")
        self.assertTrue(ok, message)

    def test_no_running_stack_proceeds(self):
        """稼働中の compose プロジェクトが見つからなければ、比較のしようが無いので通す。"""
        with mock.patch.object(cwm, "_docker_compose_ls", return_value=""):
            ok, message = cwm.check("at-start")
        self.assertTrue(ok, message)

    def test_merge_base_failure_is_refused_not_silently_passed(self):
        """origin/develop との比較に失敗したら、判定できないまま開始しない(bypassでのみ進める)。"""
        fake_git = git_fake(rev_parse_root=OTHER_WORKTREE, fail_on=("merge-base",))
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("at-start")
        self.assertFalse(ok)
        self.assertIn(cwm.BYPASS_ENV, message)


class CheckRebuild(unittest.TestCase):
    """受入基準: `rebuild-acceptance-env.sh` からの呼び出し(`rebuild`)。"""

    def setUp(self):
        self.addCleanup(mock.patch.dict(os.environ, {}, clear=False).stop)
        os.environ.pop(cwm.BYPASS_ENV, None)

    def test_mismatch_is_refused_regardless_of_production_changes(self):
        """撤去を始める前に停止して警告する。プロダクション変更の有無は問わない(#1202 要件5)。"""
        fake_git = git_fake(rev_parse_root=OTHER_WORKTREE, diff_lines=[])
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("rebuild")
        self.assertFalse(ok)
        self.assertIn(MY_WORKTREE, message)
        self.assertIn(OTHER_WORKTREE, message)
        # rebuild モードは merge-base を呼ぶ必要が無い(プロダクション差分を見ない)。

    def test_same_worktree_proceeds(self):
        fake_git = git_fake(rev_parse_root=MY_WORKTREE)
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("rebuild")
        self.assertTrue(ok, message)

    def test_no_running_stack_proceeds(self):
        with mock.patch.object(cwm, "_docker_compose_ls", return_value=""):
            ok, message = cwm.check("rebuild")
        self.assertTrue(ok, message)


class BypassEnvironmentVariable(unittest.TestCase):
    """受入基準: 迂回用の環境変数を設定した場合はチェックを飛ばし、飛ばしたことが記録される。"""

    def setUp(self):
        self.addCleanup(mock.patch.dict(os.environ, {}, clear=False).stop)

    def test_bypass_skips_the_check_for_at_start(self):
        os.environ[cwm.BYPASS_ENV] = "1"
        fake_git = git_fake(rev_parse_root=OTHER_WORKTREE, diff_lines=["services/x/src/y.java"])
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("at-start")
        self.assertTrue(ok)
        self.assertIn(cwm.BYPASS_ENV, message)

    def test_bypass_skips_the_check_for_rebuild(self):
        os.environ[cwm.BYPASS_ENV] = "1"
        fake_git = git_fake(rev_parse_root=OTHER_WORKTREE)
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("rebuild")
        self.assertTrue(ok)
        self.assertIn(cwm.BYPASS_ENV, message)

    def test_only_the_documented_value_bypasses(self):
        """'0' や空文字では迂回しない(誤って env を残していても効かないことの確認)。"""
        os.environ[cwm.BYPASS_ENV] = "0"
        fake_git = git_fake(rev_parse_root=OTHER_WORKTREE)
        with mock.patch.object(
            cwm, "_docker_compose_ls", return_value=compose_ls_json(config_dir=MY_WORKTREE)
        ), mock.patch.object(cwm, "_git", side_effect=fake_git):
            ok, message = cwm.check("rebuild")
        self.assertFalse(ok)


class CommandLineInterface(unittest.TestCase):
    def test_rejects_an_unknown_mode(self):
        code = cwm.main(["check-worktree-match.py", "bogus"])
        self.assertEqual(2, code)

    def test_rejects_missing_mode(self):
        code = cwm.main(["check-worktree-match.py"])
        self.assertEqual(2, code)

    def test_exit_code_reflects_the_verdict(self):
        with mock.patch.object(cwm, "check", return_value=(True, "ok")):
            self.assertEqual(0, cwm.main(["check-worktree-match.py", "at-start"]))
        with mock.patch.object(cwm, "check", return_value=(False, "no")):
            self.assertEqual(1, cwm.main(["check-worktree-match.py", "at-start"]))


class ImportsTheSharedClassifier(unittest.TestCase):
    """分類規則を書き写さず `.claude/hooks/paths.py` の `classify()` を import している(要件3)。"""

    def test_imports_classify_from_paths_module(self):
        source = open(SCRIPT, encoding="utf-8").read()
        self.assertIn("from paths import classify", source)
        self.assertNotIn("PRODUCTION_PATTERNS", source, "分類規則を書き写している")


if __name__ == "__main__":
    unittest.main()
