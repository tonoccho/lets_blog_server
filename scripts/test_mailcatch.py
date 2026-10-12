#!/usr/bin/env python3
"""メールキャッチャーが Penpot と無関係な常時起動の `mailcatch` であることを検査する(#1748)。

    python3 -m unittest scripts.test_mailcatch

Keycloak のパスワードリセットメールの送信先は、名前に反して Penpot 用に見える
`penpot-mailcatch` だった。Penpot を消してもメールが止まらないよう、改名して常時起動にし、
Keycloak の realm もそちらを向ける。
"""

import json
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))


def compose_config(*extra):
    r = subprocess.run(
        ["docker", "compose", *extra, "config", "--format", "json"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)["services"]


class MailcatchService(unittest.TestCase):
    def test_default_services_have_mailcatch_and_not_penpot_mailcatch(self):
        r = subprocess.run(
            ["docker", "compose", "config", "--services"],
            cwd=REPO_ROOT, capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 0, r.stderr)
        services = r.stdout.split()
        self.assertIn("mailcatch", services)
        self.assertNotIn("penpot-mailcatch", services)

    def test_mailcatch_has_no_profile_and_no_penpot_dependency(self):
        svc = compose_config()["mailcatch"]
        self.assertFalse(svc.get("profiles"))
        depends = svc.get("depends_on") or {}
        self.assertEqual([d for d in depends if d.startswith("penpot")], [])

    def test_container_name(self):
        self.assertEqual(compose_config()["mailcatch"]["container_name"], "lbs-mailcatch")


class KeycloakRealm(unittest.TestCase):
    def test_smtp_host_is_mailcatch(self):
        with open(os.path.join(REPO_ROOT, "infra/keycloak/realm-export.json"), encoding="utf-8") as f:
            realm = json.load(f)
        self.assertEqual(realm["smtpServer"]["host"], "mailcatch")


if __name__ == "__main__":
    unittest.main()
