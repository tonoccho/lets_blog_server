#!/usr/bin/env python3
"""`scripts/check-test-db.sh --reachability` の検証(#1109)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_check_test_db.py'

## なぜ Gherkin ではないのか

対象はホストの `./gradlew test` 前に走る開発者向けの到達性検査(運用系の振る舞い)で、
Web UI から到達する経路が無い。CLAUDE.md の Test-First が認める「Web UI から到達できない
基準はスクリプトレベルのテストで表現する」に当たる。黙って省略しているのではない。
"""

import os
import socket
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check-test-db.sh"
RECOVERY = "docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql"


def run(port, host="127.0.0.1", extra=None):
    env = {**os.environ, "TEST_DB_HOST": host, "TEST_DB_PORT": str(port)}
    env.pop("TEST_DB_USER", None)
    return subprocess.run(
        ["bash", str(SCRIPT), "--reachability", *(extra or [])],
        env=env, capture_output=True, text=True, timeout=30,
    )


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ReachabilityMode(unittest.TestCase):
    def test_reachable_port_exits_zero(self):
        with socket.socket() as srv:
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            r = run(srv.getsockname()[1])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_reachable_does_not_check_schemas_or_login(self):
        with socket.socket() as srv:
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            r = run(srv.getsockname()[1])
        self.assertNotIn("ログイン", r.stdout)
        self.assertNotIn("スキーマ", r.stdout)

    def test_unreachable_port_exits_one_with_environment_message(self):
        r = run(free_port())
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("環境要因", r.stdout)
        self.assertIn("3306が公開されていない", r.stdout)
        self.assertIn(RECOVERY, r.stdout)

    def test_custom_port_is_reported_and_probed(self):
        port = free_port()
        r = run(port)
        self.assertIn(f"127.0.0.1:{port}", r.stdout)

    def test_port_only_alias(self):
        env = {**os.environ, "TEST_DB_PORT": str(free_port())}
        r = subprocess.run(["bash", str(SCRIPT), "--port-only"], env=env,
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 1)
        self.assertIn("環境要因", r.stdout)


CONTAINER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
SCHEMAS = ["lbs_identity_test", "lbs_project_test", "lbs_content_test", "lbs_media_test",
           "lbs_ai_test", "lbs_analytics_test", "lbs_publishing_test", "lbs_platform_test",
           "lbs_log_test"]

FAKE_MYSQL = r"""#!/bin/bash
case "$*" in
  *"@@hostname"*) echo "$FAKE_HOSTNAME" ;;
  *"SHOW DATABASES"*) printf '%s\n' $FAKE_SCHEMAS ;;
esac
exit 0
"""
FAKE_DOCKER = r"""#!/bin/bash
if [ "$1" = inspect ]; then
  [ -n "$FAKE_CONTAINER_ID" ] || exit 1
  echo "$FAKE_CONTAINER_ID"
  exit 0
fi
exit 0
"""


class FullModeIdentityCheck(unittest.TestCase):
    """#1769: 3306 の向こうが今の lbs-mysql であることを @@hostname で確かめる。

    ホストの実際の 3306 に依存しないよう、到達性の `timeout 5 bash -c ...` は偽物で成功させる。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(__import__("shutil").rmtree, self.tmp, True)
        for name, body in (("mysql", FAKE_MYSQL), ("docker", FAKE_DOCKER),
                           ("timeout", "#!/bin/bash\nexit 0\n")):
            p = os.path.join(self.tmp, name)
            with open(p, "w", encoding="utf-8") as f:
                f.write(body)
            os.chmod(p, os.stat(p).st_mode | stat.S_IXUSR)

    def run_full(self, hostname, container_id=CONTAINER_ID, args=(), env_extra=None):
        env = {**os.environ, "PATH": self.tmp + os.pathsep + os.environ["PATH"],
               "FAKE_HOSTNAME": hostname, "FAKE_CONTAINER_ID": container_id,
               "FAKE_SCHEMAS": " ".join(SCHEMAS)}
        for k in ("TEST_DB_HOST", "TEST_DB_PORT", "TEST_DB_USER", "TEST_DB_PASSWORD"):
            env.pop(k, None)
        env.update(env_extra or {})
        return subprocess.run(["bash", str(SCRIPT), *args], env=env,
                              capture_output=True, text=True, timeout=30)

    def test_matching_hostname_exits_zero(self):
        r = self.run_full(CONTAINER_ID[:12])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_mismatching_hostname_exits_nonzero_with_message(self):
        r = self.run_full("abcdefabcdef")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("今の lbs-mysql ではない", r.stdout)

    def test_reachability_mode_does_not_compare(self):
        r = self.run_full("abcdefabcdef", args=("--reachability",))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_explicit_test_db_host_does_not_compare(self):
        r = self.run_full("abcdefabcdef", env_extra={"TEST_DB_HOST": "127.0.0.1"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_explicit_test_db_port_does_not_compare(self):
        r = self.run_full("abcdefabcdef", env_extra={"TEST_DB_PORT": "3306"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_missing_lbs_mysql_does_not_compare(self):
        r = self.run_full("abcdefabcdef", container_id="")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
