#!/usr/bin/env python3
"""reset-acceptance-env.sh が未起動(Created)のコンテナで止まらないことの検証(#1306)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

CLAUDE.md -> Test-First Implementation は、Web UI から到達できない基準を
スクリプトレベルのテストで表現することを認めている。本Issueの受入基準は、
受け入れテスト環境を初期化するスクリプト自身の終了コードと手順の到達範囲であり、
判定の時点でスタックは消去の最中にあるため Playwright からは観測できない。
test_rebuild_acceptance_env.py と同じ文書化された例外である。

## どう検証するか

`docker` と `curl` を PATH 上の偽物に差し替える。偽 docker は `lbs-comfyui` を
`docker-compose.yml` の gpu プロファイルと同じく「存在するが未起動(Created)」として振る舞う。

  - `docker inspect lbs-comfyui` は成功する(Created でも成功する本物と同じ)
  - `docker inspect -f '{{.State.Running}}' lbs-comfyui` は false
  - `docker exec lbs-comfyui ...` は失敗する("is not running")

スクリプトは REPO_ROOT を自身の位置から求めるため、一時ディレクトリに scripts/ と
ダミーの .env を置いて実行し、実スタックにも実 .env にも触れない。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT_NAME = "reset-acceptance-env.sh"

FAKE_DOCKER = r"""#!/bin/bash
echo "$*" >> "$FAKE_DOCKER_LOG"
running() {
  case "$1" in
    "$FAKE_NOT_RUNNING") echo false ;;
    *) echo true ;;
  esac
}
case "$1" in
  inspect)
    if [ "$2" = "-f" ]; then
      running "$4"
    fi
    exit 0
    ;;
  exec)
    shift
    # exec のオプション(-i / -e KEY=VAL)を読み飛ばしてコンテナ名を得る
    while [ $# -gt 0 ]; do
      case "$1" in
        -i) shift ;;
        -e) shift 2 ;;
        *) break ;;
      esac
    done
    container="$1"
    if [ "$container" = "$FAKE_NOT_RUNNING" ]; then
      echo "Error response from daemon: container x($container) is not running" >&2
      exit 1
    fi
    case "$*" in
      *"wc -l"*) echo 0 ;;
      *"kcadm.sh get users"*) echo '[]' ;;
    esac
    exit 0
    ;;
  compose)
    echo "COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-<unset>} $*" >> "$FAKE_COMPOSE_LOG"
    exit 0
    ;;
esac
exit 0
"""

FAKE_CURL = "#!/bin/bash\necho 200\n"
FAKE_WAIT = "#!/bin/bash\nexit 0\n"


def write_exec(path, body):
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


class ResetNotRunningContainerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "scripts"))
        shutil.copy(
            os.path.join(HERE, SCRIPT_NAME), os.path.join(self.repo, "scripts", SCRIPT_NAME)
        )
        os.makedirs(os.path.join(self.repo, "scripts", "lib"))
        shutil.copy(
            os.path.join(HERE, "lib", "migration-seeded-tables.sh"),
            os.path.join(self.repo, "scripts", "lib", "migration-seeded-tables.sh"),
        )
        write_exec(os.path.join(self.repo, "scripts", "wait-for-stack-healthy.sh"), FAKE_WAIT)
        with open(os.path.join(self.repo, ".env"), "w", encoding="utf-8") as f:
            f.write(
                "MYSQL_ROOT_PASSWORD=dummy\n"
                "KEYCLOAK_ADMIN_USERNAME=admin\nKEYCLOAK_ADMIN_PASSWORD=admin\n"
            )
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        write_exec(os.path.join(self.bin, "docker"), FAKE_DOCKER)
        write_exec(os.path.join(self.bin, "curl"), FAKE_CURL)
        self.log = os.path.join(self.tmp, "docker.log")
        self.compose_log = os.path.join(self.tmp, "compose.log")

    def run_script(self, not_running):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_DOCKER_LOG"] = self.log
        env["FAKE_NOT_RUNNING"] = not_running
        env["FAKE_COMPOSE_LOG"] = self.compose_log
        env.pop("COMPOSE_PROJECT_NAME", None)
        return subprocess.run(
            ["bash", os.path.join(self.repo, "scripts", SCRIPT_NAME), "--yes"],
            env=env, capture_output=True, text=True, timeout=60,
        )

    def docker_calls(self):
        with open(self.log, encoding="utf-8") as f:
            return f.read().splitlines()

    def test_created_comfyui_does_not_stop_the_reset(self):
        r = self.run_script("lbs-comfyui")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("6/6 RabbitMQ", r.stdout)
        self.assertIn("setup-status → 200", r.stdout)
        self.assertIn("lbs-comfyui は未起動のためスキップ", r.stdout)
        self.assertFalse(
            any(c.startswith("exec lbs-comfyui") for c in self.docker_calls()),
            "未起動のコンテナへ docker exec してはならない",
        )

    def test_created_media_is_also_skipped(self):
        r = self.run_script("lbs-media")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("6/6 RabbitMQ", r.stdout)
        self.assertIn("lbs-media は未起動のためスキップ", r.stdout)

    def test_running_containers_are_still_cleaned(self):
        r = self.run_script("none")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        calls = self.docker_calls()
        self.assertTrue(any(c.startswith("exec lbs-media") for c in calls))
        self.assertTrue(any(c.startswith("exec lbs-comfyui") for c in calls))
        self.assertNotIn("未起動のためスキップ", r.stdout)

    def test_compose_restart_pins_the_shared_stack_project_name(self):
        # worktree(ディレクトリ名が lets_blog_server でない)から実行しても、
        # compose が別プロジェクトを選ばず共有スタックを再起動する(#1635)。
        r = self.run_script("none")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.compose_log, encoding="utf-8") as f:
            lines = f.read().splitlines()
        restarts = [l for l in lines if " restart " in l]
        self.assertTrue(restarts, "docker compose restart が呼ばれていない")
        for l in restarts:
            self.assertTrue(
                l.startswith("COMPOSE_PROJECT_NAME=lets_blog_server "),
                "プロジェクト名が固定されていない: " + l,
            )


if __name__ == "__main__":
    unittest.main()
