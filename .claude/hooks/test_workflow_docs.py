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


# --------------------------------------------------------------------------- #1434


class ReportBugSkillExists(unittest.TestCase):
    """Requirement 1 / AC1: `.claude/skills/report-bug/SKILL.md` の frontmatter(#1434)。"""

    PATH = ".claude/skills/report-bug/SKILL.md"

    def _frontmatter(self):
        text = read(self.PATH)
        parts = text.split("---", 2)
        self.assertEqual(3, len(parts), "%s の frontmatter が見つからない" % self.PATH)
        return parts[1]

    def test_skill_file_exists(self):
        self.assertIn(self.PATH, claude_docs())

    def test_frontmatter_declares_opus(self):
        """CLAUDE.md → Model Selection → Authoring Issues → opus(Requirement 1)。"""
        self.assertRegex(self._frontmatter(), r"(?m)^model:\s*opus\s*$")

    def test_frontmatter_name_matches_directory(self):
        """`SkillNames.test_frontmatter_name_matches_the_directory` と同じ検査を、
        このファイルが存在しない間も明示的な失敗として出すための重複。"""
        self.assertRegex(self._frontmatter(), r"(?m)^name:\s*report-bug\s*$")


class ReportBugDoesNotRestateTheIssueTemplate(unittest.TestCase):
    """Requirement 2: 本文は Issue の節構成を書き直さず、`plan-issue` / `project-planner` を
    参照する(#1434)。`.claude/` 内の二重定義はこのプロジェクトではバグ扱い(CLAUDE.md)。

    #1447 より前は「3つ以上一致すれば書き直しとみなす」だったが、それでは節見出しを
    1つか2つだけ書き写した部分的な書き直しを取りこぼす。マーカー自体が「行頭で
    `#` に続いて見出し語だけが単独で並ぶ」形にしか一致しない(`Step 2` の地の文にある
    "Title, Background, Problem, ..." のような列挙は一致しない)ので、検査範囲を
    さらに節に絞り込む必要はない。ここで直すのは閾値だけで、**1件でも一致したら
    失敗**にする。
    """

    PATH = ".claude/skills/report-bug/SKILL.md"

    ISSUE_TEMPLATE_HEADING_MARKERS = [
        re.compile(r"^#{1,3}\s+Background\s*$", re.MULTILINE),
        re.compile(r"^#{1,3}\s+Problem\s*$", re.MULTILINE),
        re.compile(r"^#{1,3}\s+Requirements\s*$", re.MULTILINE),
        re.compile(r"^#{1,3}\s+Acceptance Criteria\s*$", re.MULTILINE),
        re.compile(r"^#{1,3}\s+Out of Scope\s*$", re.MULTILINE),
        re.compile(r"^#{1,3}\s+Implementation Notes\s*$", re.MULTILINE),
    ]

    def test_does_not_restate_the_issue_template_headings(self):
        text = read(self.PATH)
        matched = sum(1 for m in self.ISSUE_TEMPLATE_HEADING_MARKERS if m.search(text))
        self.assertEqual(
            0, matched, "report-bug が Issue の節構成をそのまま書き直している(%d 個一致)" % matched
        )

    def test_references_plan_issue(self):
        self.assertIn("plan-issue", read(self.PATH))

    def test_references_project_planner(self):
        self.assertIn("project-planner", read(self.PATH))

    def test_references_the_five_acceptance_criteria_cap(self):
        self.assertRegex(read(self.PATH), r"At most five Acceptance Criteria")


class ReportBugSkillContent(unittest.TestCase):
    """Requirement 2 / AC4: 固定ラベル一式・`status::Backlog`・上限チェック手順(#1434)。"""

    PATH = ".claude/skills/report-bug/SKILL.md"

    def test_specifies_the_fixed_label_set(self):
        text = read(self.PATH)
        for label in ("user-request", "bug", "priority::P0", "hotfix", "status::Backlog"):
            with self.subTest(label=label):
                self.assertIn(label, text, "%s がラベル一式に含まれていない" % label)

    def test_uses_glab_issue_create_with_label_flag(self):
        self.assertRegex(read(self.PATH), r"glab issue create.*--label", )

    def test_has_a_hotfix_cap_check_before_filing(self):
        """AC4: 起票前に open な hotfix の件数を数える手順。"""
        text = read(self.PATH)
        self.assertIn("hotfix", text)
        self.assertRegex(text, r"3")
        self.assertRegex(text, r"(state=opened|open な)")

    def test_stops_without_creating_when_the_cap_is_full(self):
        """AC4: 上限3件のとき、起票せず止まる。"""
        text = read(self.PATH)
        self.assertRegex(text, r"(Stop|止まり|止まる)")

    def test_shows_the_existing_three_and_offers_two_choices(self):
        """AC4: 既存3件を示し、二択を提示する。"""
        text = read(self.PATH)
        self.assertRegex(text, r"(existing 3|既存3件|既存の3件)")
        # 二択: web UI で外す/hotfix 無しで起票する、の2通り
        self.assertRegex(text, r"(?s)1\..{0,400}2\.")

    def test_never_removes_an_existing_hotfix_itself(self):
        """Requirement 6: Claude が既存の hotfix を外すことはしない。"""
        text = read(self.PATH)
        self.assertRegex(text, r"(Never remove|外すことはしない|Claude.*外さない)")

    def test_reports_issue_number_labels_and_readiness_signal(self):
        """Requirement 7: 起票後、Issue 番号・ラベル・readiness signal を報告する。"""
        text = read(self.PATH)
        self.assertIn("Readiness Signal", text)
        self.assertRegex(text, r"(Ready candidate|Needs clarification)")

    def test_does_not_decide_ready_itself(self):
        """Requirement 7: Backlog → Ready の判定はしない。"""
        text = read(self.PATH)
        self.assertIn("ready-issue", text)


class ReadOnlyStagesIncludeReportBug(unittest.TestCase):
    """Requirement 3 / AC2: `CLAUDE.md` → Read-Only Stages に `report-bug` を追加する(#1434)。"""

    def _section(self):
        text = read(".claude/CLAUDE.md")
        after = text.split("# Read-Only Stages", 1)
        self.assertEqual(2, len(after), "CLAUDE.md に # Read-Only Stages が無い")
        return after[1].split("\n# ", 1)[0]

    def test_definition_sentence_lists_report_bug(self):
        section = self._section()
        self.assertRegex(
            section,
            r"`discover-issues`,?\s*`triage-backlog`,?\s*`ready-issue`.{0,40}`report-bug`"
            r"|`report-bug`.{0,120}`discover-issues`",
        )

    def test_permitted_mutations_table_has_a_report_bug_row(self):
        section = self._section()
        self.assertRegex(section, r"\|\s*`report-bug`\s*\|")
        # そのすぐ後(表の同じ行)に、許可される内容が書かれていること
        row_match = re.search(r"\|\s*`report-bug`\s*\|([^\n]*)\|", section)
        self.assertIsNotNone(row_match, "report-bug の行が見つからない")
        row = row_match.group(1)
        for token in ("user-request", "bug", "priority::P0", "hotfix", "status::Backlog"):
            with self.subTest(token=token):
                self.assertIn(token, row)

    def test_guard_py_read_only_skills_include_report_bug(self):
        text = read(".claude/hooks/guard.py")
        match = re.search(r"READ_ONLY_SKILLS\s*=\s*\{([^}]*)\}", text)
        self.assertIsNotNone(match, "guard.py に READ_ONLY_SKILLS が見つからない")
        self.assertIn('"report-bug"', match.group(1))


class ModelSelectionIncludesReportBug(unittest.TestCase):
    """Requirement 8: `CLAUDE.md` → Model Selection の opus 一覧に `report-bug`(#1434)。"""

    def test_opus_assignment_line_includes_report_bug(self):
        text = read(".claude/CLAUDE.md")
        section = text.split("## What each stage declares", 1)[1].split("\n---", 1)[0]
        match = re.search(r"`opus`\s*—\s*([^\n]*)", section)
        self.assertIsNotNone(match, "opus の割り当て行が見つからない")
        self.assertIn("report-bug", match.group(1))


class BacklogCreationExceptionIsDefinedOnce(unittest.TestCase):
    """Requirement 4 / AC5: `/report-bug` の Backlog 直接起票の例外は1か所だけ(#1434)。

    `plan-issue` Step 5 の「the project workflow explicitly allows the planner to skip a
    stage」が指す先。表の1行(Read-Only Stages)とは別に、Inbox 始まりの既定に対する
    例外だと明示的に述べる文が、CLAUDE.md 中にちょうど1つだけ存在することを固定する。
    """

    SENTINEL = "creates its Issue directly in `status::Backlog`, skipping `Inbox`"

    def test_sentinel_sentence_appears_exactly_once(self):
        text = read(".claude/CLAUDE.md")
        self.assertEqual(
            1, text.count(self.SENTINEL),
            "Backlog 直接起票の例外が0か所、または複数か所に書かれている",
        )

    def test_exception_mentions_report_bug_and_plan_issue_step_5(self):
        text = read(".claude/CLAUDE.md")
        idx = text.find(self.SENTINEL)
        self.assertNotEqual(-1, idx)
        surrounding = text[max(0, idx - 200): idx + 600]
        self.assertIn("report-bug", surrounding)
        self.assertIn("plan-issue", surrounding)
        self.assertIn("Step 5", surrounding)


class LegalTransitionsAreUnchanged(unittest.TestCase):
    """AC5: Legal Transitions の表と `LEGAL_STATUS_TRANSITIONS` は変わっていない(#1434)。

    作成は遷移ではないので、#1434 はこの表にも `guard.py` のデータにも触れない。
    ここは「触れていないこと」自体を固定する回帰ガードであり、新しい振る舞いを
    要求するものではない(常に真であるべき既存の不変条件)。
    """

    EXPECTED_TABLE = """| Kind | Transition | Driven by |
| --- | --- | --- |
| Forward | `Inbox → Backlog` | `triage-backlog` |
| Forward | `Backlog → Ready` | `ready-issue` |
| Forward | `Ready → In Progress` | `work-next` Step 6 |
| Forward | `In Progress → Review` | `work-next` Step 7 |
| Forward | `Review → QA` | `review-issue` APPROVED |
| Forward | `QA → Done` | `complete-issue`, after confirming the merge |
| Rollback | `Review → In Progress` | `review-issue` CHANGES REQUIRED |
| Rollback | `QA → In Progress` | `qa-issue` FAIL |
| Rollback | `Ready → Backlog` | `work-next` Step 4 |
| Rollback | `Review → Backlog` | `review-issue` REQUIREMENT CLARIFICATION |
| Rollback | `In Progress → Ready` | re-assessment after two rollbacks in one cycle (see **Implementation runs on Sonnet**) |"""

    EXPECTED_GUARD_SET = """LEGAL_STATUS_TRANSITIONS = {
    # 前進
    ("Inbox", "Backlog"),
    ("Backlog", "Ready"),
    ("Ready", "In Progress"),
    ("In Progress", "Review"),
    ("Review", "QA"),
    ("QA", "Done"),
    # 差し戻し
    ("Review", "In Progress"),
    ("QA", "In Progress"),
    ("Ready", "Backlog"),
    ("Review", "Backlog"),
    ("In Progress", "Ready"),
}"""

    def test_claude_md_table_is_byte_identical(self):
        self.assertIn(self.EXPECTED_TABLE, read(".claude/CLAUDE.md"))

    def test_guard_py_set_is_byte_identical(self):
        self.assertIn(self.EXPECTED_GUARD_SET, read(".claude/hooks/guard.py"))

    def test_no_new_transition_mentions_report_bug(self):
        text = read(".claude/CLAUDE.md")
        after = text.split("### Legal Transitions", 1)[1].split("\n### ", 1)[0]
        self.assertNotIn("report-bug", after)


class HotfixSectionDocumentsCreateTimeHandling(unittest.TestCase):
    """Requirement 5: hotfix セクションが、作成時の扱いを最新の状態で説明している(#1434)。

    #1433 の文言「`glab issue create --label hotfix,...` is neither denied nor specially
    handled here」は、#1434 が実装した時点で事実と食い違う(#1434 はまさにその「作成時の
    扱い」を追加する)。齟齬を残さないこと自体を検査する。
    """

    def _hotfix_section(self):
        text = read(".claude/CLAUDE.md")
        after = text.split("## hotfix", 1)
        self.assertEqual(2, len(after), "CLAUDE.md に ## hotfix が無い")
        return after[1].split("\n## ", 1)[0]

    def test_no_longer_claims_creation_is_unhandled(self):
        section = self._hotfix_section()
        self.assertNotIn("is neither denied nor specially handled here", section)

    def test_describes_the_report_bug_only_create_time_gate(self):
        section = self._hotfix_section()
        self.assertIn("report-bug", section)
        self.assertIn("guard.py", section)


# --------------------------------------------------------------------------- #1438


class DiscoverIssuesEntryPointsAreCurrent(unittest.TestCase):
    """Requirement 1/2/3, AC1/AC2: discover-issues の入口一覧が `/report-bug` を含み、
    件数の表記が一覧の項目数と一致していること。定義そのものは書き直さず、
    `CLAUDE.md` / `report-bug/SKILL.md` への参照に留めていること(#1438)。
    """

    PATH = ".claude/skills/discover-issues/SKILL.md"

    NUMBER_WORDS = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six"}

    # `report-bug` の固定ラベル一式・上限の定義を、discover-issues が自分で書き直した
    # 兆候。#1447 より前は「ファイル全体を対象に2つ以上一致すれば書き直しとみなす」
    # 閾値だったが、これだと105行目付近の無関係な既存の一文("Never apply
    # `user-request`...")が偶然2件目の一致を稼ぐ場合にしか部分的な書き写しを
    # 検出できず、その一文が言い換えられると素通りする(#1447 で実証)。
    # マーカーは裸の語なので、入口一覧の**節の中だけ**を対象にする
    # (`_entry_points_section()` の外に出れば、地の文の「参照」に触れても
    # 誤検出しない)。1つでも一致すれば書き直しとみなす。
    RESTATEMENT_MARKERS = [
        re.compile(r"user-request"),
        re.compile(r"priority::P0"),
        re.compile(r"上限3件|at most 3|cap of 3|no more than 3"),
    ]

    def _entry_points_section(self):
        text = read(self.PATH)
        after = text.split(
            "entry points into the Issue registration workflow:\n\n", 1
        )
        self.assertEqual(2, len(after), "%s に入口一覧の導入文が見つからない" % self.PATH)
        section = after[1].split("\n\n", 1)[0]
        return section

    def _stated_number_word(self):
        text = read(self.PATH)
        match = re.search(
            r"one of the (\w+) entry points into the Issue registration workflow", text
        )
        self.assertIsNotNone(match, "入口一覧の導入文が見つからない")
        return match.group(1)

    def _item_count(self):
        section = self._entry_points_section()
        return len(re.findall(r"^\s*\d+\.\s+", section, re.MULTILINE))

    def test_lists_report_bug_as_an_entry_point(self):
        section = self._entry_points_section()
        self.assertRegex(section, r"`?/report-bug`?")

    def test_item_count_is_at_least_four_now_that_report_bug_exists(self):
        self.assertGreaterEqual(
            self._item_count(), 4, "report-bug 追加後は入口が4つ以上のはず"
        )

    def test_stated_number_word_matches_the_item_count(self):
        count = self._item_count()
        expected_word = self.NUMBER_WORDS.get(count)
        self.assertIsNotNone(
            expected_word, "項目数 %d に対応する数詞が定義されていない" % count
        )
        self.assertEqual(
            expected_word,
            self._stated_number_word(),
            "件数の表記が一覧の項目数(%d)と一致しない" % count,
        )

    def test_distinguishes_regular_bug_reports_from_urgent_ones(self):
        """Requirement 2: 通常のバグ報告(plan-issue)と緊急バグ(report-bug)の使い分け。"""
        section = self._entry_points_section()
        self.assertIn("plan-issue", section)
        self.assertRegex(section, r"/report-bug")

    def test_does_not_restate_the_report_bug_definition(self):
        """Requirement 3: report-bug の意味・ラベル・上限を書き直さない(#1447)。

        検査対象は入口一覧の**節だけ**(`_entry_points_section()`)。ファイル全体を
        見ると、節の外にある無関係な既存の一文がたまたま目印を拾ってしまい、
        その一文の言い換え次第で判定が変わってしまう(#1447 の Problem)。
        """
        section = self._entry_points_section()
        matched = sum(1 for m in self.RESTATEMENT_MARKERS if m.search(section))
        self.assertEqual(
            0, matched,
            "discover-issues が入口一覧の中で report-bug の定義を書き直している"
            "(%d 個一致)" % matched,
        )

    def test_references_report_bug_definition_source(self):
        """Requirement 3: 一覧の `/report-bug` の項目そのものが `CLAUDE.md` または
        `report-bug/SKILL.md` への参照になっている(ファイルのどこか他の場所に
        `CLAUDE.md` という語があるだけでは満たされない)。"""
        section = self._entry_points_section()
        self.assertRegex(section, r"(report-bug/SKILL\.md|CLAUDE\.md)")


class NoStaleEntryPointEnumerations(unittest.TestCase):
    """AC3: `.claude/` 内に、入口を古い件数・古い内容で列挙している箇所が
    他に残っていないこと(#1438)。"""

    def test_no_document_mentions_three_entry_points(self):
        offenders = [path for path in claude_docs() if "three entry points" in read(path)]
        self.assertEqual([], offenders, "古い件数表記が残っている: %s" % offenders)


if __name__ == "__main__":
    unittest.main()
