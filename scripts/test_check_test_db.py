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
import subprocess
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


if __name__ == "__main__":
    unittest.main()
