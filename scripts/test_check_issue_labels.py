#!/usr/bin/env python3
"""`scripts/check-issue-labels.sh` の単体テスト(#1023)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ検出側も要るのか

`guard.py` の予防はエージェントと CLI 経由の操作しか見られない。Web UI から
ラベルを手で付け外しされたら、フックは何も知らない。ステータスの一意性は
ワークフローの選択ロジックが依拠している前提なので、破れたことに気づく手段が要る。

とくに **0 個** が危険である。ステータスの無い Issue はボードのどの列にも現れず、
`work-next` からも triage からも見えない。誰も困らないまま忘れられる。

`glab` は PATH 上のスタブに差し替える。
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-issue-labels.sh")

# issue #1031 (レビュー再検討): ページごとにファイルを分けて返す。
# `issues_page_<n>` / `closed_page_<n>` が存在しなければ空配列(=そのページで打ち切り)。
# `closed_by_<iid>` は `issues/<iid>/closed_by` への応答。
STUB = r"""#!/bin/bash
for arg in "$@"; do
  case "$arg" in
    */closed_by*)
      iid="$(echo "$arg" | sed -E 's#.*/issues/([0-9]+)/closed_by.*#\1#')"
      f="$GLAB_STUB_DIR/closed_by_${iid}"
      if [ -f "$f" ]; then cat "$f"; else echo "[]"; fi
      exit 0
      ;;
  esac
done
for arg in "$@"; do
  case "$arg" in
    *state=closed*)
      page=1
      case "$arg" in *page=*) page="$(echo "$arg" | sed -E 's/.*page=([0-9]+).*/\1/')" ;; esac
      f="$GLAB_STUB_DIR/closed_page_${page}"
      if [ -f "$f" ]; then cat "$f"; else echo "[]"; fi
      exit 0
      ;;
  esac
done
for arg in "$@"; do
  case "$arg" in
    *state=opened*)
      page=1
      case "$arg" in *page=*) page="$(echo "$arg" | sed -E 's/.*page=([0-9]+).*/\1/')" ;; esac
      f="$GLAB_STUB_DIR/issues_page_${page}"
      if [ -f "$f" ]; then cat "$f"; else echo "[]"; fi
      exit 0
      ;;
  esac
done
echo "[]"
"""


def issue(iid, labels):
    return {"iid": iid, "title": "Issue %d" % iid, "state": "opened", "labels": labels}


# #1031 の再検討で導入したカットオフ(check-issue-labels.sh の CUTOFF と同じ値)。
# これより後に closed_at を持つ Issue だけを検査対象にする。
CUTOFF = "2026-09-03T02:44:25Z"
AFTER_CUTOFF = "2026-09-04T00:00:00Z"
BEFORE_CUTOFF = "2026-09-01T00:00:00Z"


def closed_issue(iid, labels, closed_at=AFTER_CUTOFF):
    return {
        "iid": iid,
        "title": "Issue %d" % iid,
        "state": "closed",
        "labels": labels,
        "closed_at": closed_at,
    }


def merged_mr(mr_iid):
    return {"iid": mr_iid, "state": "merged"}


class Harness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        stub = os.path.join(self.bin, "glab")
        with open(stub, "w") as f:
            f.write(STUB)
        os.chmod(stub, 0o755)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_with(self, issues, closed_issues=None, closed_by=None):
        return self.run_paginated(
            issues_pages=[issues],
            closed_pages=[closed_issues or []],
            closed_by=closed_by,
        )

    def run_paginated(self, issues_pages=None, closed_pages=None, closed_by=None):
        for i, page in enumerate(issues_pages or [[]], start=1):
            with open(os.path.join(self.tmp, "issues_page_%d" % i), "w") as f:
                json.dump(page, f)
        for i, page in enumerate(closed_pages or [[]], start=1):
            with open(os.path.join(self.tmp, "closed_page_%d" % i), "w") as f:
                json.dump(page, f)
        for iid, mrs in (closed_by or {}).items():
            with open(os.path.join(self.tmp, "closed_by_%s" % iid), "w") as f:
                json.dump(mrs, f)
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["GLAB_STUB_DIR"] = self.tmp
        return subprocess.run(
            ["bash", SCRIPT], capture_output=True, text=True, env=env, timeout=60
        )


class Healthy(Harness):
    def test_exactly_one_of_each_passes(self):
        r = self.run_with(
            [
                issue(1, ["status::Ready", "priority::P1"]),
                issue(2, ["status::Done", "priority::P2", "bug"]),
            ]
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_reports_the_number_checked(self):
        r = self.run_with([issue(1, ["status::Ready", "priority::P1"])])
        self.assertIn("1", r.stdout)


class Violations(Harness):
    def test_two_status_labels_fail(self):
        r = self.run_with([issue(7, ["status::Ready", "status::QA", "priority::P1"])])
        self.assertNotEqual(0, r.returncode)
        self.assertIn("#7", r.stdout + r.stderr)

    def test_no_status_label_fails(self):
        r = self.run_with([issue(8, ["priority::P1"])])
        self.assertNotEqual(0, r.returncode)
        self.assertIn("#8", r.stdout + r.stderr)

    def test_two_priority_labels_fail(self):
        r = self.run_with([issue(9, ["status::Ready", "priority::P0", "priority::P2"])])
        self.assertNotEqual(0, r.returncode)
        self.assertIn("#9", r.stdout + r.stderr)

    def test_no_priority_label_fails(self):
        r = self.run_with([issue(10, ["status::Ready"])])
        self.assertNotEqual(0, r.returncode)
        self.assertIn("#10", r.stdout + r.stderr)

    def test_lists_every_offender_not_just_the_first(self):
        r = self.run_with(
            [
                issue(11, []),
                issue(12, ["status::Ready", "status::Done", "priority::P1"]),
                issue(13, ["status::Ready", "priority::P1"]),
            ]
        )
        out = r.stdout + r.stderr
        self.assertIn("#11", out)
        self.assertIn("#12", out)
        self.assertNotIn("#13", out)

    def test_says_which_side_is_wrong(self):
        """0 個と 2 個は原因も対処も違う。区別して報告すること。"""
        missing = self.run_with([issue(20, ["priority::P1"])]).stdout
        doubled = self.run_with(
            [issue(21, ["status::Ready", "status::QA", "priority::P1"])]
        ).stdout
        self.assertNotEqual(
            missing.replace("20", "N"),
            doubled.replace("21", "N"),
            "0 個と 2 個が同じ報告になっている",
        )


class EmptyProject(Harness):
    def test_no_open_issues_passes(self):
        self.assertEqual(0, self.run_with([]).returncode)


class MergePrecondition(Harness):
    """#1031 Requirement 3, 方式C(レビュー再検討版): `complete-issue` のマージ前提を
    事後検出する。

    独立レビュー(#1031 の Review コメント)は、`state=closed` かつ `status::Done` で
    ないという条件だけでは 47 件の誤検知(運用開始前の過去 Issue、重複/却下などマージと
    無関係な理由での closed)を生むと指摘した。再検討では二重に絞る:

    1. **カットオフ**: `closed_at` が CUTOFF(#1023 がマージされた日時)以降の Issue だけを
       検査する。それより前は Out of Scope #4(過去の遷移履歴は問わない)の対象。
    2. **MR 突き合わせ**: `issues/<iid>/closed_by` を引き、実際に **merged な** MR が
       閉じたことを確認する。手動 close(重複・却下等)は #959 パターンではないので
       対象から外す。
    """

    def test_closed_issue_without_done_label_and_merged_mr_is_flagged(self):
        r = self.run_with(
            [],
            closed_issues=[closed_issue(959, ["status::In Progress", "priority::P1"])],
            closed_by={"959": [merged_mr(1)]},
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("#959", r.stdout + r.stderr)

    def test_closed_issue_with_done_label_passes(self):
        r = self.run_with(
            [],
            closed_issues=[closed_issue(100, ["status::Done", "priority::P1"])],
            closed_by={"100": [merged_mr(1)]},
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_open_issues_are_unaffected_by_closed_check(self):
        r = self.run_with(
            [issue(1, ["status::Ready", "priority::P1"])],
            closed_issues=[closed_issue(100, ["status::Done", "priority::P1"])],
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_closed_before_cutoff_without_done_label_is_not_flagged(self):
        """#1031 Out of Scope #4: 運用開始前の過去 Issue は判定しない。"""
        r = self.run_with(
            [],
            closed_issues=[
                closed_issue(
                    500,
                    ["status::In Progress", "priority::P1"],
                    closed_at=BEFORE_CUTOFF,
                )
            ],
            closed_by={"500": [merged_mr(1)]},
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertNotIn("#500", r.stdout + r.stderr)

    def test_closed_after_cutoff_without_merged_mr_is_not_flagged(self):
        """重複/却下など、マージ以外の理由で closed になった Issue は #959 パターンではない。"""
        r = self.run_with(
            [],
            closed_issues=[
                closed_issue(600, ["status::Inbox", "priority::P1"], closed_at=AFTER_CUTOFF)
            ],
            closed_by={"600": []},
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertNotIn("#600", r.stdout + r.stderr)

    def test_closed_after_cutoff_with_unmerged_mr_is_not_flagged(self):
        """closed_by に MR はあるがまだ merged でない(=無関係)場合は対象外。"""
        r = self.run_with(
            [],
            closed_issues=[
                closed_issue(700, ["status::Inbox", "priority::P1"], closed_at=AFTER_CUTOFF)
            ],
            closed_by={"700": [{"iid": 1, "state": "opened"}]},
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertNotIn("#700", r.stdout + r.stderr)


class Pagination(Harness):
    """#1031 レビュー再検討: per_page=100 の1ページ目しか見ておらず、2ページ目以降の
    closed / open Issue が検査対象から漏れていた。ページを跨いで応答を返すスタブで、
    最終ページまで辿ることを確認する。"""

    def test_closed_issues_beyond_page_one_are_inspected(self):
        r = self.run_paginated(
            issues_pages=[[]],
            closed_pages=[
                [closed_issue(501, ["status::Done", "priority::P1"])],
                [closed_issue(502, ["status::In Progress", "priority::P1"])],
            ],
            closed_by={"502": [merged_mr(1)]},
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("#502", r.stdout + r.stderr)

    def test_open_issues_beyond_page_one_are_inspected(self):
        r = self.run_paginated(
            issues_pages=[
                [issue(1, ["status::Ready", "priority::P1"])],
                [issue(2, ["priority::P1"])],
            ],
            closed_pages=[[]],
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("#2", r.stdout + r.stderr)


class HotfixCap(Harness):
    """CLAUDE.md → Issue Provenance → hotfix: open な Issue で最大3件(#1433)。

    guard.py は既存 Issue への hotfix の付与・削除を拒否するが、上限3件の判定は
    しない(件数を知るには API 問い合わせが要り、guard.py はネットワークを使わない
    方針)。ここが事後検出を担う。closed な hotfix は数えない(`complete-issue` が
    Done に付け替えても、hotfix ラベル自体を外す必要が無いようにするため)。
    """

    def _hotfix_issue(self, iid):
        return issue(iid, ["status::Ready", "priority::P1", "hotfix"])

    def test_three_open_hotfix_issues_pass(self):
        r = self.run_with([self._hotfix_issue(i) for i in (1, 2, 3)])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_four_open_hotfix_issues_fail(self):
        r = self.run_with([self._hotfix_issue(i) for i in (1, 2, 3, 4)])
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)

    def test_violation_lists_every_hotfix_issue_number(self):
        r = self.run_with([self._hotfix_issue(i) for i in (11, 12, 13, 14)])
        out = r.stdout + r.stderr
        for iid in (11, 12, 13, 14):
            with self.subTest(iid=iid):
                self.assertIn("#%d" % iid, out)

    def test_closed_hotfix_issues_are_not_counted(self):
        """open は3件だけなら、closed に何件 hotfix があっても違反にならない。"""
        r = self.run_with(
            [self._hotfix_issue(i) for i in (1, 2, 3)],
            closed_issues=[
                closed_issue(90 + i, ["status::Done", "priority::P1", "hotfix"])
                for i in range(5)
            ],
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_non_hotfix_issues_do_not_count_toward_the_cap(self):
        r = self.run_with(
            [self._hotfix_issue(i) for i in (1, 2, 3)]
            + [issue(4, ["status::Ready", "priority::P1"])]
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
