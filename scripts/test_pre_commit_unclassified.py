#!/usr/bin/env python3
"""`scripts/git-hooks/pre-commit` が未分類パスのコミットを拒否すること(#1452)の検証。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## 背景

`.claude/hooks/test_paths.py` の `RepositoryExhaustiveness` は「追跡中の全ファイルが
テスト / プロダクション / 列挙済みの中立のいずれかである」ことを事後的に検査するが、
これはコミットが `develop` に取り込まれた後にしか働かない。直下にスクリプトが増えるたびに
同じ形で develop が赤くなる再発(#1208 → #1321 → #1452)を防ぐため、コミット時点で
未分類パスを拒否する層をここに足す(CLAUDE.md → 「ちょうど1つ」の強制が2層である理由、
と同じ構成)。

## なぜ Gherkin ではないのか

対象は git フックの挙動であって、製品の画面には現れない。`scripts/test_pre_commit_merge_commit.py`
と同じ文書化された例外(CLAUDE.md → Test-First Implementation)として、スクリプトレベルの
テストで表現する。この使い捨てリポジトリ雛形もそこから踏襲した。

## マージコミットを免除しない理由

`check_phase_separation` はマージコミットを免除する(#1125) —
マージ中の `git diff --cached` は取り込む側の全コミット分の差分になり、テストと
プロダクションが混在するのが**通常**だから。未分類パスの検査はこれと事情が違う:
マージで未分類パスが取り込まれることは正常な事象ではなく、まさに本Issue(#1208 → #1321 → #1452)
が繰り返し踏んできた欠陥そのものである。ここでマージコミットを免除すると、
ローカルでの衝突解決マージが同じ抜け道になってしまうため、免除しない。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

PROD = "services/project-service/src/main/java/A.java"
UNCLASSIFIED = "fixture.sh"
OTHER_UNCLASSIFIED = "another-fixture.sh"


def git(args, cwd):
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, env=env, timeout=60)


class PreCommitUnclassified(unittest.TestCase):
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

    def commit(self):
        r = git(["commit", "-m", "commit"], self.tmp)
        r.stdout += r.stderr  # git はフックの標準出力を標準エラーへ回す
        return r

    # 受入基準4
    def test_single_unclassified_path_is_rejected_and_named(self):
        self.write(UNCLASSIFIED, "echo hi\n")
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(UNCLASSIFIED, r.stdout)
        # 受入基準4(a): 通常コミット(マージ中でない)ではマージ用ヒント文を出さない。
        # `scripts/test_pre_merge_commit_unclassified.py` の対になる assertIn と対にして、
        # ヒントの出現条件がマージ中に限られることを固定する。
        self.assertNotIn("マージコミットです", r.stdout)

    def test_unclassified_path_mixed_with_classified_path_is_still_rejected(self):
        self.write(PROD, "class A {}\n")
        self.write(UNCLASSIFIED, "echo hi\n")
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(UNCLASSIFIED, r.stdout)
        # プロダクション側は分類済みなので、拒否理由に含めない。
        self.assertNotIn(PROD, r.stdout)

    def test_classified_paths_alone_are_not_rejected_by_this_check(self):
        self.write(PROD, "class A {}\n")
        r = self.commit()
        self.assertEqual(0, r.returncode, r.stdout)

    # レビュー指摘(2026-09-27、1回目): 唯一の「拒否してはならない」テストが
    # プロダクションパスしかステージしておらず、`is_declared_neutral(p)` を
    # `check_unclassified` の条件から落とす変異を注入しても検出できなかった
    # (フック suite 38件が全て緑のまま)。宣言済み中立パスのみをステージする
    # ケースをここに独立させ、その穴を塞ぐ。
    def test_declared_neutral_paths_alone_are_not_rejected_by_this_check(self):
        self.write("docs/note.md", "note\n")
        r = self.commit()
        self.assertEqual(0, r.returncode, r.stdout)

    def test_merge_commit_introducing_unclassified_path_is_also_rejected(self):
        git(["checkout", "-q", "-b", "other"], self.tmp)
        self.raw_commit("other", {OTHER_UNCLASSIFIED: "echo hi\n"})
        git(["checkout", "-q", "main"], self.tmp)
        self.raw_commit("own", {"own.txt": "own\n"})
        r = git(["merge", "--no-ff", "--no-commit", "other"], self.tmp)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        r = self.commit()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(OTHER_UNCLASSIFIED, r.stdout)


if __name__ == "__main__":
    unittest.main()
