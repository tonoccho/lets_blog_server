#!/usr/bin/env python3
"""run-at-setup.sh(リセット → at-setup → 必ずシード)の検証(#1634)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_run_at_setup.py'

## なぜ Gherkin ではないのか

CLAUDE.md -> Test-First Implementation が認める「Web UI から到達できない基準」。
対象は受け入れテストを動かす入口スクリプトの手順と終了コードで、Playwright 自身を
包む側のため、Playwright のシナリオとしては観測できない(test_reset_acceptance_env.py と同じ例外)。

## どう検証するか

一時ディレクトリへ scripts/run-at-setup.sh と apps/web を置き、`npx`(bddgen / playwright)と
seed-acceptance-env.sh を、呼ばれた順序と環境を記録する偽物に差し替える。
排他ロックは AT_LOCK_FILE を一時ファイルへ向ける。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

FAKE_NPX = r"""#!/bin/bash
echo "npx $*" >> "$FAKE_LOG"
if [ "$1" = "playwright" ]; then
  echo "ACCEPTANCE_RESET=${ACCEPTANCE_RESET-<unset>}" >> "$FAKE_LOG"
  exit "${FAKE_PLAYWRIGHT_EXIT:-0}"
fi
exit 0
"""
FAKE_SEED = r"""#!/bin/bash
echo "seed" >> "$FAKE_LOG"
exit "${FAKE_SEED_EXIT:-0}"
"""


def write_exec(path, body):
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


class RunAtSetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "scripts"))
        os.makedirs(os.path.join(self.repo, "apps", "web"))
        shutil.copy(
            os.path.join(HERE, "run-at-setup.sh"),
            os.path.join(self.repo, "scripts", "run-at-setup.sh"),
        )
        write_exec(os.path.join(self.repo, "scripts", "seed-acceptance-env.sh"), FAKE_SEED)
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        write_exec(os.path.join(self.bin, "npx"), FAKE_NPX)
        self.log = os.path.join(self.tmp, "calls.log")

    def run_entry(self, **extra):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_LOG"] = self.log
        env["AT_LOCK_FILE"] = os.path.join(self.tmp, "at.lock")
        env.update(extra)
        return subprocess.run(
            ["bash", os.path.join(self.repo, "scripts", "run-at-setup.sh")],
            env=env, capture_output=True, text=True, timeout=60,
        )

    def calls(self):
        with open(self.log, encoding="utf-8") as f:
            return f.read().splitlines()

    def test_runs_setup_then_seed_and_succeeds(self):
        r = self.run_entry()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        calls = self.calls()
        pw = [i for i, c in enumerate(calls) if c.startswith("npx playwright")]
        seed = [i for i, c in enumerate(calls) if c == "seed"]
        self.assertEqual(len(pw), 1)
        self.assertEqual(len(seed), 1)
        self.assertLess(pw[0], seed[0])
        self.assertIn("--project=at-setup", calls[pw[0]])
        # リセットは Playwright の globalSetup(排他ロックの内側)で行わせる
        self.assertIn("ACCEPTANCE_RESET=data", calls)

    def test_setup_failure_still_seeds_and_exits_nonzero(self):
        r = self.run_entry(FAKE_PLAYWRIGHT_EXIT="1")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("seed", self.calls())

    def test_seed_failure_exits_nonzero_even_when_setup_passed(self):
        r = self.run_entry(FAKE_SEED_EXIT="1")
        self.assertNotEqual(r.returncode, 0)

    def test_setup_failure_exit_code_is_preserved(self):
        r = self.run_entry(FAKE_PLAYWRIGHT_EXIT="7")
        self.assertEqual(r.returncode, 7)

    def test_seed_is_not_run_through_the_at_seed_project(self):
        self.run_entry()
        self.assertFalse(any("at-seed" in c for c in self.calls()))

    def test_seed_waits_for_the_acceptance_lock(self):
        lock = os.path.join(self.tmp, "at.lock")
        holder = subprocess.Popen(["flock", lock, "sleep", "30"])
        self.addCleanup(holder.kill)
        # holder がロックを取るまで待つ
        for _ in range(50):
            if subprocess.run(["flock", "-n", lock, "true"]).returncode != 0:
                break
        r = subprocess.run(
            ["bash", os.path.join(self.repo, "scripts", "run-at-setup.sh")],
            env={**os.environ, "PATH": self.bin + os.pathsep + os.environ["PATH"],
                 "FAKE_LOG": self.log, "AT_LOCK_FILE": lock, "AT_LOCK_TIMEOUT_SECONDS": "1"},
            capture_output=True, text=True, timeout=60,
        )
        self.assertNotEqual(r.returncode, 0, "ロックを取れなければシードを走らせず非ゼロで終わる")
        self.assertNotIn("seed", self.calls())


if __name__ == "__main__":
    unittest.main()
