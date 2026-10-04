#!/usr/bin/env python3
"""`scripts/git-hooks/pre-commit` が apps/web のカバレッジ床(#1040)を検知することの検証。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

#1040 が対象とするのは `apps/web/jest.config.ts` の `coverageThreshold.global` という
git フック配線であって、製品の画面には一切現れない。`scripts/test_git_hooks_binding.py` と
同じ文書化された例外(CLAUDE.md → Test-First Implementation)として、ここで
スクリプトレベルのテストとして表現する。

## なぜ本物の `npm run test:coverage` を実行しないのか

このチェックが `apps/web` を触るすべてのコミットで本物の jest を走らせる(20秒超)ことを
検証したいわけではない。検証したいのは **配線** — 「apps/web に触れたときだけ
`npm run test:coverage` が呼ばれ、その終了コードでコミットの合否が決まること」である。
そこで `PATH` の先頭に `npm` という名前の偽の実行可能ファイルを差し込み、その終了コードと
呼び出しの記録だけを見る。実物の jest を差し替えるのではなく、実行される**コマンド**を
差し替える点が、`scripts/test_check_changed_coverage.py` の `mock.patch.object` による
関数差し替えと役割上同じである。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

HOOKS_DIR = "scripts/git-hooks"
SETUP_SCRIPT = os.path.join(HERE, "setup-git-hooks.sh")
PRE_COMMIT = os.path.join(REPO_ROOT, HOOKS_DIR, "pre-commit")
PRE_MERGE_COMMIT = os.path.join(REPO_ROOT, HOOKS_DIR, "pre-merge-commit")
PRE_PUSH = os.path.join(REPO_ROOT, HOOKS_DIR, "pre-push")

FAKE_NPM = """#!/bin/bash
echo "$@" >> "$FAKE_NPM_LOG"
echo "$PWD" >> "$FAKE_NPM_LOG"
[ -n "$FAKE_NPM_OUTPUT" ] && echo "$FAKE_NPM_OUTPUT"
exit "$FAKE_NPM_EXIT_CODE"
"""


def git(args, cwd, env=None, **kwargs):
    full_env = dict(os.environ)
    for key in list(full_env):
        if key.startswith("GIT_"):
            del full_env[key]
    if env:
        full_env.update(env)
    return subprocess.run(
        ["git"] + args, cwd=cwd, capture_output=True, text=True, env=full_env, timeout=60, **kwargs
    )


class TempRepo(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.tmp, "scripts", "git-hooks"))
        os.makedirs(os.path.join(self.tmp, ".claude", "hooks"))
        os.makedirs(os.path.join(self.tmp, "apps", "web"))
        os.makedirs(os.path.join(self.tmp, "services", "project-service"))
        # `check_web_coverage_floor` は apps/web/package.json の有無で「実際のnpm
        # プロジェクトか」を判定する(#1040)。他のフック検査のtemp repo雛形には
        # package.jsonが無く、そこでは黙ってスキップさせたい — 本テストでは実際に
        # 検査が走ることを確かめたいので、ここでは用意する。
        self.write("apps/web/package.json", '{"name": "web", "scripts": {}}\n')
        # 依存が導入済みの apps/web を既定にする。未導入(#1320)の再現は個別のテストで
        # このディレクトリを消して行う。git は空ディレクトリを追跡しない。
        os.makedirs(os.path.join(self.tmp, "apps", "web", "node_modules"))
        shutil.copy(SETUP_SCRIPT, os.path.join(self.tmp, "scripts", "setup-git-hooks.sh"))
        shutil.copy(PRE_COMMIT, os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"))
        os.chmod(os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"), 0o755)
        # setup-git-hooks.sh は #1452 で pre-commit と pre-merge-commit の両方が
        # 揃っていることを束縛の前提にした。ここに pre-merge-commit を置かないと
        # 束縛そのものが失敗し、フックが1つも配線されない temp repo になる。
        if os.path.isfile(PRE_MERGE_COMMIT):
            dst = os.path.join(self.tmp, "scripts", "git-hooks", "pre-merge-commit")
            shutil.copy(PRE_MERGE_COMMIT, dst)
            os.chmod(dst, 0o755)
        # setup-git-hooks.sh は #1514 で pre-push も束縛の前提にした(HOOK_NAMES)。
        if os.path.isfile(PRE_PUSH):
            dst = os.path.join(self.tmp, "scripts", "git-hooks", "pre-push")
            shutil.copy(PRE_PUSH, dst)
            os.chmod(dst, 0o755)
        shutil.copy(
            os.path.join(REPO_ROOT, ".claude", "hooks", "paths.py"),
            os.path.join(self.tmp, ".claude", "hooks", "paths.py"),
        )
        shutil.copy(
            os.path.join(REPO_ROOT, ".claude", "hooks", "silencers.py"),
            os.path.join(self.tmp, ".claude", "hooks", "silencers.py"),
        )
        git(["init", "-q"], cwd=self.tmp)
        git(["config", "user.email", "t@example.com"], cwd=self.tmp)
        git(["config", "user.name", "t"], cwd=self.tmp)
        git(["add", "-A"], cwd=self.tmp)
        git(["commit", "-q", "-m", "init"], cwd=self.tmp)

        setup = subprocess.run(
            ["bash", os.path.join(self.tmp, "scripts", "setup-git-hooks.sh")],
            capture_output=True, text=True, timeout=60, cwd=self.tmp,
        )
        assert setup.returncode == 0, (
            "fixture の setup-git-hooks.sh が束縛に失敗した: " + setup.stdout + setup.stderr
        )

        self.bin = os.path.join(self.tmp, "fakebin")
        os.makedirs(self.bin)
        self.npm_log = os.path.join(self.tmp, "npm-invocations.log")
        npm_path = os.path.join(self.bin, "npm")
        with open(npm_path, "w", encoding="utf-8") as f:
            f.write(FAKE_NPM)
        st = os.stat(npm_path)
        os.chmod(npm_path, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, rel_path, text):
        full = os.path.join(self.tmp, rel_path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
        return rel_path

    def commit(self, message, npm_exit_code=0, npm_output=""):
        env = {
            "PATH": self.bin + os.pathsep + os.environ.get("PATH", ""),
            "FAKE_NPM_LOG": self.npm_log,
            "FAKE_NPM_EXIT_CODE": str(npm_exit_code),
            "FAKE_NPM_OUTPUT": npm_output,
        }
        return git(["commit", "-m", message], cwd=self.tmp, env=env)

    def npm_invocations(self):
        if not os.path.exists(self.npm_log):
            return None
        with open(self.npm_log, encoding="utf-8") as f:
            return f.read()


class WebCoverageFloor(TempRepo):
    """受入基準3(#1040): 床を割った状態が pre-commit で自動的に検知される。"""

    def test_apps_web_change_with_failing_coverage_is_rejected(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)
        r = self.commit("feat: touch web", npm_exit_code=1)
        self.assertNotEqual(0, r.returncode, "床を割った状態のコミットが通ってしまった: " + r.stdout)
        self.assertIn("test:coverage", r.stdout + r.stderr)

    def test_apps_web_change_with_passing_coverage_is_accepted(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)
        r = self.commit("feat: touch web", npm_exit_code=0)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_runs_npm_test_coverage_inside_apps_web(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)
        self.commit("feat: touch web", npm_exit_code=0)
        log = self.npm_invocations()
        self.assertIsNotNone(log, "npm が一度も呼ばれなかった")
        self.assertIn("run test:coverage", log)
        self.assertTrue(
            log.strip().endswith(os.path.join(self.tmp, "apps", "web")),
            "apps/web の外で npm が呼ばれている: %r" % log,
        )

    def test_change_outside_apps_web_does_not_invoke_npm(self):
        """services/** だけを触るコミットで毎回20秒級のjestを走らせない(#1040のスコープ要求)。"""
        git(
            [
                "add",
                self.write(
                    "services/project-service/src/main/kotlin/Foo.kt", "class Foo\n"
                ),
            ],
            cwd=self.tmp,
        )
        r = self.commit("feat: touch service", npm_exit_code=1)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNone(self.npm_invocations(), "apps/web を触っていないのに npm が呼ばれた")


class WebCoverageFloorTestPhase(TempRepo):
    """#1377: RED のテスト位相のコミットは「テストが落ちている」ことを理由に拒否されない。

    床(coverageThreshold)割れの検査は残す。落ちたテストと床割れは出力で区別する。
    """

    TEST_FAILURE = "Tests:       1 failed, 626 passed, 627 total"
    FLOOR_FAILURE = "Jest: Coverage for branches (30%) does not meet global threshold (40%)"

    def stage_test(self):
        git(["add", self.write("apps/web/src/foo.test.ts", "test('x', () => {})\n")], cwd=self.tmp)

    def test_test_only_commit_with_failing_tests_is_accepted(self):
        self.stage_test()
        r = self.commit("test: red", npm_exit_code=1, npm_output=self.TEST_FAILURE)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_production_commit_with_failing_tests_is_still_rejected(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)
        r = self.commit("feat: green", npm_exit_code=1, npm_output=self.TEST_FAILURE)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)

    def test_test_only_commit_breaking_the_floor_is_still_rejected(self):
        self.stage_test()
        r = self.commit(
            "test: red", npm_exit_code=1, npm_output=self.TEST_FAILURE + "\n" + self.FLOOR_FAILURE
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("下回っています", r.stdout + r.stderr)

    def test_production_commit_breaking_the_floor_is_still_rejected(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)
        r = self.commit("feat: green", npm_exit_code=1, npm_output=self.FLOOR_FAILURE)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("下回っています", r.stdout + r.stderr)


class WebCoverageFloorEnvironmentNotSetUp(TempRepo):
    """#1320: node_modules 未導入の worktree での失敗を「床割れ」と誤診断しない。

    どちらの失敗も npm は非0で終了するので、終了コードでは区別できない。
    環境未導入は拒否したまま(通さない)、メッセージだけが `npm install` を指す。
    """

    JEST_NOT_FOUND = "sh: 1: jest: not found"

    def stage_web_change(self):
        git(["add", self.write("apps/web/src/foo.ts", "export const foo = 1\n")], cwd=self.tmp)

    def test_missing_node_modules_is_rejected_with_install_guidance(self):
        shutil.rmtree(os.path.join(self.tmp, "apps", "web", "node_modules"))
        self.stage_web_change()
        r = self.commit("feat: touch web", npm_exit_code=127, npm_output=self.JEST_NOT_FOUND)
        out = r.stdout + r.stderr
        self.assertNotEqual(0, r.returncode, "環境未導入のコミットが通ってしまった: " + out)
        self.assertIn("npm install", out)
        self.assertNotIn("テストを追加", out)
        self.assertNotIn("下回っています", out)

    def test_command_not_found_output_is_rejected_with_install_guidance(self):
        """node_modules はあるが jest が実行できない(壊れた導入)場合も同じ診断にする。"""
        self.stage_web_change()
        r = self.commit("feat: touch web", npm_exit_code=127, npm_output=self.JEST_NOT_FOUND)
        out = r.stdout + r.stderr
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("npm install", out)
        self.assertNotIn("テストを追加", out)

    def test_genuine_coverage_failure_still_reports_the_coverage_message(self):
        self.stage_web_change()
        r = self.commit(
            "feat: touch web",
            npm_exit_code=1,
            npm_output="Jest: Coverage for branches (30%) does not meet global threshold (40%)",
        )
        out = r.stdout + r.stderr
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("下回っています", out)
        self.assertIn("テストを追加", out)
        self.assertNotIn("npm install", out)


class WebCoverageFloorLinkedWorktree(TempRepo):
    """#1040 の QA FAIL: linked worktree からのコミットが誤ってメイン作業ツリーの
    apps/web を検査してしまう(#1319 と同じ欠陥、修正はこの Issue #1040 の側で行う)。

    `check_web_coverage_floor` が `web_dir` を、フックスクリプト自身の物理配置
    (`os.path.dirname(__file__)`)から導出していると、`core.hooksPath` がメイン作業ツリーの
    絶対パスを指す構成(本リポジトリの実環境がまさにこれ)では、linked worktree から
    コミットしても常にメイン作業ツリーの物理ファイルが実行され、`__file__` は常にメインを
    指す。このクラスはその構成を明示的に再現する。
    """

    def setUp(self):
        super().setUp()
        # setup-git-hooks.sh は core.hooksPath を相対値で設定する(scripts/setup-git-hooks.sh
        # 参照)。しかし本リポジトリの実環境の core.hooksPath は絶対パスであり(`.git/config`
        # で確認済み)、その構成でのみ本欠陥は再現する。ここで明示的に絶対パスへ書き換え、
        # 実環境の条件を temp repo でも再現する。
        git(
            ["config", "core.hooksPath", os.path.join(self.tmp, "scripts", "git-hooks")],
            cwd=self.tmp,
        )
        self.worktree = self.tmp + "-worktree"
        r = git(["worktree", "add", self.worktree, "-b", "feat"], cwd=self.tmp)
        assert r.returncode == 0, r.stdout + r.stderr

    def tearDown(self):
        shutil.rmtree(self.worktree, ignore_errors=True)
        super().tearDown()

    def commit_in_worktree(self, message, npm_exit_code=0):
        rel = "apps/web/src/from_worktree.ts"
        full = os.path.join(self.worktree, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write("export const bar = 1\n")
        git(["add", rel], cwd=self.worktree)
        env = {
            "PATH": self.bin + os.pathsep + os.environ.get("PATH", ""),
            "FAKE_NPM_LOG": self.npm_log,
            "FAKE_NPM_EXIT_CODE": str(npm_exit_code),
            "FAKE_NPM_OUTPUT": "",
        }
        return git(["commit", "-m", message], cwd=self.worktree, env=env)

    def test_worktree_commit_runs_npm_inside_its_own_apps_web(self):
        """linked worktree からのコミットは、その worktree 自身の apps/web を検査対象にする。

        fake npm はどのディレクトリで呼ばれても env の終了コードをそのまま返すだけなので、
        終了コードの一致だけでは「正しい apps/web を見ているか」を検証できない
        (メインの apps/web を検査しても、たまたま同じ終了コードで一致してしまう)。
        fake npm が記録した実行時の cwd そのものを、worktree 自身の apps/web と比較する。
        """
        self.commit_in_worktree("feat: touch web from worktree", npm_exit_code=0)
        log = self.npm_invocations()
        self.assertIsNotNone(log, "npm が一度も呼ばれなかった")
        expected_web_dir = os.path.join(self.worktree, "apps", "web")
        self.assertTrue(
            log.strip().endswith(expected_web_dir),
            "worktree 自身の apps/web ではなく別の場所で npm が呼ばれている: %r (期待: %s)"
            % (log, expected_web_dir),
        )

    def test_worktree_commit_with_failing_coverage_is_rejected_for_its_own_apps_web(self):
        """メインの apps/web が健全でも、worktree 自身の apps/web の床割れは拒否される。

        現在の欠陥では、worktree からのコミットは(メインの apps/web を検査してしまうため)
        メインの状態次第で受理されてしまう。この検査はメインの apps/web が健全
        (npm_exit_code=0 相当)であるという前提を明示するため、まずメイン作業ツリーで
        床が健全であることを確認し、それでも worktree 側の変更はそのworktree自身の
        床判定(exit 1)で拒否されることを確認する。
        """
        # メイン作業ツリー側は健全(床を割っていない)ことを明示しておく。
        git(
            ["add", self.write("apps/web/src/main_side.ts", "export const ok = 1\n")],
            cwd=self.tmp,
        )
        healthy = self.commit("feat: touch web on main worktree", npm_exit_code=0)
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)

        # worktree 側は床を割った変更としてコミットを試みる。
        r = self.commit_in_worktree("feat: touch web from worktree", npm_exit_code=1)
        self.assertNotEqual(
            0,
            r.returncode,
            "worktree 自身の床割れが、メインの apps/web が健全なせいで素通りした: "
            + r.stdout
            + r.stderr,
        )


if __name__ == "__main__":
    unittest.main()
