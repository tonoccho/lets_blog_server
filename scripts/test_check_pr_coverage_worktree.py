"""`.claude/hooks/guard.py` の `check_pr_coverage` が worktree を正しく扱うことの単体テスト(#1229)。

## 背景

`guard.py` の `glab mr create` ガードは、カバレッジ検査の対象ディレクトリ(`root`)を
`CLAUDE_PROJECT_DIR` / セッションの cwd から決めていた。`git worktree` から
`glab mr create` を実行すると、判定は **worktree ではなくメインの作業ツリーの HEAD** に
対して行われる — メインツリーがどのブランチに乗っていても関係なく、その状態が
判定を左右してしまう(逆方向の誤りも成立する: メインツリーがカバレッジ基準を
満たすブランチに乗っていれば、実際には満たさない worktree 側の MR がそのまま通る)。

## なぜ `scripts/test_*.py` で、実際に `git worktree add` するのか

この Issue の受入基準5がそう指定している。判定は「どのディレクトリの git 状態を見るか」
という、実際の `git worktree` の挙動そのものに依存する問題なので、モックした
`git rev-parse` では検証にならない。したがって一時ディレクトリに本物の git リポジトリを
作り、本物の `git worktree add` で2つ目の作業ツリーを用意して検証する。

`scripts/check-changed-coverage.py` 自体はここでは検証しない(それは
`scripts/test_check_changed_coverage.py` の役割)。ここで確かめるのは
**`guard.py` がどのディレクトリの検査結果を採用するか** だけなので、
`scripts/check-changed-coverage.py` は「カレントディレクトリの `COVERAGE_STATUS`
ファイルの中身をそのまま終了コードにする」という最小の偽物に置き換える。
ブランチごとにこのファイルの内容を変えておけば、どのブランチの作業ツリーが
実際に検査されたかを終了コード経由でそのまま観測できる。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
GUARD = os.path.join(REPO_ROOT, ".claude", "hooks", "guard.py")

FAKE_COVERAGE_SCRIPT = (
    "import sys\n"
    "try:\n"
    "    with open('COVERAGE_STATUS') as f:\n"
    "        code = int(f.read().strip())\n"
    "except OSError:\n"
    "    code = 1\n"
    "print('coverage-status=%d' % code)\n"
    "sys.exit(code)\n"
)


def _git(args, cwd):
    subprocess.run(["git"] + args, cwd=cwd, check=True, capture_output=True, text=True)


def _write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _commit_all(cwd, message):
    _git(["add", "."], cwd)
    _git(["commit", "-q", "-m", message], cwd)


def run_hook(mode, payload):
    """guard.py を起動し、(拒否理由 or None) を返す。"""
    proc = subprocess.run(
        [sys.executable, GUARD, mode],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=60,
    )
    if not proc.stdout.strip():
        return None
    out = json.loads(proc.stdout)
    decision = out.get("hookSpecificOutput", {})
    if decision.get("permissionDecision") != "deny":
        return None
    return decision.get("permissionDecisionReason", "")


def bash_payload(command, cwd):
    return {"tool_input": {"command": command}, "session_id": "test-session", "cwd": cwd}


class CoverageWorktreeFixture(unittest.TestCase):
    """本物の git worktree を持つ最小リポジトリを用意する。

    - メインの作業ツリー(`self.main_root`)は `develop` のまま。
    - `docs/good` ブランチの worktree(`self.docs_worktree`)は `COVERAGE_STATUS=0`
      (カバレッジ基準を満たす、production code を触らない変更を想定)。
    - `fix/bad` ブランチの worktree(`self.bad_worktree`)は `COVERAGE_STATUS=1`
      (カバレッジ基準を満たさない変更を想定)。

    どちらのテストも、メインツリー(`develop`)を意図的に worktree 側と逆の状態に
    しておく。メインツリーの状態が判定に紛れ込んでいれば、そのテストは
    誤った結果(逆の許可/拒否)になる。
    """

    def setUp(self):
        self.main_root = tempfile.mkdtemp()
        _git(["init", "-q"], self.main_root)
        _git(["config", "user.email", "t@example.com"], self.main_root)
        _git(["config", "user.name", "t"], self.main_root)
        _git(["checkout", "-q", "-b", "develop"], self.main_root)
        os.makedirs(os.path.join(self.main_root, "scripts"))
        _write(
            os.path.join(self.main_root, "scripts", "check-changed-coverage.py"),
            FAKE_COVERAGE_SCRIPT,
        )
        _write(os.path.join(self.main_root, "README.md"), "root\n")
        _commit_all(self.main_root, "init")

        self.env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        self.addCleanup(self._restore_env)

    def _restore_env(self):
        if self.env_backup is not None:
            os.environ["CLAUDE_PROJECT_DIR"] = self.env_backup

    def _add_worktree(self, branch, coverage_status):
        """`develop` から分岐したブランチを、専用の worktree としてチェックアウトする。"""
        _git(["branch", branch, "develop"], self.main_root)
        worktree_dir = tempfile.mkdtemp()
        # `git worktree add` はターゲットディレクトリを自分で作るので、空の状態にしておく。
        os.rmdir(worktree_dir)
        _git(["worktree", "add", "-q", worktree_dir, branch], self.main_root)
        _write(os.path.join(worktree_dir, "COVERAGE_STATUS"), str(coverage_status))
        _commit_all(worktree_dir, "set coverage status to %s" % coverage_status)
        return worktree_dir


class WorktreeCoverageIsMeasuredIndependently(CoverageWorktreeFixture):
    """受入基準1・2: worktree の判定は、メインの作業ツリーの状態に左右されない。"""

    def test_docs_only_worktree_branch_succeeds_regardless_of_main_tree_branch(self):
        """受入基準1: docs のみのブランチを worktree から MR 作成すると成功する。

        メインツリー(`develop`)は意図的にカバレッジ基準を満たさない状態(`1`)に
        しておく。それでも worktree 側(`docs/good`、`0`)の判定が使われるべきなので、
        許可されなければならない。
        """
        _write(os.path.join(self.main_root, "COVERAGE_STATUS"), "1")
        _commit_all(self.main_root, "main tree fails coverage")

        worktree_dir = self._add_worktree("docs/good", coverage_status=0)

        os.environ["CLAUDE_PROJECT_DIR"] = self.main_root
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab mr create --target-branch develop", cwd=worktree_dir),
            )
        finally:
            os.environ.pop("CLAUDE_PROJECT_DIR", None)

        self.assertIsNone(
            reason,
            "docs のみの worktree ブランチが、メインツリーの状態を理由に拒否された: %s" % reason,
        )

    def test_low_coverage_worktree_branch_is_denied_regardless_of_main_tree_branch(self):
        """受入基準2: production coverage が90%未満の worktree ブランチは拒否される。

        メインツリー(`develop`)は意図的にカバレッジ基準を満たす状態(`0`)に
        しておく。それでも worktree 側(`fix/bad`、`1`)の判定が使われるべきなので、
        拒否されなければならない。
        """
        _write(os.path.join(self.main_root, "COVERAGE_STATUS"), "0")
        _commit_all(self.main_root, "main tree passes coverage")

        worktree_dir = self._add_worktree("fix/bad", coverage_status=1)

        os.environ["CLAUDE_PROJECT_DIR"] = self.main_root
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab mr create --target-branch develop", cwd=worktree_dir),
            )
        finally:
            os.environ.pop("CLAUDE_PROJECT_DIR", None)

        self.assertIsNotNone(
            reason,
            "カバレッジ不足の worktree ブランチが、メインツリーの状態を理由に許可された",
        )
        self.assertIn("fix/bad", reason, "拒否メッセージに計測したブランチ名が含まれていない")


if __name__ == "__main__":
    unittest.main()
