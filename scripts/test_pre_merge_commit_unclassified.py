#!/usr/bin/env python3
"""`scripts/git-hooks/pre-merge-commit` が、コンフリクトなしの通常の `git merge` が持ち込む
未分類パスを拒否することの検証(#1452、QA FAIL の是正)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## 背景 — 経路が1本足りなかった

`scripts/test_pre_commit_unclassified.py` の
`test_merge_commit_introducing_unclassified_path_is_also_rejected` は
`git merge --no-ff --no-commit` の後に明示 `git commit` する経路しか検査していなかった。
git はコミット経路ごとに**別のフック**を起動する(`man githooks`) —
`pre-commit` は `git commit` と、コンフリクトが起きたマージを手で解決した後の明示コミットしか
拾わない。**コンフリクトなしの `git merge`(= 最も普通の結果)は `pre-merge-commit` の担当**で、
このフックが `scripts/git-hooks/` に存在しなければ何も起きない(QA #1452 が実測: `rc=0`、
フックの出力なし)。このファイルはその経路を検査する。

## なぜ Gherkin ではないのか

`scripts/test_pre_commit_unclassified.py` と同じ、文書化された例外
(CLAUDE.md → Test-First Implementation)。対象は git フックの挙動であって、
製品の画面には一切現れない。

## RED になる仕組み

`PRE_MERGE_COMMIT_SRC` がまだ存在しない間、`setUp` はそれをコピーしない
(存在しないファイルを `shutil.copy` で無理にコピーしてクラッシュさせるのではなく、
「フックが `scripts/git-hooks/` に無い」という実際の状態をそのまま再現する)。
git はディレクトリに無いフックを黙って飛ばすので、以下のテストは「拒否されるべきなのに
拒否されない」という形の `AssertionError` で失敗する — これが求める RED である。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

PRE_COMMIT_SRC = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-commit")
PRE_MERGE_COMMIT_SRC = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-merge-commit")

UNCLASSIFIED = "fixture.sh"


def git(args, cwd):
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, env=env, timeout=60)


class PreMergeCommitUnclassified(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        for d in ("scripts/git-hooks", ".claude/hooks"):
            os.makedirs(os.path.join(self.tmp, d))
        shutil.copy(PRE_COMMIT_SRC, os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"))
        os.chmod(os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"), 0o755)
        if os.path.isfile(PRE_MERGE_COMMIT_SRC):
            dst = os.path.join(self.tmp, "scripts", "git-hooks", "pre-merge-commit")
            shutil.copy(PRE_MERGE_COMMIT_SRC, dst)
            os.chmod(dst, 0o755)
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

    def branch_with_unclassified_file(self):
        """`other` に未分類パスを持たせ、`main` 側にも別の分類済み変更を積む。"""
        git(["checkout", "-q", "-b", "other"], self.tmp)
        self.raw_commit("other", {UNCLASSIFIED: "echo hi\n"})
        git(["checkout", "-q", "main"], self.tmp)
        self.raw_commit("own", {"own.txt": "own\n"})

    def merge(self):
        """コンフリクトなしの通常の `git merge`(自動コミット。`--no-commit` を使わない)。"""
        r = git(["merge", "--no-edit", "other"], self.tmp)
        r.stdout += r.stderr  # git はフックの標準出力を標準エラーへ回す
        return r

    # 受入基準4: コンフリクトなしの git merge 経路でも拒否され、パスが名指しされる
    def test_plain_merge_introducing_unclassified_path_is_rejected_and_named(self):
        self.branch_with_unclassified_file()
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn(UNCLASSIFIED, r.stdout)

    # 受入基準4(a): 拒否メッセージにマージ用ヒント文が出る(レビュー2回目の M7)
    def test_plain_merge_rejection_includes_merge_hint(self):
        self.branch_with_unclassified_file()
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn("マージコミットです", r.stdout)

    # 受入基準4(b): 脱出路 — 中断後、同じ index に paths.py の分類を足せば git commit で解決できる
    def test_paths_py_classification_added_after_aborted_merge_lets_commit_succeed(self):
        self.branch_with_unclassified_file()
        r = self.merge()
        self.assertNotEqual(0, r.returncode, r.stdout)

        # `pre-merge-commit` が非0で終わっても、git はマージ結果を index に残したまま
        # コミット作成だけを中断する(`man githooks`)。MERGE_HEAD が残っていることを
        # 確認してから、その状態のまま解決する。
        merge_head = git(["rev-parse", "-q", "--verify", "MERGE_HEAD"], self.tmp)
        self.assertEqual(
            0, merge_head.returncode, "MERGE_HEAD が無い: 中断のはずがマージ状態ごと失われている"
        )

        paths_py = os.path.join(self.tmp, ".claude", "hooks", "paths.py")
        with open(paths_py, encoding="utf-8") as f:
            content = f.read()
        anchor = 'r"^startup\\.sh$",\n]'
        self.assertIn(anchor, content, "paths.py の NEUTRAL_PATTERNS の構造が前提と異なる")
        content = content.replace(anchor, 'r"^startup\\.sh$",\n    r"^%s$",\n]' % UNCLASSIFIED, 1)
        with open(paths_py, "w", encoding="utf-8") as f:
            f.write(content)
        git(["add", ".claude/hooks/paths.py"], self.tmp)

        # MERGE_HEAD が残っているので、この `git commit` はマージコミットとして扱われ、
        # (pre-merge-commit ではなく)pre-commit が起動する。
        r2 = git(["commit", "-m", "merge: classify fixture.sh"], self.tmp)
        r2.stdout += r2.stderr
        self.assertEqual(0, r2.returncode, r2.stdout)


if __name__ == "__main__":
    unittest.main()
