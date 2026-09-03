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

STUB = r"""#!/bin/bash
cat "$GLAB_STUB_DIR/issues"
"""


def issue(iid, labels):
    return {"iid": iid, "title": "Issue %d" % iid, "state": "opened", "labels": labels}


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

    def run_with(self, issues):
        with open(os.path.join(self.tmp, "issues"), "w") as f:
            json.dump(issues, f)
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


if __name__ == "__main__":
    unittest.main()
