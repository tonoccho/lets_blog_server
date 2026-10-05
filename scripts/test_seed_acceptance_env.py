#!/usr/bin/env python3
"""seed-acceptance-env.sh の管理者資格情報のフォールバックの検証(#1634)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_seed_acceptance_env.py'

## なぜ Gherkin ではないのか

CLAUDE.md -> Test-First Implementation が認める「Web UI から到達できない基準」。
対象は受け入れテスト環境を整えるスクリプト自身の終了コードと、子プロセスへ渡す環境変数で
あり、Playwright のシナリオとして観測できない(test_reset_acceptance_env.py と同じ例外)。

## どう検証するか

scripts/ とダミーの .env を一時ディレクトリへ置き、`docker` / `curl` を PATH 上の偽物に
差し替え、provision-e2e-keycloak-users.sh を「受け取った環境変数を記録するだけ」の偽物に
差し替える。実スタックにも実 .env にも触れない。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

FAKE_DOCKER = r"""#!/bin/bash
case "$*" in
  *"kcadm.sh get users"*) echo '[{"id":"u-1"}]' ;;
esac
exit 0
"""
FAKE_CURL = "#!/bin/bash\necho '{\"needsSetup\": false}'\n"
FAKE_PROVISION = r"""#!/bin/bash
{
  echo "EMAIL=${E2E_PROVISION_ADMIN_EMAIL-<unset>}"
  echo "PASSWORD=${E2E_PROVISION_ADMIN_PASSWORD-<unset>}"
} > "$FAKE_PROVISION_LOG"
exit "${FAKE_PROVISION_EXIT:-0}"
"""


def write_exec(path, body):
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


class SeedAdminFallbackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "scripts"))
        shutil.copy(
            os.path.join(HERE, "seed-acceptance-env.sh"),
            os.path.join(self.repo, "scripts", "seed-acceptance-env.sh"),
        )
        write_exec(
            os.path.join(self.repo, "scripts", "provision-e2e-keycloak-users.sh"), FAKE_PROVISION
        )
        with open(os.path.join(self.repo, ".env"), "w", encoding="utf-8") as f:
            f.write("KEYCLOAK_ADMIN_USERNAME=admin\nKEYCLOAK_ADMIN_PASSWORD=admin\n")
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        write_exec(os.path.join(self.bin, "docker"), FAKE_DOCKER)
        write_exec(os.path.join(self.bin, "curl"), FAKE_CURL)
        self.provision_log = os.path.join(self.tmp, "provision.log")

    def run_seed(self, extra_env):
        env = {k: v for k, v in os.environ.items() if not k.startswith("E2E_")}
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_PROVISION_LOG"] = self.provision_log
        env.update(extra_env)
        return subprocess.run(
            ["bash", os.path.join(self.repo, "scripts", "seed-acceptance-env.sh")],
            env=env, capture_output=True, text=True, timeout=60,
        )

    def provision_env(self):
        with open(self.provision_log, encoding="utf-8") as f:
            return dict(line.split("=", 1) for line in f.read().splitlines())

    def test_falls_back_to_e2e_admin_when_provision_vars_are_unset(self):
        r = self.run_seed({"E2E_TEST_PASSWORD": "tp", "E2E_ADMIN_PASSWORD": "ap"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        got = self.provision_env()
        self.assertEqual(got["EMAIL"], "e2e-admin@letsblog.local")
        self.assertEqual(got["PASSWORD"], "ap")

    def test_explicit_provision_vars_win_over_the_fallback(self):
        r = self.run_seed({
            "E2E_TEST_PASSWORD": "tp", "E2E_ADMIN_PASSWORD": "ap",
            "E2E_PROVISION_ADMIN_EMAIL": "boss@example.com",
            "E2E_PROVISION_ADMIN_PASSWORD": "pp",
        })
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        got = self.provision_env()
        self.assertEqual(got["EMAIL"], "boss@example.com")
        self.assertEqual(got["PASSWORD"], "pp")

    def test_missing_test_password_fails(self):
        r = self.run_seed({"E2E_ADMIN_PASSWORD": "ap"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("E2E_TEST_PASSWORD", r.stderr)
        self.assertFalse(os.path.exists(self.provision_log))

    def test_missing_admin_password_fails(self):
        r = self.run_seed({"E2E_TEST_PASSWORD": "tp"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("E2E_ADMIN_PASSWORD", r.stderr)
        self.assertFalse(os.path.exists(self.provision_log))

    def test_provision_failure_makes_seed_fail(self):
        r = self.run_seed({
            "E2E_TEST_PASSWORD": "tp", "E2E_ADMIN_PASSWORD": "ap", "FAKE_PROVISION_EXIT": "3",
        })
        self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
