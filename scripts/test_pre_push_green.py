#!/usr/bin/env python3
"""`scripts/git-hooks/pre-push` が「push する時点でブランチが green」を強制することの検証(#1514)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象は git の `pre-push` フック配線で、製品の画面には現れない。`scripts/test_check_web_coverage_floor.py`
や `scripts/test_git_hooks_binding.py` と同じ文書化された例外(CLAUDE.md → Test-First Implementation)
として、スクリプトレベルのテストで表現する。

## 方法

`PATH` の先頭に偽の `npm` を置き(呼び出しと cwd を記録し、終了コードと出力は環境変数で決める)、
使い捨てリポジトリから bare の origin へ本物の `git push` を行う。検証するのは jest の中身ではなく
配線 — 「apps/web を変更する範囲の push のときだけ `npm run test:coverage` が呼ばれ、その終了コードで
push の合否が決まること」である。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
PRE_PUSH = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-push")

ZEROS = "0" * 40

FAKE_NPM = """#!/bin/bash
echo "$@" >> "$FAKE_NPM_LOG"
echo "$PWD" >> "$FAKE_NPM_LOG"
[ -n "$FAKE_NPM_OUTPUT" ] && printf '%b\\n' "$FAKE_NPM_OUTPUT"
exit "$FAKE_NPM_EXIT_CODE"
"""


def git(args, cwd, env=None):
    full_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    if env:
        full_env.update(env)
    return subprocess.run(
        ["git"] + args, cwd=cwd, capture_output=True, text=True, env=full_env, timeout=60
    )


class PrePushRepo(unittest.TestCase):
    def setUp(self):
        base = tempfile.mkdtemp()
        self.base = base
        self.tmp = os.path.join(base, "work")
        self.remote = os.path.join(base, "origin.git")
        os.makedirs(os.path.join(self.tmp, "scripts", "git-hooks"))
        git(["init", "-q", "--bare", "-b", "develop", self.remote], cwd=base)
        git(["init", "-q", "-b", "develop", self.tmp], cwd=base)
        git(["config", "user.email", "t@example.com"], cwd=self.tmp)
        git(["config", "user.name", "t"], cwd=self.tmp)
        git(["remote", "add", "origin", self.remote], cwd=self.tmp)

        self.write("apps/web/package.json", '{"name": "web", "scripts": {}}\n')
        self.write("apps/web/src/old.ts", "export const old = 1\n")
        self.write("README.md", "x\n")
        git(["add", "-A"], cwd=self.tmp)
        git(["commit", "-q", "-m", "init"], cwd=self.tmp)

        self.bin = os.path.join(base, "fakebin")
        os.makedirs(self.bin)
        self.npm_log = os.path.join(base, "npm-invocations.log")
        npm_path = os.path.join(self.bin, "npm")
        with open(npm_path, "w", encoding="utf-8") as f:
            f.write(FAKE_NPM)
        os.chmod(npm_path, os.stat(npm_path).st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

        # フックを束縛する前に develop を origin へ置く(= origin/develop が基準になる)。
        r = git(["push", "-q", "origin", "develop"], cwd=self.tmp)
        assert r.returncode == 0, r.stdout + r.stderr
        self.assertTrue(os.path.isfile(PRE_PUSH), "scripts/git-hooks/pre-push が存在しない")
        dst = os.path.join(self.tmp, "scripts", "git-hooks", "pre-push")
        shutil.copy(PRE_PUSH, dst)
        os.chmod(dst, 0o755)
        git(["config", "core.hooksPath", "scripts/git-hooks"], cwd=self.tmp)

    def tearDown(self):
        shutil.rmtree(self.base, ignore_errors=True)

    def write(self, rel_path, text):
        full = os.path.join(self.tmp, rel_path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
        return rel_path

    def commit_file(self, rel_path, text="export const v = 1\n"):
        git(["add", self.write(rel_path, text)], cwd=self.tmp)
        r = git(["commit", "-q", "-m", "change " + rel_path], cwd=self.tmp)
        assert r.returncode == 0, r.stdout + r.stderr

    def push(self, args, npm_exit_code=0, npm_output=""):
        env = {
            "PATH": self.bin + os.pathsep + os.environ.get("PATH", ""),
            "FAKE_NPM_LOG": self.npm_log,
            "FAKE_NPM_EXIT_CODE": str(npm_exit_code),
            "FAKE_NPM_OUTPUT": npm_output,
        }
        return git(["push"] + args, cwd=self.tmp, env=env)

    def npm_invocations(self):
        if not os.path.exists(self.npm_log):
            return None
        with open(self.npm_log, encoding="utf-8") as f:
            return f.read()

    def remote_has(self, branch):
        return git(["rev-parse", "--verify", "--quiet", "refs/heads/" + branch], cwd=self.remote).returncode == 0


class WebChangeIsGatedByCoverage(PrePushRepo):
    """受入基準1・2: apps/web を変える push は green のときだけ受理される。"""

    def test_failing_coverage_rejects_the_push(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        r = self.push(["origin", "feat"], npm_exit_code=1)
        out = r.stdout + r.stderr
        self.assertNotEqual(0, r.returncode, "jest が落ちているのに push が通った: " + out)
        self.assertIn("green", out)
        self.assertFalse(self.remote_has("feat"), "拒否されたのに origin にブランチができた")

    def test_passing_coverage_accepts_the_push(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        r = self.push(["origin", "feat"], npm_exit_code=0)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertTrue(self.remote_has("feat"))
        self.assertIn("run test:coverage", self.npm_invocations() or "")

    def test_npm_runs_in_the_pushing_worktrees_apps_web(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        self.push(["origin", "feat"], npm_exit_code=0)
        log = (self.npm_invocations() or "").strip()
        self.assertTrue(
            os.path.realpath(log.splitlines()[-1]) == os.path.realpath(os.path.join(self.tmp, "apps", "web")),
            "apps/web の外で npm が呼ばれた: %r" % log,
        )

    def test_rejection_message_includes_the_output_tail(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        noise = "".join("noise line %d\\n" % i for i in range(300))
        r = self.push(
            ["origin", "feat"], npm_exit_code=1, npm_output=noise + "Tests: 1 failed, LAST-LINE-MARKER"
        )
        out = r.stdout + r.stderr
        self.assertIn("LAST-LINE-MARKER", out)
        self.assertNotIn("noise line 0\n", out)

    def test_follow_up_push_checks_only_the_pushed_range(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        self.assertEqual(0, self.push(["origin", "feat"]).returncode)
        os.remove(self.npm_log)
        self.commit_file("apps/web/src/bar.ts")
        r = self.push(["origin", "feat"], npm_exit_code=1)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)


class NpmIsNotInvokedWhenUnneeded(PrePushRepo):
    """受入基準2・要件4: apps/web に触れない push とブランチ削除では npm を起動しない。"""

    def test_push_without_web_change_does_not_invoke_npm(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("services/project-service/src/main/kotlin/Foo.kt", "class Foo\n")
        r = self.push(["origin", "feat"], npm_exit_code=1)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNone(self.npm_invocations(), "apps/web を触っていないのに npm が呼ばれた")

    def test_branch_deletion_does_not_invoke_npm(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("services/project-service/src/main/kotlin/Foo.kt", "class Foo\n")
        self.assertEqual(0, self.push(["origin", "feat"]).returncode)
        r = self.push(["origin", ":feat"], npm_exit_code=1)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNone(self.npm_invocations(), "ブランチ削除で npm が呼ばれた")
        self.assertFalse(self.remote_has("feat"))

    def test_new_branch_uses_merge_base_with_origin_develop(self):
        """新規ブランチ(remote sha が全0)は origin/develop との merge-base から数える。

        develop に既にある apps/web の変更は、この push の範囲ではない。
        """
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("README.md", "changed\n")
        r = self.push(["origin", "feat"], npm_exit_code=1)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNone(self.npm_invocations(), "develop 由来の apps/web で npm が呼ばれた")

    def test_new_branch_with_web_change_invokes_npm(self):
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("README.md", "changed\n")
        self.commit_file("apps/web/src/new.ts")
        r = self.push(["origin", "feat"], npm_exit_code=1)
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNotNone(self.npm_invocations())


class WebProjectAbsent(PrePushRepo):
    def test_missing_package_json_is_skipped(self):
        """apps/web が npm プロジェクトでない雛形では判定対象がなく、黙って通す(pre-commit と同じ)。"""
        git(["checkout", "-q", "-b", "feat"], cwd=self.tmp)
        self.commit_file("apps/web/src/foo.ts")
        git(["rm", "-q", "apps/web/package.json"], cwd=self.tmp)
        git(["commit", "-q", "-m", "drop package.json"], cwd=self.tmp)
        r = self.push(["origin", "feat"], npm_exit_code=1)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIsNone(self.npm_invocations())


if __name__ == "__main__":
    unittest.main()
