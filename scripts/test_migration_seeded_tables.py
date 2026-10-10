"""ゼロ構築の検証が「マイグレーションが入れる行」を残骸と取り違えないことの単体テスト(#1738)。

`scripts/lib/migration-seeded-tables.sh` が、マイグレーション(Flyway)が行を入れる表の一覧を
1 か所で持つ。`rebuild-acceptance-env.sh` と `reset-acceptance-env.sh` の両方がそれを使う。
マイグレーションが行を入れる表が増えたとき、リリース検証(受け入れテスト 1 本も流れない段階)
で初めて気づくのではなく、このテストで気づく。
"""

import glob
import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
LIB = os.path.join(HERE, "lib", "migration-seeded-tables.sh")
SCRIPTS = ("rebuild-acceptance-env.sh", "reset-acceptance-env.sh")

INSERT_RE = re.compile(r"^\s*INSERT\s+(?:IGNORE\s+)?INTO\s+`?(\w+)`?", re.IGNORECASE | re.MULTILINE)


def migration_inserted_tables():
    """各サービスの db/migration/*.sql が INSERT する表 -> それを書いているファイルの一覧。"""
    found = {}
    pattern = os.path.join(REPO_ROOT, "services", "*", "src", "main", "resources", "db", "migration", "*.sql")
    for path in sorted(glob.glob(pattern)):
        with open(path, encoding="utf-8") as f:
            body = re.sub(r"--[^\n]*", "", f.read())
        for table in INSERT_RE.findall(body):
            found.setdefault(table, []).append(os.path.relpath(path, REPO_ROOT))
    return found


def bash(snippet):
    return subprocess.run(
        ["bash", "-c", 'source "%s"; %s' % (LIB, snippet)],
        capture_output=True,
        text=True,
        timeout=30,
    )


class MigrationInsertsAreCoveredByTheAllowlist(unittest.TestCase):
    def test_the_scan_finds_the_known_seeded_tables(self):
        """走査が空振りして「全部入っている」と誤って通ることを防ぐ。"""
        tables = migration_inserted_tables()
        for expected in ("roles", "role_permissions", "initial_setup_lock"):
            self.assertIn(expected, tables)

    def test_every_table_a_migration_inserts_into_is_listed(self):
        r = bash('printf "%s\\n" "${MIGRATION_SEEDED_TABLES[@]}"')
        self.assertEqual(0, r.returncode, r.stderr)
        listed = {entry.split(":")[0] for entry in r.stdout.split()}
        missing = {t: files for t, files in migration_inserted_tables().items() if t not in listed}
        self.assertEqual(
            {},
            missing,
            "マイグレーションが行を入れる表が scripts/lib/migration-seeded-tables.sh の "
            "MIGRATION_SEEDED_TABLES に無い(ゼロ構築の検証が残骸と判定する): %s" % sorted(missing),
        )


class AllowlistHelpers(unittest.TestCase):
    def test_initial_setup_lock_allows_exactly_one_row(self):
        self.assertEqual("1", bash("migration_seeded_max_rows initial_setup_lock").stdout.strip())

    def test_unlimited_tables_report_no_limit(self):
        self.assertEqual("*", bash("migration_seeded_max_rows roles").stdout.strip())

    def test_a_table_not_in_the_list_allows_no_rows(self):
        self.assertEqual("0", bash("migration_seeded_max_rows some_domain_table").stdout.strip())

    def test_sql_list_skips_flyway_history_and_unlimited_tables_only(self):
        out = bash("migration_seeded_not_in_sql").stdout.strip()
        self.assertIn("'flyway_schema_history'", out)
        self.assertIn("'roles'", out)
        self.assertIn("'role_permissions'", out)
        self.assertNotIn("initial_setup_lock", out)  # 行数まで確かめる表は数える


class BothScriptsShareTheSingleDefinition(unittest.TestCase):
    def test_scripts_source_the_library_and_do_not_keep_their_own_list(self):
        for name in SCRIPTS:
            with self.subTest(script=name):
                with open(os.path.join(HERE, name), encoding="utf-8") as f:
                    body = f.read()
                self.assertIn("lib/migration-seeded-tables.sh", body)
                self.assertNotIn("'role_permissions'", body)
                self.assertNotIn("MIGRATION_SEEDED_TABLES=(", body)


if __name__ == "__main__":
    unittest.main()
