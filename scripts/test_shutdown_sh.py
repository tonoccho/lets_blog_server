#!/usr/bin/env python3
"""`shutdown.sh`(リポジトリ直下)の検証(#1528)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_shutdown_sh.py'

`CLAUDE.md` → **Test-First Implementation** の例外(Web UI から到達できない基準は
スクリプトレベルのテストで表現する)に従う。`startup.sh`(`test_startup_sh.py`)と同じ方式で、
一時ディレクトリに最小のリポジトリを作り、`docker` を PATH 上の偽物に差し替えて呼び出しを記録する。

## 対象外(本物のスタックが必要で QA で確認する)

停止後に名前付きボリュームが残ること、再起動後にデータが残ることは QA で確認する。
ここでは `down` に `-v` / `--volumes` を渡さないこと、確認を拒否すると `down` を呼ばないことを検証する。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SHUTDOWN_SCRIPT = os.path.join(REPO_ROOT, "shutdown.sh")
COMPOSE_LIB = os.path.join(REPO_ROOT, "scripts", "lib", "compose-project.sh")

FAKE_DOCKER = """#!/bin/bash
echo "docker $*" >> "$FAKE_LOG"
case "$*" in
  *" ps -q"*) [ -n "${FAKE_RUNNING:-}" ] && echo abc123; exit 0 ;;
  *"config --volumes"*) printf 'mysql_data\\nkeycloak_postgres\\ngenerated_images\\n'; exit 0 ;;
  *" down"*) [ -n "${FAKE_DOWN_FAIL:-}" ] && exit 1; exit 0 ;;
esac
exit 0
"""


class ShutdownShTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = os.path.join(self.tmp, "repo")
        self.bin = os.path.join(self.tmp, "bin")
        for d in (self.bin, os.path.join(self.repo, "scripts", "lib")):
            os.makedirs(d)
        self.log = os.path.join(self.tmp, "fake.log")
        open(self.log, "w").close()
        path = os.path.join(self.bin, "docker")
        with open(path, "w") as f:
            f.write(FAKE_DOCKER)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
        shutil.copy(COMPOSE_LIB, os.path.join(self.repo, "scripts", "lib", "compose-project.sh"))
        open(os.path.join(self.repo, "docker-compose.yml"), "w").close()
        if os.path.exists(SHUTDOWN_SCRIPT):
            shutil.copy(SHUTDOWN_SCRIPT, os.path.join(self.repo, "shutdown.sh"))

    def shutdown(self, *args, stdin="", running=True, extra_env=None):
        env = dict(os.environ)
        env.update(PATH=self.bin + ":" + os.environ["PATH"], FAKE_LOG=self.log)
        env.pop("COMPOSE_PROJECT_NAME", None)
        if running:
            env["FAKE_RUNNING"] = "1"
        env.update(extra_env or {})
        return subprocess.run(["bash", os.path.join(self.repo, "shutdown.sh"), *args],
                              cwd=self.repo, env=env, text=True, input=stdin,
                              capture_output=True)

    def logged(self):
        with open(self.log) as f:
            return f.read()

    def downs(self):
        return [l for l in self.logged().splitlines() if " down" in l]

    def test_help_exits_zero_without_side_effects(self):
        r = self.shutdown("--help")
        self.assertEqual(r.returncode, 0)
        self.assertIn("--volumes", r.stdout)
        self.assertEqual(self.logged(), "")

    def test_unknown_argument_fails(self):
        r = self.shutdown("--bogus")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.logged(), "")

    def test_short_v_alias_is_rejected(self):
        r = self.shutdown("-v")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.downs(), [])

    def test_default_runs_down_without_volume_removal(self):
        r = self.shutdown()
        self.assertEqual(r.returncode, 0, r.stderr)
        downs = self.downs()
        self.assertEqual(len(downs), 1)
        self.assertNotIn(" -v", downs[0])
        self.assertNotIn("--volumes", downs[0])
        self.assertIn("-f " + os.path.join(self.repo, "docker-compose.yml"), downs[0])

    def test_default_failure_propagates(self):
        r = self.shutdown(extra_env={"FAKE_DOWN_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)

    def test_not_running_reports_and_exits_zero_without_down(self):
        r = self.shutdown(running=False)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("起動していません", r.stdout)
        self.assertEqual(self.downs(), [])

    def test_volumes_lists_names_and_declined_removes_nothing(self):
        r = self.shutdown("--volumes", stdin="no\n")
        self.assertNotEqual(r.returncode, 0)
        for name in ("mysql_data", "keycloak_postgres", "generated_images"):
            self.assertIn(name, r.stdout)
        self.assertEqual(self.downs(), [])

    def test_volumes_empty_stdin_is_a_refusal(self):
        r = self.shutdown("--volumes", stdin="")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.downs(), [])

    def test_volumes_confirmed_runs_down_with_volumes(self):
        r = self.shutdown("--volumes", stdin="yes\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        downs = self.downs()
        self.assertEqual(len(downs), 1)
        self.assertIn("--volumes", downs[0])

    def test_volumes_confirmed_failure_propagates(self):
        r = self.shutdown("--volumes", stdin="yes\n", extra_env={"FAKE_DOWN_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)

    def test_volumes_when_not_running_still_asks(self):
        r = self.shutdown("--volumes", stdin="no\n", running=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("mysql_data", r.stdout)
        self.assertEqual(self.downs(), [])


if __name__ == "__main__":
    unittest.main()
