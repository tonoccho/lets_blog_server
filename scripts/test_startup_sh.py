#!/usr/bin/env python3
"""`startup.sh`(リポジトリ直下)の検証(#961)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_startup_sh.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** の例外(Web UI から到達できない基準は
スクリプトレベルのテストで表現する)に従う。`startup.sh` は docker compose を叩く運用
シェルスクリプトで、Web UI からは到達できない(利用者がホストの端末で実行する)。
`setup.sh`(`test_setup_sh.py`)・`update.sh`(`test_update_sh.py`)と同じ扱い。

## どう検証するか

一時ディレクトリに最小のリポジトリ(`startup.sh` / 実物の `scripts/lib/compose-project.sh` /
偽の `scripts/wait-for-stack-healthy.sh` / 空の `docker-compose.yml`)を作り、`docker` を
PATH 上の偽物に差し替えて呼び出しを FAKE_LOG に記録する。

## 対象外(ユニットテストでは到達できない受入基準)

「`docker compose stop` 後に `./startup.sh` が終了コード0で終わり、直後の
`wait-for-stack-healthy.sh --all` も通る」は本物の稼働スタックが必要で、QA で確認する。
ここでは startup.sh が `wait-for-stack-healthy.sh --all` を呼び、その終了コードを伝播
することだけを検証する。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
STARTUP_SCRIPT = os.path.join(REPO_ROOT, "startup.sh")
COMPOSE_LIB = os.path.join(REPO_ROOT, "scripts", "lib", "compose-project.sh")

FAKE_DOCKER = """#!/bin/bash
echo "docker $* [DOCKER_CONFIG=${DOCKER_CONFIG:-}]" >> "$FAKE_LOG"
case "$*" in
  *"config --images"*) printf 'lets/web:latest\\nlets/content:latest\\n'; exit 0 ;;
  "image inspect"*) [ -n "${FAKE_IMAGES_MISSING:-}" ] && exit 1; exit 0 ;;
  *" build"*) [ -n "${FAKE_BUILD_FAIL:-}" ] && exit 1; exit 0 ;;
  *" up "*) [ -n "${FAKE_UP_FAIL:-}" ] && exit 1; exit 0 ;;
esac
exit 0
"""

FAKE_WAIT = """#!/bin/bash
echo "wait $*" >> "$FAKE_LOG"
if [ -n "${FAKE_WAIT_FAIL:-}" ]; then
  echo "  - web" >&2
  echo "  - content" >&2
  exit 1
fi
exit 0
"""


class StartupShTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = os.path.join(self.tmp, "repo")
        self.bin = os.path.join(self.tmp, "bin")
        self.home = os.path.join(self.tmp, "home")
        for d in (self.bin, self.home, os.path.join(self.repo, "scripts", "lib")):
            os.makedirs(d)
        self.log = os.path.join(self.tmp, "fake.log")
        open(self.log, "w").close()
        self._write(os.path.join(self.bin, "docker"), FAKE_DOCKER)
        self._write(os.path.join(self.repo, "scripts", "wait-for-stack-healthy.sh"), FAKE_WAIT)
        shutil.copy(COMPOSE_LIB, os.path.join(self.repo, "scripts", "lib", "compose-project.sh"))
        open(os.path.join(self.repo, "docker-compose.yml"), "w").close()
        if os.path.exists(STARTUP_SCRIPT):
            shutil.copy(STARTUP_SCRIPT, os.path.join(self.repo, "startup.sh"))

    def _write(self, path, content):
        with open(path, "w") as f:
            f.write(content)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)

    def _env(self, extra_env=None):
        env = dict(os.environ)
        env.update(PATH=self.bin + ":" + os.environ["PATH"], FAKE_LOG=self.log,
                   HOME=self.home)
        env.pop("DOCKER_CONFIG", None)
        env.pop("COMPOSE_PROJECT_NAME", None)
        env.update(extra_env or {})
        return env

    def startup(self, *args, extra_env=None):
        return subprocess.run(["bash", os.path.join(self.repo, "startup.sh"), *args],
                              cwd=self.repo, env=self._env(extra_env), text=True,
                              capture_output=True)

    def logged(self):
        with open(self.log) as f:
            return f.read()

    def lines(self, prefix):
        return [l for l in self.logged().splitlines() if l.startswith(prefix)]

    # ---- 引数 ----
    def test_help_exits_zero_without_side_effects(self):
        r = self.startup("--help")
        self.assertEqual(r.returncode, 0)
        self.assertIn("--build", r.stdout)
        self.assertEqual(self.logged(), "")

    def test_unknown_argument_fails(self):
        r = self.startup("--bogus")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.logged(), "")

    # ---- 起動とヘルス待ち ----
    def test_success_runs_up_then_waits_all_and_prints_guidance(self):
        r = self.startup()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.logged()
        self.assertIn("up -d", log)
        self.assertIn("wait --all", log)
        self.assertLess(log.index("up -d"), log.index("wait --all"))
        self.assertIn("https://localhost", r.stdout)
        self.assertIn("/setup", r.stdout)

    def test_uses_base_compose_file_and_resolved_project_only(self):
        self.startup()
        ups = self.lines("docker compose")
        self.assertTrue(ups)
        for l in ups:
            self.assertIn("-f " + os.path.join(self.repo, "docker-compose.yml"), l)
            self.assertIn("-p repo", l)
            self.assertNotIn("e2e-stubs", l)
            self.assertNotIn("shared-host", l)

    def test_compose_project_name_env_is_respected(self):
        self.startup(extra_env={"COMPOSE_PROJECT_NAME": "customproj"})
        for l in self.lines("docker compose"):
            self.assertIn("-p customproj", l)

    def test_stage_headings_for_build_start_and_health_wait(self):
        r = self.startup()
        out = r.stdout
        for heading in ("ビルド", "起動", "ヘルス待ち"):
            self.assertIn("==> [", out)
            self.assertIn(heading, out)
        self.assertLess(out.index("ビルド"), out.index("起動"))
        self.assertLess(out.index("起動"), out.index("ヘルス待ち"))

    # ---- ビルド ----
    def test_build_flag_builds_explicitly(self):
        r = self.startup("--build")
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.logged()
        self.assertIn(" build", log)
        self.assertLess(log.index(" build"), log.index("up -d"))

    def test_no_build_when_images_exist(self):
        self.startup()
        self.assertEqual(self.lines("docker compose -p repo -f " + os.path.join(
            self.repo, "docker-compose.yml") + " build"), [])

    def test_builds_when_images_missing(self):
        r = self.startup(extra_env={"FAKE_IMAGES_MISSING": "1"})
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.logged()
        self.assertIn(" build", log)
        self.assertLess(log.index(" build"), log.index("up -d"))

    def test_build_failure_stops_before_up(self):
        r = self.startup("--build", extra_env={"FAKE_BUILD_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("up -d", self.logged())
        self.assertNotIn("wait", self.logged())

    # ---- 失敗 ----
    def test_up_failure_exits_nonzero_without_waiting(self):
        r = self.startup(extra_env={"FAKE_UP_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("wait --all", self.logged())
        self.assertNotIn("https://localhost", r.stdout)

    def test_unhealthy_services_are_named_and_exit_nonzero(self):
        r = self.startup(extra_env={"FAKE_WAIT_FAIL": "1"})
        self.assertNotEqual(r.returncode, 0)
        combined = r.stdout + r.stderr
        self.assertIn("web", combined)
        self.assertIn("content", combined)
        self.assertNotIn("https://localhost にアクセス", r.stdout)

    # ---- 冪等性・再利用 ----
    def test_rerun_is_idempotent(self):
        self.assertEqual(self.startup().returncode, 0)
        self.assertEqual(self.startup().returncode, 0)

    def test_no_destructive_docker_commands(self):
        self.startup("--build")
        log = self.logged()
        for bad in (" down", " rm ", "volume rm", " -v", " stop", " kill"):
            self.assertNotIn(bad, log)

    def test_does_not_reimplement_health_polling(self):
        with open(STARTUP_SCRIPT) as f:
            text = f.read()
        self.assertIn("wait-for-stack-healthy.sh", text)
        self.assertNotIn(" ps ", text)
        self.assertNotIn("sleep ", text)

    def test_docker_config_dir_used_when_present(self):
        os.makedirs(os.path.join(self.home, ".config", "docker-cli"))
        self.startup()
        self.assertIn("DOCKER_CONFIG=" + os.path.join(self.home, ".config", "docker-cli"),
                      self.logged())

    def test_user_docker_config_respected(self):
        os.makedirs(os.path.join(self.home, ".config", "docker-cli"))
        self.startup(extra_env={"DOCKER_CONFIG": "/custom"})
        self.assertIn("DOCKER_CONFIG=/custom", self.logged())

    def test_source_does_not_run_main(self):
        r = subprocess.run(
            ["bash", "-c", 'source "$1"; type main >/dev/null && echo sourced', "_",
             STARTUP_SCRIPT],
            env=self._env(), text=True, capture_output=True)
        self.assertIn("sourced", r.stdout)
        self.assertEqual(self.logged(), "")


if __name__ == "__main__":
    unittest.main()
