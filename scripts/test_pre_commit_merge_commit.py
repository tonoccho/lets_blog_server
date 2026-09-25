#!/usr/bin/env python3
"""`scripts/git-hooks/pre-commit` がマージコミットを通常コミットと区別すること(#1125)の検証。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象は git フックの挙動であって、製品の画面には現れない。`scripts/test_git_hooks_binding.py`
と同じ文書化された例外(CLAUDE.md → Test-First Implementation)として、スクリプトレベルの
テストで表現する。

## 構成

使い捨てリポジトリに本物のフックを配線し、`git merge --no-commit` で `MERGE_HEAD` のある
状態を作ってから `git commit` する。準備用のコミットは、フックが拒否する構成(テストと
プロダクションの混在)を意図的に作るため `core.hooksPath` を空にして行う。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

PROD = "services/project-service/src/main/java/A.java"
TEST = "services/project-service/src/test/java/ATest.java"
OTHER_TEST = "services/project-service/src/test/java/BTest.java"
# フック自身がこのファイルを検査するため、黙殺パターンの字面をソースに書かない。
DISABLED_ANN = "@" + "Disabled"
IGNORE_ANN = "@" + "Ignore"
DISABLED = DISABLED_ANN + "\nclass BTest {}\n"


def git(args, cwd):
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, env=env, timeout=60)


class PreCommitMergeCommit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        for d in ("scripts/git-hooks", ".claude/hooks"):
            os.makedirs(os.path.join(self.tmp, d))
        shutil.copy(
            os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-commit"),
            os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"),
        )
        os.chmod(os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"), 0o755)
        for name in ("paths.py", "silencers.py"):
            shutil.copy(
                os.path.join(REPO_ROOT, ".claude", "hooks", name),
                os.path.join(self.tmp, ".claude", "hooks", name),
            )
        git(["init", "-q", "-b", "main"], self.tmp)
        git(["config", "user.email", "t@example.com"], self.tmp)
        git(["config", "user.name", "t"], self.tmp)
        self.raw_commit("init", {"README.md": "x\n"})
        git(["config", "core.hooksPath", "scripts/git-hooks"], self.tmp)

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

    def start_merge(self, other_files, own_files=None):
        """main と分岐した `other` を作り、`main` 上で `--no-commit` マージした状態にする。"""
        git(["checkout", "-q", "-b", "other"], self.tmp)
        self.raw_commit("other", other_files)
        git(["checkout", "-q", "main"], self.tmp)
        self.raw_commit("own", own_files or {"own.txt": "own\n"})
        r = git(["merge", "--no-ff", "--no-commit", "other"], self.tmp)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def commit(self):
        r = git(["commit", "-m", "commit"], self.tmp)
        r.stdout += r.stderr  # git はフックの標準出力を標準エラーへ回す
        return r

    # 受入基準1
    def test_merge_mixing_test_and_production_paths_passes_phase_separation(self):
        self.start_merge({PROD: "class A {}\n", TEST: "class ATest {}\n"})
        r = self.commit()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # 受入基準2
    def test_merge_inheriting_existing_silencer_from_merge_head_passes(self):
        self.start_merge({OTHER_TEST: DISABLED})
        r = self.commit()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    # 受入基準3
    def test_merge_introducing_new_silencer_is_still_rejected(self):
        self.start_merge({PROD: "class A {}\n"})
        self.write(TEST, IGNORE_ANN + "\nclass ATest {}\n")
        r = self.commit()
        self.assertNotEqual(0, r.returncode, "マージ中に持ち込んだ新規の黙殺が通った: " + r.stdout)
        self.assertIn(IGNORE_ANN, r.stdout)

    def test_merge_adding_silencer_to_path_where_merge_head_lacks_that_pattern_is_rejected(self):
        self.start_merge({OTHER_TEST: DISABLED})
        self.write(OTHER_TEST, DISABLED + IGNORE_ANN + "\n")
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)

    # 受入基準4
    def test_plain_commit_mixing_test_and_production_is_rejected(self):
        self.write(PROD, "class A {}\n")
        self.write(TEST, "class ATest {}\n")
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn("同じコミットに混在", r.stdout)

    def test_plain_commit_with_silencer_is_rejected_even_if_other_branch_has_it(self):
        git(["checkout", "-q", "-b", "other"], self.tmp)
        self.raw_commit("other", {OTHER_TEST: DISABLED})
        git(["checkout", "-q", "main"], self.tmp)
        self.write(OTHER_TEST, DISABLED)
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(DISABLED_ANN, r.stdout)


if __name__ == "__main__":
    unittest.main()
