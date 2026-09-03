"""リポジトリの規約・手順書・設定が GitLab の実態を指していることを検証する(#1025, #1027)。

## なぜ機械的に検査するのか

エージェントは `.claude/` の記述を読んで動く。記述が GitHub を指していれば、
存在しないコマンドを叩き、存在しない概念を探す。

しかもこのリポジトリでは、それが**無害な失敗にならない**。移行元の GitHub
リポジトリ `tonoccho/lets_blog_server` は private のまま現存しており、`gh` に
認証が通っている環境では、スキルの指示どおり**移行元を読み書きしてしまう**。
GitLab 側は何も変わらないので、気づくのも遅れる。

置換漏れを目視に頼らないために、ここで固定する。

    python3 -m unittest discover -s .claude/hooks -t .claude/hooks -p 'test_*.py'
"""

import os
import re
import subprocess
import sys
import unittest

HOOKS_DIR = os.path.dirname(os.path.abspath(__file__))
CLAUDE_DIR = os.path.dirname(HOOKS_DIR)
REPO_ROOT = os.path.dirname(CLAUDE_DIR)


def claude_docs():
    """`.claude/` 配下の Markdown(ルール・スキル・エージェント)のパス一覧。"""
    out = subprocess.run(
        ["git", "ls-files", ".claude"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split()
    return [p for p in out if p.endswith(".md")]


def read(path):
    with open(os.path.join(REPO_ROOT, path), encoding="utf-8") as f:
        return f.read()


# `gh` の**コマンド呼び出し**。散文中の "GitHub" や、経緯を説明するための引用とは
# 区別する。判定するのは「エージェントがそのまま実行しうる形」かどうか。
GH_INVOCATION = re.compile(r"\bgh\s+(pr|issue|api|repo|project|auth|search|release|run|workflow)\b")

# 移行後に残っていてはならない用語。値は代わりに使うべき語。
STALE_TERMS = {
    r"\bGitHub Issue\b": "GitLab Issue",
    r"\bGitHub Project\b": "GitLab のボード / status:: ラベル",
}

# 経緯の説明として `gh` に言及してよいファイル。#1029 で「なぜ判定を変えたか」を
# 残す方針を採ったため、フックとそのテストは除外する(CLAUDE.md → Learning Loop)。
HISTORY_EXEMPT = {
    ".claude/hooks/test_guard.py",
    ".claude/hooks/guard.py",
}


class NoGitHubCommands(unittest.TestCase):
    """`.claude/` の手順書が `gh` を実行させないこと。"""

    def test_skills_and_agents_have_no_gh_invocations(self):
        offenders = []
        for path in claude_docs():
            if path in HISTORY_EXEMPT:
                continue
            for i, line in enumerate(read(path).splitlines(), 1):
                if GH_INVOCATION.search(line):
                    offenders.append("%s:%d  %s" % (path, i, line.strip()[:90]))
        self.assertEqual(
            [], offenders, "gh コマンドの指示が残っている:\n" + "\n".join(offenders)
        )

    def test_glab_is_actually_used(self):
        """置換の結果、glab の手順が実在すること(単に消しただけではないこと)。"""
        merged = "\n".join(read(p) for p in claude_docs())
        for needed in ("glab mr create", "glab mr merge", "glab issue"):
            self.assertIn(needed, merged, "%s の手順が見当たらない" % needed)


class GitLabTerminology(unittest.TestCase):
    """用語が GitLab のものであること。"""

    def test_no_stale_github_nouns(self):
        offenders = []
        for path in claude_docs():
            if path in HISTORY_EXEMPT:
                continue
            text = read(path)
            for pattern, replacement in STALE_TERMS.items():
                for i, line in enumerate(text.splitlines(), 1):
                    if re.search(pattern, line):
                        offenders.append(
                            "%s:%d  %s  → %s" % (path, i, line.strip()[:70], replacement)
                        )
        self.assertEqual(
            [], offenders, "GitHub 由来の用語が残っている:\n" + "\n".join(offenders)
        )

    def test_pull_request_is_called_merge_request(self):
        """MR を Pull Request と呼んでいないこと。

        GitLab の呼称は Merge Request で、番号も `!N` と別空間になる。
        呼称がずれていると、Issue の `#N` と混同する。
        """
        offenders = []
        for path in claude_docs():
            if path in HISTORY_EXEMPT:
                continue
            for i, line in enumerate(read(path).splitlines(), 1):
                if re.search(r"\bPull Requests?\b", line):
                    offenders.append("%s:%d  %s" % (path, i, line.strip()[:90]))
        self.assertEqual(
            [], offenders, "Pull Request の呼称が残っている:\n" + "\n".join(offenders)
        )


class StatusLabels(unittest.TestCase):
    """ステータス操作が `status::` ラベルとして書かれていること。"""

    def test_status_labels_are_named(self):
        """ワークフローを駆動するスキルが、実際のラベル名に触れていること。

        「`Backlog → Ready` に変更する」という散文だけでは、GitLab 上で何をすれば
        よいかが決まらない。GitHub Projects の Status フィールドと違い、GitLab CE では
        ラベルの付け替えであり、しかも排他性が保証されない(#1023)。
        """
        driving = [
            ".claude/skills/work-next/SKILL.md",
            ".claude/skills/ready-issue/SKILL.md",
            ".claude/skills/triage-backlog/SKILL.md",
            ".claude/skills/complete-issue/SKILL.md",
        ]
        for path in driving:
            with self.subTest(path=path):
                self.assertIn("status::", read(path), "%s が status:: ラベルに触れていない" % path)


if __name__ == "__main__":
    unittest.main()


class NoDeadCiConfiguration(unittest.TestCase):
    """動かない見込みの CI 設定を残さないこと(#1027)。

    GitHub Actions は移行前から意図的に無効化されていたため、`.github/workflows/` は
    「有効化すれば動く定義」だった。移行後は**動かないプラットフォーム向けの定義**に
    変わっており、意味が違う。README のバッジに至っては、存在しないリポジトリの
    存在しないワークフローの状態を指している。

    GitLab CI は稼働させない(2026-09-03、利用者決定。Runner 0台)。したがって
    ここで固定するのは「GitLab CI に移した」ことではなく、
    **死んだ設定が残っていない**ことである。
    """

    def test_no_github_actions_workflows(self):
        self.assertFalse(
            os.path.exists(os.path.join(REPO_ROOT, ".github")),
            ".github/ が残っている。GitHub Actions は動かない",
        )

    def test_readme_has_no_dead_badges(self):
        """バッジは、存在しないワークフローと止まった収集の状態を指している。"""
        with open(os.path.join(REPO_ROOT, "README.md"), encoding="utf-8") as f:
            readme = f.read()
        for dead in (
            "github.com/tonoccho/lets_blog_server/actions",
            "codecov.io/gh/tonoccho",
        ):
            with self.subTest(badge=dead):
                self.assertNotIn(dead, readme, "死んだバッジが残っている: %s" % dead)

    def test_paths_no_longer_declares_github_neutral(self):
        """`.github/` を消したら、その分類宣言も消す。

        残しておくと、`.github/` を再び置いたときに「意図して中立にした」ものとして
        黙って通ってしまう。分類は都度決める、というのが paths.py の設計方針である。
        """
        sys.path.insert(0, HOOKS_DIR)
        import paths

        self.assertFalse(paths.is_declared_neutral(".github/workflows/x.yml"))

    def test_quality_gates_are_documented_somewhere(self):
        """CI が無いなら、代わりに何が品質を担保しているかが書かれていること。

        「CI が無い」はリポジトリを見た人が推測すべきことではない。
        """
        with open(os.path.join(REPO_ROOT, "README.md"), encoding="utf-8") as f:
            readme = f.read()
        self.assertIn("scripts/git-hooks/pre-commit", readme)
        self.assertIn("check-changed-coverage.py", readme)

