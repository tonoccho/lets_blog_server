#!/usr/bin/env python3
"""`scripts/git-hooks/pre-merge-commit` が、コンフリクトなしの通常の `git merge` が持ち込む
テストの黙殺(検査2)を拒否することの検証(#1460)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`scripts/test_pre_merge_commit_unclassified.py` と同じ、文書化された例外
(CLAUDE.md → Test-First Implementation)。対象は git フックの挙動で、製品の画面には現れない。

## 判定基準(#1460 Requirement 3)

取り込みでステージされた差分の追加行に黙殺パターンがあり、かつ**マージ前の HEAD 側の同じパス**に
そのパターンが無いときだけ拒否する。HEAD 側に既にある記述を含む行を取り込み側が編集しただけなら
拒否しない。`pre-merge-commit` 実行時点では `MERGE_HEAD` が無いので、`pre-commit`(コンフリクト経路、
#1125)の「MERGE_HEAD 側にあれば許す」は使えない。

## 検査4(apps/web カバレッジ床)は走らせない(利用者の判断、2026-10-01)

偽の `npm` を `PATH` 先頭に置き、`apps/web` を変更する取り込みでも呼び出し記録が空であることを確かめる
(`scripts/test_check_web_coverage_floor.py` と同じ方式)。

## RED になる仕組み

修正前の `pre-merge-commit` は検査5しか走らせないので、黙殺を持ち込むマージが rc=0 で通り、
「拒否されるべきなのに拒否されない」という AssertionError で失敗する。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

PRE_COMMIT_SRC = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-commit")
PRE_MERGE_COMMIT_SRC = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-merge-commit")

FAKE_NPM = """#!/bin/sh
echo "$@" >> "$FAKE_NPM_LOG"
exit 1
"""

TEST_FILE = "services/foo/src/test/java/FooTest.java"
PROD_FILE = "services/foo/src/main/java/Foo.java"
# 検査2が自分自身のテストを拒否しないよう、リテラルを連結で組み立てる(test_pre_commit_merge_commit.py と同じ)。
DIS = "@" + "Disabled"
IGN = "@" + "Ignore"
WEB_FILE = "apps/web/src/a.ts"


def git(args, cwd, env=None):
    full_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    if env:
        full_env.update(env)
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, env=full_env, timeout=60)


class PreMergeCommitSilencers(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        for d in ("scripts/git-hooks", ".claude/hooks", "apps/web/node_modules"):
            os.makedirs(os.path.join(self.tmp, d))
        for name in ("pre-commit", "pre-merge-commit"):
            dst = os.path.join(self.tmp, "scripts", "git-hooks", name)
            shutil.copy(os.path.join(REPO_ROOT, "scripts", "git-hooks", name), dst)
            os.chmod(dst, 0o755)
        for name in ("paths.py", "silencers.py"):
            shutil.copy(
                os.path.join(REPO_ROOT, ".claude", "hooks", name),
                os.path.join(self.tmp, ".claude", "hooks", name),
            )
        git(["init", "-q", "-b", "main"], self.tmp)
        git(["config", "user.email", "t@example.com"], self.tmp)
        git(["config", "user.name", "t"], self.tmp)
        self.raw_commit("init", {"README.md": "x\n", "apps/web/package.json": "{}\n"})
        git(["config", "core.hooksPath", "scripts/git-hooks"], self.tmp)

        self.bin = os.path.join(self.tmp, "fakebin")
        os.makedirs(self.bin)
        self.npm_log = os.path.join(self.tmp, "npm.log")
        npm = os.path.join(self.bin, "npm")
        with open(npm, "w", encoding="utf-8") as f:
            f.write(FAKE_NPM)
        os.chmod(npm, os.stat(npm).st_mode | stat.S_IEXEC)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, rel, text):
        full = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
        git(["add", rel], self.tmp)

    def raw_commit(self, message, files):
        for rel, text in files.items():
            self.write(rel, text)
        r = git(["-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", message], self.tmp)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def diverge(self, other_files, own_files=None):
        """`other` に other_files を、`main` に own_files(既定は中立の docs 変更)を積む。"""
        git(["checkout", "-q", "-b", "other"], self.tmp)
        self.raw_commit("other", other_files)
        git(["checkout", "-q", "main"], self.tmp)
        self.raw_commit("own", own_files or {"docs/own.md": "own\n"})

    def merge(self):
        env = {"PATH": self.bin + os.pathsep + os.environ.get("PATH", ""), "FAKE_NPM_LOG": self.npm_log}
        r = git(["merge", "--no-ff", "--no-edit", "other"], self.tmp, env=env)
        r.stdout += r.stderr
        return r

    # AC1: 取り込み側が新しい黙殺を持ち込み、HEAD 側に無い → 拒否
    def test_merge_introducing_new_silencer_in_new_file_is_rejected(self):
        self.diverge({TEST_FILE: DIS + "\nclass FooTest {}\n"})
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(DIS, r.stdout)
        self.assertIn(TEST_FILE, r.stdout)

    def test_merge_adding_silencer_to_file_that_exists_on_head_without_it_is_rejected(self):
        self.raw_commit("base", {TEST_FILE: "class FooTest {}\n"})
        self.diverge({TEST_FILE: DIS + "\nclass FooTest {}\n"})
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(TEST_FILE, r.stdout)

    def test_rejection_message_says_merge_aborted_and_result_stays_in_index(self):
        self.diverge({TEST_FILE: DIS + "\nclass FooTest {}\n"})
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn("マージ", r.stdout)
        self.assertIn("index", r.stdout)
        staged = git(["diff", "--cached", "--name-only"], self.tmp).stdout
        self.assertIn(TEST_FILE, staged)

    def test_silencer_in_production_file_is_rejected_too(self):
        self.diverge({PROD_FILE: "// " + IGN + "\nclass Foo {}\n"})
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)

    # AC2(a): develop 相当(プロダクション+テスト)を中立だけの作業ブランチへ → 通る
    def test_legit_prod_and_test_merge_into_neutral_only_branch_succeeds(self):
        self.diverge(
            {PROD_FILE: "class Foo {}\n", TEST_FILE: "class FooTest {}\n"},
            {".claude/note.md": "n\n", "docs/n.md": "n\n"},
        )
        r = self.merge()
        self.assertEqual(0, r.returncode, r.stdout)

    # AC2(b): HEAD 側に既にある黙殺を含む行を取り込み側が編集 → 通る
    def test_incoming_edit_of_line_with_preexisting_silencer_succeeds(self):
        self.raw_commit("base", {TEST_FILE: DIS + '("old")\nclass FooTest {}\n'})
        self.diverge({TEST_FILE: DIS + '("new reason")\nclass FooTest {}\n'})
        r = self.merge()
        self.assertEqual(0, r.returncode, r.stdout)

    def test_merge_of_neutral_only_changes_succeeds(self):
        git(["checkout", "-q", "-b", "other"], self.tmp)
        git(["checkout", "-q", "main"], self.tmp)
        self.raw_commit("own", {"docs/own.md": "own\n"})
        git(["checkout", "-q", "other"], self.tmp)
        self.raw_commit("o", {"docs/o.md": "o\n"})
        git(["checkout", "-q", "main"], self.tmp)
        self.assertEqual(0, self.merge().returncode)

    # AC3: apps/web を変更する取り込みでも npm を起動しない
    def test_merge_touching_apps_web_does_not_invoke_npm(self):
        self.diverge({WEB_FILE: "export const a = 1;\n"})
        r = self.merge()
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertFalse(os.path.exists(self.npm_log), "pre-merge-commit が npm を起動した")

    # pre-commit(コンフリクト経路)の挙動は変えない: MERGE_HEAD 側の記述は許す(#1125)
    def test_pre_commit_conflict_route_still_allows_silencer_present_on_merge_head(self):
        self.diverge({TEST_FILE: DIS + "\nclass FooTest {}\n"})
        git(["merge", "--no-ff", "--no-commit", "other"], self.tmp)
        r = git(["commit", "-m", "merge"], self.tmp)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
