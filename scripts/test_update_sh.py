#!/usr/bin/env python3
"""`update.sh`(リポジトリ直下)の検証(#962)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_update_sh.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** の例外(Web UI から到達できない基準は
スクリプトレベルのテストで表現する)に従う。`update.sh` は git と docker compose を叩く
運用シェルスクリプトで、Web UI からは到達できない。`setup.sh`(`test_setup_sh.py`)と同じ扱い。

## どう検証するか

一時ディレクトリに bare の origin と作業クローンを作り(本物の git を使う)、
`docker` だけを PATH 上の偽物に差し替えて呼び出しを FAKE_LOG に記録する。
`scripts/wait-for-stack-healthy.sh` も作業クローン内の偽物にして、終了コードの伝播を見る。

## 対象外(ユニットテストでは到達できない受入基準)

「更新前に存在したデータ(ユーザー・プロジェクト・投稿)が更新後も残っている」は
本物の Docker ボリュームと稼働スタックが必要。ここでは `update.sh` が
`docker compose down` / `-v` / `volume rm` を一切呼ばないこと(データを消す経路が無いこと)
だけをテストし、実データの残存は実環境での手動確認に委ねる。
"""

import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
UPDATE_SCRIPT = os.path.join(REPO_ROOT, "update.sh")

FAKE_DOCKER = """#!/bin/bash
echo "docker $* [DOCKER_CONFIG=${DOCKER_CONFIG:-}]" >> "$FAKE_LOG"
if [ -n "${FAKE_DOCKER_FAIL:-}" ]; then exit 1; fi
exit 0
"""

FAKE_WAIT = """#!/bin/bash
echo "wait $*" >> "$FAKE_LOG"
if [ -n "${FAKE_WAIT_FAIL:-}" ]; then
  echo "サービス 'web' が healthy になりませんでした" >&2
  exit 1
fi
exit 0
"""

FAKE_BACKUP = """#!/bin/bash
echo "backup $*" >> "$FAKE_LOG"
if [ -n "${FAKE_BACKUP_FAIL:-}" ]; then
  echo "エラー: コンテナ lbs-mysql が見つかりません" >&2
  exit 1
fi
mkdir -p "$(dirname "$1")"
echo "-- dump" > "$1"
echo "バックアップを作成しました: $1"
exit 0
"""

# .env 追随チェック(#1254)用の縮小版 .env.example と、それに揃った .env。
ENV_EXAMPLE = "A_KEY=1\nDB_PASSWORD=changeme_db\nAPI_KEY=\n"
ENV_FILE = "A_KEY=1\nDB_PASSWORD=my-own-secret\nAPI_KEY=\n"
APP_KEY_PLACEHOLDER = "UkVQTEFDRV9XSVRIX09QRU5TU0xfUkFORF9CNjRfMzI="

JAVA_SERVICES = ["gateway", "identity", "project", "content", "media", "ai",
                 "publishing", "analytics", "platform", "log-writer"]
ALL_BUILD_SERVICES = ["web"] + JAVA_SERVICES + ["wordpress"]


def compose_yaml():
    """実物の docker-compose.yml と同じ build: の形(12個)だけを持つ縮小版。"""
    out = ["services:"]
    for n in ALL_BUILD_SERVICES:
        out.append("  %s:" % n)
        out.append("    image: x/%s" % n)
        out.append("    build:")
        if n == "web":
            out += ["      context: ./apps/web", "      dockerfile: Dockerfile"]
        elif n == "wordpress":
            out += ["      context: ./infra/wordpress", "      dockerfile: Dockerfile"]
        else:
            out += ["      context: .", "      dockerfile: services/%s/Dockerfile" % n]
        out.append("    restart: always")
    out += ["  mysql:", "    image: mysql:8", "volumes:", "  data:"]
    return "\n".join(out) + "\n"


def run(cmd, cwd, env=None, check=True):
    return subprocess.run(cmd, cwd=cwd, env=env, check=check, text=True,
                          capture_output=True)


def git(cwd, *args):
    return run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *args], cwd)


class UpdateShTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.origin = os.path.join(self.tmp, "origin.git")
        self.repo = os.path.join(self.tmp, "repo")
        self.other = os.path.join(self.tmp, "other")
        self.bin = os.path.join(self.tmp, "bin")
        self.home = os.path.join(self.tmp, "home")
        self.env_path = os.path.join(self.repo, ".env")
        os.makedirs(self.bin)
        os.makedirs(self.home)
        self.log = os.path.join(self.tmp, "fake.log")
        open(self.log, "w").close()

        run(["git", "init", "--bare", "-b", "develop", self.origin], self.tmp)
        run(["git", "init", "-b", "develop", self.repo], self.tmp)
        os.makedirs(os.path.join(self.repo, "scripts"))
        self._write(os.path.join(self.repo, "scripts", "wait-for-stack-healthy.sh"), FAKE_WAIT)
        self._write(os.path.join(self.repo, "scripts", "db-backup.sh"), FAKE_BACKUP)
        self._write(os.path.join(self.bin, "docker"), FAKE_DOCKER)
        shutil.copy(os.path.join(REPO_ROOT, "scripts", "check-env.sh"),
                    os.path.join(self.repo, "scripts", "check-env.sh"))
        shutil.copytree(os.path.join(REPO_ROOT, "scripts", "lib"),
                        os.path.join(self.repo, "scripts", "lib"))
        with open(os.path.join(self.repo, ".env.example"), "w") as f:
            f.write(ENV_EXAMPLE)
        shutil.copy(UPDATE_SCRIPT, os.path.join(self.repo, "update.sh")) \
            if os.path.exists(UPDATE_SCRIPT) else None
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("v1\n")
        with open(os.path.join(self.repo, "docker-compose.yml"), "w") as f:
            f.write(compose_yaml())
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", "init")
        git(self.repo, "remote", "add", "origin", self.origin)
        git(self.repo, "push", "origin", "develop")
        git(self.repo, "branch", "main")
        git(self.repo, "push", "origin", "main")
        git(self.repo, "branch", "--set-upstream-to=origin/develop", "develop")
        # .env は git 管理外(利用者の手元にしか無い)
        with open(self.env_path, "w") as f:
            f.write(ENV_FILE)
        run(["git", "clone", self.origin, self.other], self.tmp)

    def _write(self, path, content):
        with open(path, "w") as f:
            f.write(content)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)

    def push_upstream(self, branch="develop", text="v2\n"):
        git(self.other, "checkout", branch)
        with open(os.path.join(self.other, "a.txt"), "w") as f:
            f.write(text)
        git(self.other, "commit", "-am", "upstream")
        git(self.other, "push", "origin", branch)

    def push_files(self, files):
        """upstream(develop)に、パス→内容のファイル群を1コミットで積む。"""
        git(self.other, "checkout", "develop")
        git(self.other, "pull", "-q", "--rebase", "origin", "develop")
        for rel, text in files.items():
            path = os.path.join(self.other, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(text)
        git(self.other, "add", "-A")
        git(self.other, "commit", "-m", "upstream files")
        git(self.other, "push", "origin", "develop")

    def up_lines(self):
        return [re.sub(r" \[DOCKER_CONFIG=.*$", "", l)
                for l in self.logged().splitlines() if l.startswith("docker compose up")]

    def rebuild_targets_line(self, r):
        m = re.search(r"再ビルド対象[^\n]*", r.stdout)
        self.assertIsNotNone(m, r.stdout)
        return m.group(0)

    def update(self, *args, extra_env=None):
        env = dict(os.environ)
        env.update(PATH=self.bin + ":" + os.environ["PATH"], FAKE_LOG=self.log,
                   HOME=self.home)
        env.pop("DOCKER_CONFIG", None)
        env.update(extra_env or {})
        return subprocess.run(["bash", os.path.join(self.repo, "update.sh"), *args],
                              cwd=self.repo, env=env, text=True, capture_output=True)

    def logged(self):
        with open(self.log) as f:
            return f.read()

    def a_txt(self):
        with open(os.path.join(self.repo, "a.txt")) as f:
            return f.read()

    # ---- 引数 ----
    def test_help_exits_zero(self):
        r = self.update("--help")
        self.assertEqual(r.returncode, 0)
        self.assertIn("--main", r.stdout)
        self.assertIn("--branch", r.stdout)
        self.assertEqual(self.logged(), "")

    def test_unknown_argument_fails(self):
        r = self.update("--bogus")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.logged(), "")

    def test_branch_without_value_fails(self):
        r = self.update("--branch")
        self.assertNotEqual(r.returncode, 0)

    # ---- 更新元の表示 ----
    def test_default_source_is_develop_and_printed(self):
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertRegex(r.stdout, r"更新元ブランチ: develop")

    def test_main_flag_uses_main_and_prints_it(self):
        git(self.repo, "checkout", "main")
        self.push_upstream("main", "m2\n")
        r = self.update("--main")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertRegex(r.stdout, r"更新元ブランチ: main")
        self.assertEqual(self.a_txt(), "m2\n")

    def test_branch_flag_uses_named_branch(self):
        git(self.repo, "checkout", "-b", "feat/x")
        git(self.repo, "push", "origin", "feat/x")
        r = self.update("--branch", "feat/x")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertRegex(r.stdout, r"更新元ブランチ: feat/x")

    def test_source_printed_even_when_aborting(self):
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("dirty\n")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertRegex(r.stdout, r"更新元ブランチ: develop")

    # ---- 安全確認 ----
    def test_dirty_tree_aborts_without_changes(self):
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("dirty\n")
        self.push_upstream()
        before = git(self.repo, "status").stdout
        head_before = git(self.repo, "rev-parse", "HEAD").stdout
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(git(self.repo, "status").stdout, before)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD").stdout, head_before)
        self.assertEqual(self.a_txt(), "dirty\n")
        self.assertEqual(self.logged(), "")
        self.assertEqual(git(self.repo, "stash", "list").stdout, "")

    def test_wrong_branch_aborts(self):
        git(self.repo, "checkout", "main")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("main", r.stderr)
        self.assertEqual(self.logged(), "")

    def test_staged_change_aborts(self):
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("staged\n")
        git(self.repo, "add", "a.txt")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.logged(), "")

    # ---- 取り込みと反映 ----
    def test_success_pulls_rebuilds_and_waits(self):
        self.push_upstream()
        self.push_files({"services/media/src/A.java": "x\n"})
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.a_txt(), "v2\n")
        log = self.logged()
        self.assertIn("docker compose up -d --build", log)
        self.assertIn("wait --all", log)
        self.assertLess(log.index("docker compose up"), log.index("wait --all"))

    def test_idempotent(self):
        self.push_upstream()
        self.assertEqual(self.update().returncode, 0)
        self.assertEqual(self.update().returncode, 0)
        self.assertEqual(self.a_txt(), "v2\n")

    def test_non_fast_forward_fails_before_docker(self):
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("local\n")
        git(self.repo, "commit", "-am", "local commit")
        self.push_upstream()
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("docker", self.logged())

    def test_fetch_failure_fails(self):
        git(self.repo, "remote", "set-url", "origin", os.path.join(self.tmp, "nope.git"))
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("docker", self.logged())

    def test_docker_failure_propagates_and_skips_wait(self):
        r = self.update(extra_env={"FAKE_DOCKER_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("wait", self.logged())

    def test_wait_timeout_exits_nonzero(self):
        r = self.update(extra_env={"FAKE_WAIT_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("web", r.stderr)

    # ---- 更新前バックアップ(#1252) ----
    def backup_files(self):
        d = os.path.join(self.repo, "backups")
        return os.listdir(d) if os.path.isdir(d) else []

    def test_backup_created_under_backups_and_path_printed(self):
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        files = self.backup_files()
        self.assertEqual(len(files), 1)
        self.assertIn(os.path.join("backups", files[0]), r.stdout)

    def test_backup_runs_before_pull_and_rebuild(self):
        self.push_upstream()
        self.update()
        log = self.logged()
        self.assertIn("backup ", log)
        self.assertLess(log.index("backup "), log.index("docker compose up"))

    def test_backup_failure_aborts_before_pull(self):
        self.push_upstream()
        r = self.update(extra_env={"FAKE_BACKUP_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.a_txt(), "v1\n")
        self.assertNotIn("docker", self.logged())
        self.assertIn("--skip-backup", r.stderr)

    def test_skip_backup_flag_skips_backup_and_updates(self):
        self.push_upstream()
        r = self.update("--skip-backup", extra_env={"FAKE_BACKUP_FAIL": "1"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("backup ", self.logged())
        self.assertEqual(self.backup_files(), [])
        self.assertEqual(self.a_txt(), "v2\n")

    def test_help_mentions_skip_backup(self):
        self.assertIn("--skip-backup", self.update("--help").stdout)

    def test_output_states_generated_images_not_backed_up(self):
        r = self.update()
        self.assertIn("生成画像ファイルはバックアップ対象外", r.stdout)

    def test_unhealthy_guides_restore_with_service_and_backup_path(self):
        r = self.update(extra_env={"FAKE_WAIT_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("web", r.stderr)
        self.assertIn("scripts/db-restore.sh", r.stderr)
        self.assertIn(self.backup_files()[0], r.stderr)

    def test_unhealthy_with_skip_backup_has_no_restore_path(self):
        r = self.update("--skip-backup", extra_env={"FAKE_WAIT_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("db-restore.sh", r.stderr)

    def test_docker_build_failure_also_guides_restore(self):
        r = self.update(extra_env={"FAKE_DOCKER_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("scripts/db-restore.sh", r.stderr)

    # ---- 差分ベースの選択的再ビルド(#1253) ----
    def test_single_service_change_rebuilds_only_that_service(self):
        self.push_files({"services/media/src/A.java": "x\n"})
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.up_lines(), ["docker compose up -d --build media"])
        line = self.rebuild_targets_line(r)
        self.assertIn("media", line)
        self.assertNotIn("gateway", line)

    def test_web_and_wordpress_contexts_are_matched(self):
        self.push_files({"apps/web/src/p.ts": "x\n", "infra/wordpress/Dockerfile": "x\n"})
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.up_lines(), ["docker compose up -d --build web wordpress"])

    def test_two_java_services_changed(self):
        self.push_files({"services/ai/a": "x\n", "services/gateway/b": "x\n"})
        self.update()
        self.assertEqual(self.up_lines(), ["docker compose up -d --build gateway ai"])

    def test_rebuild_all_flag_rebuilds_all_twelve(self):
        r = self.update("--rebuild-all")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.up_lines(), ["docker compose up -d --build"])
        line = self.rebuild_targets_line(r)
        for n in ALL_BUILD_SERVICES:
            self.assertIn(n, line)

    def test_compose_file_change_rebuilds_all(self):
        self.push_files({"docker-compose.yml": compose_yaml() + "# changed\n"})
        r = self.update()
        self.assertEqual(self.up_lines(), ["docker compose up -d --build"])
        line = self.rebuild_targets_line(r)
        for n in ALL_BUILD_SERVICES:
            self.assertIn(n, line)

    def test_shared_build_inputs_rebuild_all(self):
        for rel in ("build.gradle", "settings.gradle", "packages/x/Y.java",
                    "gradle/wrapper/w.properties", "config/c.xml",
                    "services/media/build.gradle"):
            with self.subTest(rel=rel):
                self.setUp()
                self.push_files({rel: "x\n"})
                self.update()
                self.assertEqual(self.up_lines(), ["docker compose up -d --build"])

    def test_no_diff_reports_zero_targets_and_does_not_rebuild(self):
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("0件", self.rebuild_targets_line(r))
        self.assertNotIn("--build", self.logged())
        self.assertIn("wait --all", self.logged())

    def test_unrelated_change_reports_zero_targets(self):
        self.push_files({"docs/x.md": "x\n", "README.md": "x\n"})
        r = self.update()
        self.assertIn("0件", self.rebuild_targets_line(r))
        self.assertNotIn("--build", self.logged())

    def test_java_service_only_its_own_directory_triggers_it(self):
        self.push_files({"services/media/src/A.java": "x\n"})
        self.update()
        self.assertEqual(self.up_lines(), ["docker compose up -d --build media"])
        self.assertNotIn("gateway", self.up_lines()[0])

    def test_missing_compose_file_falls_back_to_rebuild_all(self):
        git(self.repo, "rm", "-q", "docker-compose.yml")
        git(self.repo, "commit", "-m", "rm compose")
        git(self.repo, "push", "origin", "develop")
        self.push_files({"services/media/a": "x\n"})
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.up_lines(), ["docker compose up -d --build"])

    def test_help_mentions_rebuild_all(self):
        self.assertIn("--rebuild-all", self.update("--help").stdout)

    # ---- データを消さない ----
    def test_never_destroys_volumes(self):
        self.update()
        log = self.logged()
        self.assertNotRegex(log, r"\bdown\b")
        self.assertNotRegex(log, r"(^|\s)-v(\s|$)")
        self.assertNotIn("volume", log)
        with open(UPDATE_SCRIPT) as f:
            src = f.read()
        self.assertNotRegex(src, r"compose down|--volumes|volume rm|prune")

    # ---- DOCKER_CONFIG(#1084) ----
    def test_docker_config_used_when_present(self):
        os.makedirs(os.path.join(self.home, ".config", "docker-cli"))
        self.update()
        self.assertIn("DOCKER_CONFIG=" + os.path.join(self.home, ".config", "docker-cli"),
                      self.logged())

    def test_docker_config_untouched_when_absent(self):
        self.update()
        self.assertIn("DOCKER_CONFIG=]", self.logged())

    def test_docker_config_respects_existing(self):
        os.makedirs(os.path.join(self.home, ".config", "docker-cli"))
        self.update(extra_env={"DOCKER_CONFIG": "/custom"})
        self.assertIn("DOCKER_CONFIG=/custom]", self.logged())

    # ---- .env 追随チェック(#1254) ----
    def env_text(self):
        with open(self.env_path) as f:
            return f.read()

    def push_env_example(self, extra):
        self.push_files({".env.example": ENV_EXAMPLE + extra})

    def test_missing_keys_listed_and_exit_nonzero_without_startup(self):
        self.push_env_example("NEW_FLAG=1\nNEW_OTHER=x\n")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        out = r.stdout + r.stderr
        self.assertIn("NEW_FLAG", out)
        self.assertIn("NEW_OTHER", out)
        log = self.logged()
        self.assertNotIn("docker compose up", log)
        self.assertNotIn("wait --all", log)

    def test_missing_keys_never_rewrite_existing_env(self):
        self.push_env_example("NEW_FLAG=1\nNEW_SECRET=changeme_x\n")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.env_text(), ENV_FILE)

    def test_missing_secret_not_appended_without_flag(self):
        self.push_env_example("NEW_SECRET=changeme_x\n")
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("NEW_SECRET", self.env_text())
        self.assertIn("--fill-secrets", r.stdout + r.stderr)

    def test_nothing_missing_adds_no_output(self):
        self.push_upstream()
        r = self.update("--skip-backup")
        self.assertEqual(r.returncode, 0, r.stderr)
        for marker in ("✓", "✗", "△", "check-env", ".env", "--fill-secrets"):
            self.assertNotIn(marker, r.stdout, marker)
        for marker in ("✗", "不足", "check-env"):
            self.assertNotIn(marker, r.stderr, marker)
        self.assertEqual(self.env_text(), ENV_FILE)

    def test_fill_secrets_appends_only_generatable_secrets(self):
        self.push_env_example(
            "NEW_SECRET=changeme_x\nAPP_ENCRYPTION_KEY=%s\n" % APP_KEY_PLACEHOLDER)
        r = self.update("--fill-secrets")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        env = self.env_text()
        self.assertTrue(env.startswith(ENV_FILE), "既存の行が書き換わった")
        added = dict(l.split("=", 1) for l in env[len(ENV_FILE):].splitlines())
        self.assertEqual(sorted(added), ["APP_ENCRYPTION_KEY", "NEW_SECRET"])
        self.assertNotIn("changeme_", added["NEW_SECRET"])
        self.assertNotEqual(added["NEW_SECRET"], "")
        self.assertNotEqual(added["APP_ENCRYPTION_KEY"], APP_KEY_PLACEHOLDER)
        self.assertIn("docker compose up", self.logged())

    def test_fill_secrets_does_not_print_generated_values(self):
        self.push_env_example("NEW_SECRET=changeme_x\n")
        r = self.update("--fill-secrets")
        added = self.env_text()[len(ENV_FILE):].strip().split("=", 1)[1]
        self.assertIn("NEW_SECRET", r.stdout)
        self.assertNotIn(added, r.stdout + r.stderr)

    def test_fill_secrets_does_not_append_external_values(self):
        self.push_env_example("NEW_SECRET=changeme_x\nEXT_API_KEY=\nNEW_PLAIN=default\n")
        r = self.update("--fill-secrets")
        self.assertNotEqual(r.returncode, 0)
        env = self.env_text()
        self.assertIn("NEW_SECRET=", env)
        self.assertNotIn("EXT_API_KEY", env)
        self.assertNotIn("NEW_PLAIN", env)
        out = r.stdout + r.stderr
        self.assertIn("EXT_API_KEY", out)
        self.assertNotIn("docker compose up", self.logged())

    def test_fill_secrets_handles_env_without_trailing_newline(self):
        with open(self.env_path, "w") as f:
            f.write(ENV_FILE.rstrip("\n"))
        self.push_env_example("NEW_SECRET=changeme_x\n")
        r = self.update("--fill-secrets")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertTrue(self.env_text().startswith(ENV_FILE))

    def test_fill_secrets_with_nothing_missing_changes_nothing(self):
        r = self.update("--fill-secrets")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.env_text(), ENV_FILE)

    def test_missing_env_file_fails_without_startup(self):
        os.remove(self.env_path)
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("docker compose up", self.logged())

    def test_help_mentions_fill_secrets(self):
        r = self.update("--help")
        self.assertIn("--fill-secrets", r.stdout)

    # ---- 自動生成対象の分類は setup.sh と共有する(#960 / #1254) ----
    def _lib(self, snippet):
        lib = os.path.join(REPO_ROOT, "scripts", "lib", "env-secrets.sh")
        return subprocess.run(["bash", "-c", 'source "%s"; %s' % (lib, snippet)],
                              text=True, capture_output=True)

    def test_lib_classifies_example_values(self):
        cases = [
            ("NEW_SECRET", "changeme_x", 0),
            ("API_KEY", "", 1),
            ("PLAIN", "default", 1),
            ("APP_ENCRYPTION_KEY", APP_KEY_PLACEHOLDER, 0),
            ("APP_ENCRYPTION_KEY", "other", 1),
            ("KEYCLOAK_WEB_CLIENT_SECRET", "dev-only-web-change-me", 0),
            ("KEYCLOAK_SERVICES_CLIENT_SECRET", "dev-only-s-change-me", 0),
            ("OTHER_SECRET", "dev-only-x-change-me", 1),
        ]
        for key, value, want in cases:
            r = self._lib('is_auto_generatable_secret "%s" "%s"' % (key, value))
            self.assertEqual(r.returncode, want, (key, value))

    def test_lib_generates_distinct_nonempty_values(self):
        r = self._lib('generate_value_for_key NEXTAUTH_SECRET; generate_value_for_key NEXTAUTH_SECRET')
        a, b = r.stdout.split()
        self.assertNotEqual(a, b)
        self.assertEqual(len(a), 64)

    def test_generation_logic_defined_only_in_shared_lib(self):
        for rel in ("setup.sh", "update.sh"):
            with open(os.path.join(REPO_ROOT, rel)) as f:
                text = f.read()
            self.assertNotIn("generate_value_for_key()", text, rel)
            self.assertIn("scripts/lib/env-secrets.sh", text, rel)

    # ---- ドキュメント ----
    def _doc(self, rel):
        with open(os.path.join(REPO_ROOT, rel)) as f:
            return f.read()

    def _update_section(self, rel):
        text = self._doc(rel)
        m = re.search(r"^#+ [^\n]*アップデート[^\n]*\n(.*?)(?=^#{1,2} |\Z)", text, re.S | re.M)
        self.assertIsNotNone(m, rel + " に「アップデート」節が無い")
        return m.group(1)

    def test_docs_describe_update_procedure(self):
        for rel in ("README.md", "docs/setup.md"):
            sec = self._update_section(rel)
            self.assertIn("./update.sh", sec, rel)
            self.assertIn("--main", sec, rel)
            self.assertIn("develop", sec, rel)

    def test_docs_describe_fill_secrets(self):
        sec = self._update_section("docs/setup.md")
        self.assertIn("--fill-secrets", sec)
        self.assertIn("check-env.sh", sec)


if __name__ == "__main__":
    unittest.main()
