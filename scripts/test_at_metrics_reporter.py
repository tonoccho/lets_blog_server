#!/usr/bin/env python3
"""受け入れテスト実行時間ログ用 Playwright Reporter の検証(#1209)。

    python3 -m unittest scripts.test_at_metrics_reporter   # または discover

## なぜ Gherkin ではないのか

`CLAUDE.md` → Test-First Implementation は、製品から到達できない基準をサービスレベルの
テストで表現することを認めている。Reporter は製品の振る舞いではなく、利用者は受け入れテストの
実行ログを観測しない。Gherkin で書くと「受け入れテストを実行する受け入れテスト」という入れ子になる。

## 構成

- 構成検査: `playwright.config.ts` の配線とファイル名(`.spec.` / `.test.` を含まない)
- 挙動検査: ブラウザを使わない最小の Playwright 設定を一時ディレクトリに置いて実行し、JSONL を読む
  (AC1〜AC4)。スタック不要・数秒で終わる。

AC4 のパスに `/proc/` 配下を使ってはならない: `mkdirSync(recursive)` が戻ってこず、
try/catch では捕まえられないので Reporter の欠陥に見える偽陽性になる。
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
WEB = os.path.join(REPO_ROOT, "apps/web")
REPORTER = os.path.join(WEB, "e2e/reporters/at-metrics-reporter.ts")
PLAYWRIGHT = os.path.join(WEB, "node_modules/.bin/playwright")
CONFIG_TS = os.path.join(WEB, "playwright.config.ts")

CONFIG = """import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  reporter: [['dot'], [%r]],
  projects: [{ name: 'proj-a' }, { name: 'proj-b' }],
});
"""

SPEC = """import { test, expect } from '@playwright/test';
test('first scenario', async () => { expect(1).toBe(1); });
test('second scenario', async () => { expect(2).toBe(2); });
"""

FAILING_SPEC = """import { test, expect } from '@playwright/test';
test('passes', async () => { expect(1).toBe(1); });
test('fails', async () => { expect(1).toBe(2); });
"""


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


class WiringTest(unittest.TestCase):
    def test_reporter_file_exists_and_is_not_picked_up_as_a_test(self):
        self.assertTrue(os.path.isfile(REPORTER), REPORTER)
        name = os.path.basename(REPORTER)
        self.assertNotIn(".spec.", name)
        self.assertNotIn(".test.", name)

    def test_config_keeps_html_and_adds_reporter(self):
        with open(CONFIG_TS, encoding="utf-8") as f:
            text = f.read()
        self.assertIn(
            "reporter: [['html'], ['./e2e/reporters/at-metrics-reporter.ts']]", text
        )

    def test_reporters_dir_is_not_added_to_bdd_steps(self):
        with open(CONFIG_TS, encoding="utf-8") as f:
            text = f.read()
        self.assertNotIn("e2e/reporters/**", text)


@unittest.skipUnless(os.path.exists(PLAYWRIGHT), "apps/web/node_modules is not installed")
class BehaviourTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="at-metrics-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        os.symlink(os.path.join(WEB, "node_modules"), os.path.join(self.tmp, "node_modules"))
        self.write("pw.config.ts", CONFIG % REPORTER)
        self.write("a.spec.ts", SPEC)
        self.log = os.path.join(self.tmp, "logs", "at-runs.jsonl")

    def write(self, name, body):
        with open(os.path.join(self.tmp, name), "w", encoding="utf-8") as f:
            f.write(body)

    def run_pw(self, *args, log=None):
        env = dict(os.environ)
        env["AT_METRICS_LOG"] = log or self.log
        env.pop("CI", None)
        return subprocess.run(
            [PLAYWRIGHT, "test", "-c", "pw.config.ts", *args],
            cwd=self.tmp, env=env, capture_output=True, text=True, timeout=120,
        )

    def test_ac1_run_record_appended_with_required_fields(self):
        r = self.run_pw()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        recs = read_jsonl(self.log)
        runs = [x for x in recs if x["kind"] == "run"]
        self.assertEqual(len(runs), 1)
        run = runs[0]
        for key in ("durationMs", "status", "counts", "projects", "startedAt", "endedAt"):
            self.assertIn(key, run)
        self.assertEqual(run["status"], "passed")
        self.assertEqual(run["counts"], {"passed": 4})
        self.assertEqual(sorted(run["projects"]), ["proj-a", "proj-b"])
        begins = [x for x in recs if x["kind"] == "begin"]
        self.assertEqual(len(begins), 1)
        for key in ("run", "ts", "projects", "tests", "workers"):
            self.assertIn(key, begins[0])

    def test_ac2_only_selected_projects_are_recorded(self):
        r = self.run_pw("--project=proj-a")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        run = [x for x in read_jsonl(self.log) if x["kind"] == "run"][0]
        self.assertEqual(run["projects"], ["proj-a"])
        begin = [x for x in read_jsonl(self.log) if x["kind"] == "begin"][0]
        self.assertEqual(begin["projects"], ["proj-a"])

    def test_ac3_one_test_record_per_scenario(self):
        self.write("a.spec.ts", FAILING_SPEC)
        r = self.run_pw("--project=proj-a")
        self.assertNotEqual(r.returncode, 0)
        tests = [x for x in read_jsonl(self.log) if x["kind"] == "test"]
        self.assertEqual(len(tests), 2)
        by_title = {t["title"]: t for t in tests}
        self.assertEqual(by_title["passes"]["status"], "passed")
        self.assertEqual(by_title["fails"]["status"], "failed")
        for t in tests:
            self.assertIsInstance(t["durationMs"], (int, float))
            self.assertEqual(t["project"], "proj-a")
            self.assertIn("a.spec.ts", t["file"])
            self.assertIn("retry", t)
        run = [x for x in read_jsonl(self.log) if x["kind"] == "run"][0]
        self.assertEqual(run["status"], "failed")

    def assert_same_outcome(self, bad_log, spec=None, expected_rc=0):
        if spec:
            self.write("a.spec.ts", spec)
        baseline = self.run_pw(log=os.path.join(self.tmp, "ok", "x.jsonl"))
        self.assertEqual(baseline.returncode, expected_rc, baseline.stdout + baseline.stderr)
        self.assertTrue(os.path.exists(os.path.join(self.tmp, "ok", "x.jsonl")))
        broken = self.run_pw(log=bad_log)
        self.assertEqual(broken.returncode, baseline.returncode, broken.stdout + broken.stderr)
        self.assertEqual(self.summary(broken.stdout), self.summary(baseline.stdout))
        self.assertNotIn("EACCES", broken.stdout + broken.stderr)
        self.assertNotIn("ENOSPC", broken.stdout + broken.stderr)

    @staticmethod
    def summary(out):
        # 所要時間 "(606ms)" は実行ごとに変わるので比較から除く
        return [re.sub(r"\s*\(\d[\d.]*m?s\)", "", l.strip())
                for l in out.splitlines() if "passed" in l or "failed" in l]

    def test_ac4_eacces_does_not_change_outcome(self):
        ro = os.path.join(self.tmp, "ro")
        os.mkdir(ro)
        os.chmod(ro, 0o500)
        self.addCleanup(os.chmod, ro, 0o700)
        if os.access(ro, os.W_OK):
            self.skipTest("running as a user that ignores directory permissions")
        self.assert_same_outcome(os.path.join(ro, "sub", "x.jsonl"))

    def test_ac4_eacces_with_failing_tests_keeps_exit_code(self):
        ro = os.path.join(self.tmp, "ro")
        os.mkdir(ro)
        os.chmod(ro, 0o500)
        self.addCleanup(os.chmod, ro, 0o700)
        if os.access(ro, os.W_OK):
            self.skipTest("running as a user that ignores directory permissions")
        self.assert_same_outcome(os.path.join(ro, "sub", "x.jsonl"), spec=FAILING_SPEC, expected_rc=1)

    @unittest.skipUnless(os.path.exists("/dev/full"), "/dev/full unavailable")
    def test_ac4_enospc_does_not_change_outcome(self):
        self.assert_same_outcome("/dev/full")


if __name__ == "__main__":
    unittest.main()
