"""`.claude/` のルールと手順書が GitLab を指していることを検証する(#1025)。

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
