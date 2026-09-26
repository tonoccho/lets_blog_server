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


class SelectionOrderSingleSource(unittest.TestCase):
    """CLAUDE.md → Issue Provenance → Selection order が単一の定義であること(#1433)。

    hotfix を選択順の第0キーとして追加したとき、`work-next` / `ready-issue` /
    `triage-backlog` の SKILL.md が独自にキー一覧を書き直したままでは、CLAUDE.md を
    いくら直しても各段の実際の振る舞いには効かない(#1433 の Problem そのもの)。
    書き直しではなく CLAUDE.md への参照になっていることを機械的に固定する。
    """

    SKILLS = [
        ".claude/skills/work-next/SKILL.md",
        ".claude/skills/ready-issue/SKILL.md",
        ".claude/skills/triage-backlog/SKILL.md",
    ]

    # 選択順のキー一覧を丸ごと書き直した箇条書きの特徴的な形("1. **Provenance" のような、
    # 番号付きリストの先頭に太字でキー名が来る行)。3つ以上一致すれば「書き直し」とみなす。
    KEY_LIST_MARKERS = [
        re.compile(r"^\s*\d+\.\s+\*\*Provenance", re.MULTILINE),
        re.compile(r"^\s*\d+\.\s+\*\*Kind", re.MULTILINE),
        re.compile(r"^\s*\d+\.\s+\*\*Priority", re.MULTILINE),
        re.compile(r"^\s*\d+\.\s+\*\*Is blocking count", re.MULTILINE),
        re.compile(r"^\s*\d+\.\s+\*\*Issue number", re.MULTILINE),
    ]

    # frontmatter description でキーの連鎖を矢印で書き直す形("user-request → bug → Priority")。
    CHAIN_MARKER = re.compile(r"user-request.{0,10}(→|->).{0,10}bug.{0,10}(→|->).{0,10}[Pp]riority")

    def _selection_order_section(self):
        text = read(".claude/CLAUDE.md")
        after = text.split("## Selection order", 1)
        self.assertEqual(2, len(after), "CLAUDE.md に ## Selection order が無い")
        section = after[1].split("\n---", 1)[0]
        return section

    def test_claude_md_defines_hotfix_as_key_zero(self):
        section = self._selection_order_section()
        self.assertIn("hotfix", section)
        self.assertRegex(
            section, r"0\.\s+\*\*`hotfix`", "hotfix が選択順の第0キーとして書かれていない"
        )

    def test_claude_md_hotfix_key_is_newest_first(self):
        """hotfix 同士は Issue 番号の新しい順(他のキーとは逆のタイブレーク)。"""
        section = self._selection_order_section()
        self.assertRegex(section, r"(newest|descending)", "hotfix の降順ルールが書かれていない")

    def test_skills_do_not_restate_the_key_list(self):
        for path in self.SKILLS:
            text = read(path)
            with self.subTest(path=path):
                matched = sum(1 for m in self.KEY_LIST_MARKERS if m.search(text))
                self.assertLess(
                    matched, 3,
                    "%s が選択順のキー一覧を書き直している(%d 個のキーが一致)" % (path, matched),
                )

    def test_ready_issue_frontmatter_does_not_restate_the_chain(self):
        text = read(".claude/skills/ready-issue/SKILL.md")
        frontmatter = text.split("---", 2)[1]
        self.assertNotRegex(
            frontmatter, self.CHAIN_MARKER, "frontmatter description が選択順を書き直している"
        )

    # 見出しがそのまま1行に収まらず折り返されることがあるため("Selection\norder")、
    # 空白1文字だけでなく改行も挟めるようにする。
    SELECTION_ORDER_REFERENCE = re.compile(r"Selection\s+order")

    def test_skills_reference_claude_md_selection_order(self):
        for path in self.SKILLS:
            with self.subTest(path=path):
                self.assertRegex(
                    read(path), self.SELECTION_ORDER_REFERENCE,
                    "%s が CLAUDE.md → Selection order を参照していない" % path,
                )

    def test_triage_backlog_no_longer_claims_priority_selects_first(self):
        """`ready-issue selects by Priority first` は #1433 の Problem が指摘する誤り。

        現在の Selection order では Priority は第3キー(hotfix, user-request, bug の後)。
        """
        text = read(".claude/skills/triage-backlog/SKILL.md")
        self.assertNotIn("selects by Priority first", text)

    def test_work_next_and_triage_backlog_report_hotfix(self):
        """Requirement 4: 各スキルが報告する選定理由に hotfix か否かを含める。"""
        for path in (
            ".claude/skills/work-next/SKILL.md",
            ".claude/skills/triage-backlog/SKILL.md",
        ):
            with self.subTest(path=path):
                self.assertIn("hotfix", read(path), "%s が選定理由に hotfix を含めていない" % path)


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


# ADR は過去の意思決定の記録である。当時 GitHub を使っていた事実は事実として正しく、
# 遡って書き換えてはならない(#1028 Requirement 5)。移行という新しい決定は、
# 既存 ADR の改竄ではなく新しい ADR で述べる。
ADR_DIR = "docs/adr/"


def repo_docs():
    out = subprocess.run(
        ["git", "ls-files", "docs", "*.md"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    return [p for p in out if p.endswith(".md")]


# このリポジトリの `.github/` は削除済みだが、GitHub 側のミラー先には今も存在する
# (#1256)。ミラー運用を説明する行は「削除済みパスを有効な場所として案内する」のではなく
# 「ミラー先の事情を説明する」ものなので、この明示的なマーカーで囲んだ範囲だけを
# 検出対象から外す(#1289)。マーカーの外に `.github/` が出てくれば、これまでどおり検出する。
GITHUB_MIRROR_NOTE_START = "<!-- github-mirror-note:start -->"
GITHUB_MIRROR_NOTE_END = "<!-- github-mirror-note:end -->"


def find_dot_github_references(text):
    """`.github/` を指す行のうち、ミラー注記マーカーの外にあるものを返す。"""
    offenders = []
    in_note = False
    for i, line in enumerate(text.splitlines(), 1):
        if GITHUB_MIRROR_NOTE_START in line:
            in_note = True
            continue
        if GITHUB_MIRROR_NOTE_END in line:
            in_note = False
            continue
        if in_note:
            continue
        if ".github/" in line:
            offenders.append((i, line))
    return offenders


class NoReferencesToDeletedPaths(unittest.TestCase):
    """削除した `.github/` を指す記述が残っていないこと(#1028)。

    #1027 で `.github/` を消したため、これらは**存在しないパスへの案内**になった。
    `docs/COVERAGE_TARGETS.md` には相対リンクもあり、リンク切れになっている。
    GitHub ミラー先の事情を説明する箇所は例外で、`GITHUB_MIRROR_NOTE_START` /
    `_END` マーカーで明示的に囲まれた範囲のみ許される(#1289)。
    """

    def test_docs_do_not_point_at_dot_github(self):
        offenders = []
        for path in repo_docs():
            if path.startswith(ADR_DIR):
                continue  # 過去の記録。書き換えない
            for i, line in find_dot_github_references(read(path)):
                offenders.append("%s:%d  %s" % (path, i, line.strip()[:80]))
        self.assertEqual(
            [], offenders, "削除済みの .github/ を指す記述が残っている:\n" + "\n".join(offenders)
        )

    def test_mirror_note_marker_does_not_weaken_detection(self):
        """マーカーの外にある `.github/` 言及は、これまでどおり検出されること。

        マーカーは「明示的に囲んだ範囲」だけを許す仕組みであって、`.github/` への
        言及そのものを一般に免除するものではないことを固定する(#1289 AC3)。
        """
        unmarked = "GitHub Actions の設定は `.github/workflows/ci.yml` を参照。"
        self.assertEqual(
            [(1, unmarked)], find_dot_github_references(unmarked),
        )

    def test_mirror_note_marker_exempts_only_the_marked_block(self):
        marked = "\n".join([
            "前置きの行。",
            GITHUB_MIRROR_NOTE_START,
            "GitHub 側には `.github/workflows/` が残っている。",
            GITHUB_MIRROR_NOTE_END,
            "後続の行に `.github/` が出てきたら、これは検出されるべき。",
        ])
        offenders = find_dot_github_references(marked)
        self.assertEqual([5], [i for i, _ in offenders])

    def test_docs_do_not_describe_github_only_mechanisms_as_current(self):
        """GitLab に存在しない仕組みを、現に動いているものとして案内しないこと。"""
        stale = ("Dependabot", "CodeQL", "GitHub Actions", "GitHub Security")
        offenders = []
        for path in repo_docs():
            if path.startswith(ADR_DIR):
                continue
            text = read(path)
            for i, line in enumerate(text.splitlines(), 1):
                for term in stale:
                    # 「もう使っていない」と述べる文脈は許す。判別は素朴だが、
                    # 移行の記述であることを明示的に書かせるための線引きである。
                    # 否定の文脈かどうかを素朴なキーワードで見る。当初は日本語の
                    # 表現しか並べておらず、英語で「もう動かない」と書いた行まで
                    # 拾ってしまった(SECURITY.md)。主張は変えず、実際に使う否定表現を
                    # 並べ直す。「Dependabot creates pull requests」のような、
                    # 現行として案内する行は依然として失敗する。
                    if term in line and not any(
                        w in line for w in (
                            "使わない", "動かない", "廃止", "削除", "かつて", "移行前",
                            "使っていない", "無効化", "代替", "得られない", "しか無い",
                            "no longer", "does not run", "There is no", "not automated",
                            "was removed", "removed", "no CI",
                        )
                    ):
                        offenders.append("%s:%d  %s" % (path, i, line.strip()[:80]))
        self.assertEqual(
            [], offenders,
            "GitHub 専用の仕組みが現行として案内されている:\n" + "\n".join(offenders[:20])
        )


class MigrationAdr(unittest.TestCase):
    """移行の判断を ADR に残すこと(#1028 Requirement 6)。"""

    def _adr_files(self):
        return [p for p in repo_docs() if p.startswith(ADR_DIR) and "README" not in p]

    def test_a_migration_adr_exists(self):
        texts = {p: read(p) for p in self._adr_files()}
        matched = [p for p, t in texts.items() if "GitLab" in t and "移行" in t]
        self.assertTrue(matched, "GitLab 移行の ADR が無い")
        self.adr = texts[matched[0]]

    def test_the_adr_records_the_ce_consequences(self):
        """CE で使えないものと、その代替を書くこと。"""
        text = "\n".join(read(p) for p in self._adr_files())
        for topic in ("スコープ付きラベル", "blocked_by", "CI"):
            with self.subTest(topic=topic):
                self.assertIn(topic, text, "%s についての記述が ADR に無い" % topic)

    def test_existing_adrs_are_untouched(self):
        """既存 ADR を書き換えていないこと。

        当時 GitHub を使っていた事実は事実として正しい。
        """
        untouched = subprocess.run(
            ["git", "diff", "--name-only", "origin/develop...HEAD", "--", ADR_DIR],
            cwd=REPO_ROOT, capture_output=True, text=True,
        ).stdout.split()
        modified = [
            p for p in untouched
            if subprocess.run(["git", "cat-file", "-e", "origin/develop:" + p],
                              cwd=REPO_ROOT, capture_output=True).returncode == 0
        ]
        self.assertEqual([], modified, "既存の ADR が変更されている: %s" % modified)


class SkillNames(unittest.TestCase):
    """スキル名も GitLab の用語であること(#1033)。

    #1025 で本文の用語は移行したが、**ディレクトリ名と frontmatter の `name:` は
    検査の対象外だった**。`GitLabTerminology` は Markdown の本文しか見ていない。
    そのため `skills/pull-request/` だけが GitHub の用語で残り、その `description` は
    「GitLab Merge Requests を作る」と書いてある、という食い違いが放置されていた。

    スキル名は利用者が打つ呼び出し名であり、grep でも補完でも目に入る。
    ここを検査に入れないと、同じ種類の取りこぼしが次も起きる。
    """

    def skill_dirs(self):
        out = subprocess.run(
            ["git", "ls-files", ".claude/skills"],
            cwd=REPO_ROOT, capture_output=True, text=True, check=True,
        ).stdout.split()
        return sorted({p.split("/")[2] for p in out if p.count("/") >= 3})

    def test_no_skill_is_named_after_a_github_concept(self):
        stale = ("pull-request", "pull_request", "pr")
        offenders = [d for d in self.skill_dirs() if d in stale]
        self.assertEqual(
            [], offenders, "GitHub の用語を名前に持つスキルがある: %s" % offenders
        )

    def test_frontmatter_name_matches_the_directory(self):
        """`name:` とディレクトリ名がずれていると、どちらで呼ばれるか分からない。"""
        mismatched = []
        for d in self.skill_dirs():
            path = ".claude/skills/%s/SKILL.md" % d
            for line in read(path).splitlines():
                if line.startswith("name:"):
                    declared = line.split(":", 1)[1].strip()
                    if declared != d:
                        mismatched.append("%s (name: %s)" % (d, declared))
                    break
        self.assertEqual([], mismatched, "ディレクトリ名と name: が一致しない: %s" % mismatched)

    def test_no_document_references_the_old_skill_name(self):
        offenders = []
        for path in claude_docs():
            for i, line in enumerate(read(path).splitlines(), 1):
                if "pull-request" in line:
                    offenders.append("%s:%d  %s" % (path, i, line.strip()[:80]))
        self.assertEqual(
            [], offenders, "旧スキル名への参照が残っている:\n" + "\n".join(offenders)
        )

