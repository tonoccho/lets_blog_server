#!/usr/bin/env python3
"""`scripts/issue-dependency-status.sh` の単体テスト(#1024)。

`scripts/` は既存のどのテストランナーの対象にもなっていないため、
`test_check_changed_coverage.py` と同じく Python 標準の unittest で回す。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## 何を検査するか

このスクリプトは判定を行わない。判定の**入力**を、誰がいつ実行しても同じ形で
得られるように固定するのが役割である(#751)。したがって検査するのも
「与えられた API 応答から、どんな入力表を組み立てるか」であって、
Ready/Backlog の判断ではない。

`glab` は PATH 上のスタブに差し替える。ネットワークにも GitLab にも依存しない。

## なぜ「取得失敗」の検査が重要か

API 失敗を黙って空配列に落とすと、一時的なネットワークエラーが
「依存なし」に化ける。#751 はまさに入力のぶれから生まれた事故なので、
**失敗は失敗として非0で落ちること**を固定する。
"""

import json
import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "issue-dependency-status.sh")


# スタブは呼ばれた引数を見て、fixtures ディレクトリの中から応答を選ぶ。
# 選択規則は「引数を連結した文字列に含まれる目印」で決める。単純だが、
# このスクリプトが叩く API は数えるほどしかないので十分。
STUB = r"""#!/bin/bash
args="$*"
d="$GLAB_STUB_DIR"
pick() {
  if [ -f "$d/$1" ]; then cat "$d/$1"; exit 0; fi
  if [ -f "$d/$1.fail" ]; then cat "$d/$1.fail" >&2; exit 1; fi
  echo "スタブ未定義: $1 ($args)" >&2; exit 1
}
case "$args" in
  *graphql*)        pick children ;;
  */links*)         pick links ;;
  */notes*)         pick notes ;;
  *auth*status*)    echo ok; exit 0 ;;
  *issues/*)        n="${args##*issues/}"; n="${n%% *}"; pick "issue-$n" ;;
esac
echo "スタブ未定義: $args" >&2
exit 1
"""


def issue(iid, title, state="opened", description=""):
    return {
        "iid": iid,
        "title": title,
        "state": state,
        "description": description,
        "closed_at": None if state == "opened" else "2026-09-01T00:00:00Z",
        "web_url": "https://example.invalid/-/issues/%d" % iid,
    }


class ScriptHarness(unittest.TestCase):
    """スタブ化した `glab` の下でスクリプトを走らせる土台。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.bin = os.path.join(self.tmp, "bin")
        self.fixtures = os.path.join(self.tmp, "fixtures")
        os.makedirs(self.bin)
        os.makedirs(self.fixtures)
        stub = os.path.join(self.bin, "glab")
        with open(stub, "w") as f:
            f.write(STUB)
        os.chmod(stub, 0o755)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def fixture(self, name, payload):
        path = os.path.join(self.fixtures, name)
        with open(path, "w") as f:
            f.write(payload if isinstance(payload, str) else json.dumps(payload))

    def fail_fixture(self, name, message="503 Service Unavailable"):
        with open(os.path.join(self.fixtures, name + ".fail"), "w") as f:
            f.write(message)

    def run_script(self, number):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["GLAB_STUB_DIR"] = self.fixtures
        return subprocess.run(
            ["bash", SCRIPT, str(number)],
            capture_output=True,
            text=True,
            env=env,
            timeout=60,
        )

    def empty_links_and_notes(self):
        self.fixture("links", [])
        self.fixture("notes", [])
        self.fixture("children", {"data": {"project": {"workItems": {"nodes": []}}}})


class UsesGlab(ScriptHarness):
    def test_does_not_call_gh(self):
        """`gh` を呼ばないこと。gh は移行元の GitHub を指すため、実行されると
        移行前のリポジトリの状態で判定してしまう(#1025 と同じ危険)。"""
        with open(SCRIPT, encoding="utf-8") as f:
            source = f.read()
        self.assertNotIn("gh ", source, "スクリプトに gh の呼び出しが残っている")
        self.assertIn("glab", source)


class BodyDependencies(ScriptHarness):
    def test_lists_issues_referenced_in_the_dependency_section(self):
        self.fixture(
            "issue-100",
            issue(100, "対象", description="## 依存\n\n- #200 前提の実装\n"),
        )
        self.fixture("issue-200", issue(200, "前提", state="closed"))
        self.empty_links_and_notes()
        r = self.run_script(100)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("#200", r.stdout)
        self.assertIn("closed", r.stdout.lower())

    def test_distinguishes_declared_none_from_unidentifiable(self):
        """「依存なし」と「識別子で書かれていない」は別物である。

        前者に後者の警告を出すと、依存ゼロの Issue を「依存不明」と誤って扱わせる。
        """
        self.fixture("issue-101", issue(101, "対象", description="## 依存\n\nなし\n"))
        self.empty_links_and_notes()
        none_out = self.run_script(101).stdout

        self.fixture(
            "issue-102",
            issue(102, "対象", description="## 依存\n\n- A4, B6, C14\n"),
        )
        self.empty_links_and_notes()
        shorthand_out = self.run_script(102).stdout

        self.assertIn("なし", none_out)
        self.assertNotIn("警告", none_out, "依存ゼロに『識別できない』警告が出ている")
        self.assertIn("警告", shorthand_out, "Epic 略記に警告が出ていない")

    def test_reports_a_missing_dependency_section(self):
        self.fixture("issue-103", issue(103, "対象", description="## Goal\n\nやる\n"))
        self.empty_links_and_notes()
        self.assertIn("依存の節が無い", self.run_script(103).stdout)


class FormalLinks(ScriptHarness):
    def test_lists_linked_issues_and_says_they_are_not_directional(self):
        """CE のリンクは `relates_to` しかなく、方向を持たない。

        「これは状態ベースのブロッカーではない」と出力自身が言わなければ、
        読んだ側が GitHub の `blocked_by` と同じものだと誤解する。
        """
        self.fixture("issue-110", issue(110, "対象", description="## 依存\n\nなし\n"))
        self.fixture(
            "links",
            [dict(issue(111, "関連", state="opened"), link_type="relates_to")],
        )
        self.fixture("notes", [])
        self.fixture("children", {"data": {"project": {"workItems": {"nodes": []}}}})
        out = self.run_script(110).stdout
        self.assertIn("#111", out)
        self.assertIn("relates_to", out)
        self.assertIn("ブロッカーではない", out)


class ChildItems(ScriptHarness):
    def test_flags_an_open_parent_whose_children_are_all_closed(self):
        """#575 のケース。親トラッキング Issue は分割子が全て Done でも OPEN のまま残る。

        これを見落とすと、同じ Issue に対して正反対の判定が立つ(#751)。
        """
        self.fixture("issue-120", issue(120, "親", description="## 依存\n\nなし\n"))
        self.fixture("links", [])
        self.fixture("notes", [])
        self.fixture(
            "children",
            {
                "data": {
                    "project": {
                        "workItems": {
                            "nodes": [
                                {
                                    "widgets": [
                                        {
                                            "hasChildren": True,
                                            "children": {
                                                "nodes": [
                                                    {"iid": "121", "state": "CLOSED"},
                                                    {"iid": "122", "state": "CLOSED"},
                                                ]
                                            },
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                }
            },
        )
        out = self.run_script(120).stdout
        self.assertIn("2", out, "子アイテム数が出ていない")
        self.assertIn("全て", out, "『親は OPEN だが子は全て CLOSED』の注意が出ていない")


class ExistingVerdicts(ScriptHarness):
    def test_lists_previous_readiness_comments(self):
        """直近の判定と逆向きの判定を無自覚に投稿しないための材料(#751)。"""
        self.fixture("issue-130", issue(130, "対象", description="## 依存\n\nなし\n"))
        self.fixture("links", [])
        self.fixture("children", {"data": {"project": {"workItems": {"nodes": []}}}})
        self.fixture(
            "notes",
            [
                {"created_at": "2026-09-01T01:27:00Z", "body": "## Readiness Report\n\nREADY"},
                {"created_at": "2026-09-01T02:00:00Z", "body": "ただの雑談"},
            ],
        )
        out = self.run_script(130).stdout
        self.assertIn("Readiness", out)
        self.assertNotIn("ただの雑談", out)


class FetchFailures(ScriptHarness):
    """取得失敗を「依存なし」に化けさせないこと。"""

    def test_missing_issue_exits_non_zero(self):
        self.fail_fixture("issue-140", "404 Not Found")
        r = self.run_script(140)
        self.assertNotEqual(0, r.returncode, "存在しない Issue で成功してしまった")

    def test_link_fetch_failure_exits_non_zero(self):
        self.fixture("issue-141", issue(141, "対象", description="## 依存\n\nなし\n"))
        self.fail_fixture("links")
        self.fixture("notes", [])
        self.fixture("children", {"data": {"project": {"workItems": {"nodes": []}}}})
        r = self.run_script(141)
        self.assertNotEqual(0, r.returncode, "リンク取得の失敗が握り潰された")
        self.assertNotIn("(なし)", r.stdout.split("依存の節")[0])

    def test_notes_fetch_failure_exits_non_zero(self):
        self.fixture("issue-142", issue(142, "対象", description="## 依存\n\nなし\n"))
        self.fixture("links", [])
        self.fixture("children", {"data": {"project": {"workItems": {"nodes": []}}}})
        self.fail_fixture("notes")
        self.assertNotEqual(0, self.run_script(142).returncode)


class Usage(ScriptHarness):
    def test_rejects_a_non_numeric_argument(self):
        self.assertNotEqual(0, self.run_script("abc").returncode)

    def test_requires_an_argument(self):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["GLAB_STUB_DIR"] = self.fixtures
        r = subprocess.run(["bash", SCRIPT], capture_output=True, text=True, env=env, timeout=30)
        self.assertNotEqual(0, r.returncode)


class OutputShape(ScriptHarness):
    def test_keeps_the_four_sections(self):
        """出力の形は判定の再現性そのもの。節を落とさない(#1024 Requirement 1)。"""
        self.fixture("issue-150", issue(150, "対象", description="## 依存\n\nなし\n"))
        self.empty_links_and_notes()
        out = self.run_script(150).stdout
        for section in ("対象 Issue", "リンク", "本文の依存節", "Readiness"):
            with self.subTest(section=section):
                self.assertIn(section, out)


if __name__ == "__main__":
    unittest.main()
