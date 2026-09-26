#!/usr/bin/env python3
"""`scripts/e2e-cleanup-test-data.sh` の identity ユーザー掃除の検証(#1193)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_e2e_cleanup_identity_users.py'

## なぜ Gherkin ではないのか

対象は E2E 後の孤児データを掃除する運用スクリプトで、Web UI から到達する経路が無い。
CLAUDE.md の Test-First が認める「Web UI から到達できない基準はスクリプトレベルの
テストで表現する」に当たる。黙って省略しているのではない。

`docker` を PATH 上の偽物に差し替え、mysql へ流れた SQL と Keycloak(kcadm)へ発行された
コマンドを記録して検証する。
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "e2e-cleanup-test-data.sh"

FAKE_DOCKER = r"""#!/bin/bash
echo "$*" >> "$FAKE_DIR/docker.log"
case "$1" in
  inspect)
    for missing in $FAKE_MISSING_CONTAINERS; do
      [ "$2" = "$missing" ] && exit 1
    done
    exit 0
    ;;
  exec)
    shift
    while [[ "$1" == -* ]]; do
      case "$1" in -e) shift 2 ;; *) shift ;; esac
    done
    container="$1"; shift
    if [ "$container" = "lbs-mysql" ]; then
      cat >> "$FAKE_DIR/mysql.sql"
      exit 0
    fi
    if [ "$container" = "lbs-keycloak" ]; then
      shift # kcadm.sh のパス
      case "$1" in
        get) cat "$FAKE_DIR/kc_users.json" ;;
        delete)
          echo "$2" >> "$FAKE_DIR/kc_deleted.log"
          [ "$2" = "users/$FAKE_KC_FAIL_ID" ] && exit 1
          exit 0
          ;;
      esac
      exit 0
    fi
    exit 0
    ;;
esac
exit 0
"""

KC_USERS = [
    {"id": "k-target-1", "username": "e2e-1160-target-1@example.com", "email": "e2e-1160-target-1@example.com"},
    {"id": "k-target-2", "username": "e2e-1160-authz-2@example.com", "email": "e2e-1160-authz-2@example.com"},
    {"id": "k-real", "username": "alice@example.com", "email": "alice@example.com"},
    {"id": "k-other-domain", "username": "e2e-x@letsblog.local", "email": "e2e-x@letsblog.local"},
    {"id": "k-not-prefixed", "username": "my-e2e-x@example.com", "email": "my-e2e-x@example.com"},
]


class CleanupIdentityUsers(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        (self.tmp / "scripts").mkdir()
        shutil.copy(SCRIPT, self.tmp / "scripts" / SCRIPT.name)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(FAKE_DOCKER)
        docker.chmod(0o755)
        self.fake = self.tmp / "fake"
        self.fake.mkdir()
        (self.fake / "kc_users.json").write_text(json.dumps(KC_USERS))
        self.write_env("MYSQL_ROOT_PASSWORD=pw\nKEYCLOAK_ADMIN_USERNAME=admin\nKEYCLOAK_ADMIN_PASSWORD=secret\n")

    def write_env(self, body):
        (self.tmp / ".env").write_text(body)

    def run_script(self, *args, missing="lbs-wordpress", fail_id=""):
        env = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "FAKE_DIR": str(self.fake),
            "FAKE_MISSING_CONTAINERS": missing,
            "FAKE_KC_FAIL_ID": fail_id,
        }
        return subprocess.run(
            ["bash", str(self.tmp / "scripts" / SCRIPT.name), *args],
            env=env, capture_output=True, text=True, timeout=60,
        )

    def read(self, name):
        path = self.fake / name
        return path.read_text() if path.exists() else ""

    def kc_deleted(self):
        return self.read("kc_deleted.log").split()

    def test_apply_deletes_matching_users_from_local_db(self):
        r = self.run_script("--yes")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn(
            "DELETE FROM lbs_identity.users WHERE email LIKE 'e2e-%@example.com';",
            self.read("mysql.sql"),
        )

    def test_apply_deletes_matching_users_from_keycloak(self):
        r = self.run_script("--yes")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(sorted(self.kc_deleted()), ["users/k-target-1", "users/k-target-2"])
        self.assertIn("-r letsblog", self.read("docker.log"))

    def test_keycloak_user_listing_is_not_capped_at_the_default_100(self):
        self.run_script("--yes")
        list_calls = [l for l in self.read("docker.log").splitlines() if " get users " in l]
        self.assertEqual(len(list_calls), 1, list_calls)
        self.assertIn("search=e2e-", list_calls[0])
        self.assertRegex(list_calls[0], r"max=\d{4,}")

    def test_sql_only_references_schemas_that_exist_after_the_service_split(self):
        # 旧スキーマ lets_blog は #786 以降の環境に存在せず、参照すると mysql が
        # "Unknown database" で中断して lbs_identity.users の削除まで到達しない。
        self.run_script("--yes")
        sql = self.read("mysql.sql")
        self.assertNotIn("lets_blog.", sql)
        self.assertIn("DELETE FROM lbs_identity.project_users", sql)
        self.assertIn("DELETE FROM lbs_identity.user_site_authors", sql)

    def test_project_and_site_rows_are_deleted_before_identity_users(self):
        self.run_script("--yes")
        sql = self.read("mysql.sql")
        self.assertLess(sql.index("DELETE FROM lbs_identity.project_users"),
                        sql.index("DELETE FROM lbs_identity.users"))

    def test_apply_does_not_delete_non_e2e_keycloak_users(self):
        self.run_script("--yes")
        deleted = self.kc_deleted()
        for real in ("k-real", "k-other-domain", "k-not-prefixed"):
            self.assertNotIn(f"users/{real}", deleted)

    def test_dry_run_counts_but_deletes_nothing(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        sql = self.read("mysql.sql")
        self.assertIn("FROM lbs_identity.users WHERE email LIKE 'e2e-%@example.com'", sql)
        self.assertNotIn("DELETE FROM lbs_identity.users", sql)
        self.assertEqual(self.kc_deleted(), [])
        self.assertIn("e2e-1160-target-1@example.com", r.stdout)
        self.assertNotIn("alice@example.com", r.stdout)

    def test_missing_keycloak_container_warns_and_still_cleans_db(self):
        r = self.run_script("--yes", missing="lbs-wordpress lbs-keycloak")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("lbs-keycloak", r.stderr)
        self.assertEqual(self.kc_deleted(), [])
        self.assertIn("DELETE FROM lbs_identity.users", self.read("mysql.sql"))

    def test_missing_keycloak_credentials_warns_and_still_cleans_db(self):
        self.write_env("MYSQL_ROOT_PASSWORD=pw\n")
        r = self.run_script("--yes")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("KEYCLOAK_ADMIN", r.stderr)
        self.assertEqual(self.kc_deleted(), [])
        self.assertIn("DELETE FROM lbs_identity.users", self.read("mysql.sql"))

    def test_keycloak_delete_failure_warns_and_continues(self):
        r = self.run_script("--yes", fail_id="k-target-1")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("e2e-1160-target-1@example.com", r.stderr)
        self.assertIn("users/k-target-2", self.kc_deleted())
        self.assertIn("DELETE FROM lbs_identity.users", self.read("mysql.sql"))


if __name__ == "__main__":
    unittest.main()
