#!/usr/bin/env python3
"""`scripts/db-backup.sh` / `scripts/db-restore.sh` の .env の読み方の検証(#1603)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_db_backup_restore.py'

## なぜ Gherkin ではないのか

対象は開発者向けの運用スクリプトで、Web UI から到達する経路が無い。CLAUDE.md の Test-First が
認める「Web UI から到達できない基準はスクリプトレベルのテストで表現する」に当たる。

スクリプトは自分の位置から REPO_ROOT を求めるため、一時ディレクトリの scripts/ へ複製し、
そこに .env を置く。`docker` は PATH 上の偽物に差し替え、渡された引数と MYSQL_PWD を記録する。
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent

FAKE_DOCKER = r"""#!/bin/bash
echo "$*" >> "$FAKE_DIR/docker.log"
if [ "$1" = "exec" ]; then
  pwd_arg="<unset>"
  while [ $# -gt 0 ]; do
    if [ "$1" = "-e" ]; then
      case "$2" in MYSQL_PWD=*) pwd_arg="${2#MYSQL_PWD=}" ;; esac
    fi
    shift
  done
  echo "MYSQL_PWD=$pwd_arg" >> "$FAKE_DIR/exec.log"
  cat > /dev/null
fi
exit 0
"""

# .env.example 現行3行と同じ形(引用符なしで空白を含む)
SPACED = (
    "SAFETY_NEGATIVE_PROMPT_SEXUAL=nsfw, nude, naked\n"
    "SAFETY_NEGATIVE_PROMPT_VIOLENT=gore, gory\n"
    "SAFETY_NEGATIVE_PROMPT_DISCRIMINATORY=nazi symbol, hate symbol\n"
)


class DbScriptsEnvReading(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        (self.tmp / "scripts").mkdir()
        for name in ("db-backup.sh", "db-restore.sh"):
            shutil.copy(SCRIPTS / name, self.tmp / "scripts" / name)
        self.fake = self.tmp / "fakebin"
        self.fake.mkdir()
        d = self.fake / "docker"
        d.write_text(FAKE_DOCKER)
        d.chmod(0o755)
        self.dump = self.tmp / "dump.sql"
        self.dump.write_text("USE `lbs_identity`;\n")

    def run_script(self, name, env_text):
        (self.tmp / ".env").write_text(env_text)
        args = ["bash", str(self.tmp / "scripts" / name)]
        if name == "db-backup.sh":
            args.append(str(self.tmp / "out.sql"))
        else:
            args.append(str(self.dump))
        env = dict(os.environ, PATH=f"{self.fake}:{os.environ['PATH']}", FAKE_DIR=str(self.tmp))
        return subprocess.run(
            args, input="yes\n", capture_output=True, text=True, env=env
        )

    def exec_log(self):
        p = self.tmp / "exec.log"
        return p.read_text() if p.exists() else ""

    def test_backup_with_spaced_values(self):  # AC1
        r = self.run_script("db-backup.sh", SPACED + "MYSQL_ROOT_PASSWORD=s3cret\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("command not found", r.stderr)
        self.assertIn("MYSQL_PWD=s3cret", self.exec_log())

    def test_backup_with_quoted_password(self):  # AC1(引用符付き)
        r = self.run_script("db-backup.sh", SPACED + 'MYSQL_ROOT_PASSWORD="pa ss=word"\n')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("MYSQL_PWD=pa ss=word\n", self.exec_log())

    def test_restore_with_spaced_values(self):  # AC2
        r = self.run_script("db-restore.sh", SPACED + "MYSQL_ROOT_PASSWORD=s3cret\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("MYSQL_PWD=s3cret", self.exec_log())

    def test_restore_with_quoted_password(self):
        r = self.run_script("db-restore.sh", SPACED + "MYSQL_ROOT_PASSWORD='pa ss'\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("MYSQL_PWD=pa ss\n", self.exec_log())

    def test_missing_or_empty_password(self):  # AC3
        for name in ("db-backup.sh", "db-restore.sh"):
            for env_text in (SPACED, SPACED + "MYSQL_ROOT_PASSWORD=\n", SPACED + 'MYSQL_ROOT_PASSWORD=""\n'):
                with self.subTest(script=name, env=env_text[-30:]):
                    r = self.run_script(name, env_text)
                    self.assertNotEqual(r.returncode, 0)
                    self.assertIn("MYSQL_ROOT_PASSWORD", r.stderr)
                    self.assertEqual(self.exec_log(), "")


class EnvExample(unittest.TestCase):
    EXPECTED = {
        "SAFETY_NEGATIVE_PROMPT_SEXUAL": "nsfw, nude, naked, nudity, explicit, sexual, lewd, erotic, underwear, lingerie, cleavage",
        "SAFETY_NEGATIVE_PROMPT_VIOLENT": "gore, gory, mutilated, dismembered, corpse, blood, violence, decapitated",
        "SAFETY_NEGATIVE_PROMPT_DISCRIMINATORY": "nazi symbol, hate symbol, racist imagery, offensive stereotype",
    }

    def test_env_example_is_sourceable(self):  # AC4
        r = subprocess.run(
            ["bash", "-c", "set -euo pipefail; set -a; source .env.example"],
            cwd=REPO, capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_safety_values_unchanged(self):
        lines = (REPO / ".env.example").read_text().splitlines()
        for key, want in self.EXPECTED.items():
            line = next(l for l in lines if l.startswith(key + "="))
            val = line.split("=", 1)[1]
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
                val = val[1:-1]
            self.assertEqual(val, want, key)


if __name__ == "__main__":
    unittest.main()
