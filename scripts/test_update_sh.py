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
        shutil.copy(UPDATE_SCRIPT, os.path.join(self.repo, "update.sh")) \
            if os.path.exists(UPDATE_SCRIPT) else None
        with open(os.path.join(self.repo, "a.txt"), "w") as f:
            f.write("v1\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", "init")
        git(self.repo, "remote", "add", "origin", self.origin)
        git(self.repo, "push", "origin", "develop")
        git(self.repo, "branch", "main")
        git(self.repo, "push", "origin", "main")
        git(self.repo, "branch", "--set-upstream-to=origin/develop", "develop")
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


if __name__ == "__main__":
    unittest.main()
