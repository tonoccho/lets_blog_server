#!/usr/bin/env python3
"""git worktree から実行しても共有スタックの compose プロジェクト・ネットワークを解決できること(#1201)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_compose_project_resolution.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** の文書化された例外
(`scripts/test_shared_host_proxy.py` と同じ理由)。対象は開発者向けシェルスクリプトの
プロジェクト名解決であり、製品の画面には現れない。受け入れテストを走らせる手段そのもの
(healthy 待ち)が対象でもある。

## 検査の仕方

スクリプトは自身の位置から REPO_ROOT を求めるため、一時ディレクトリに git リポジトリ
(ディレクトリ名 `lets_blog_server`)を作ってスクリプトをコピーし、そこと、そこから作った
リンク worktree、git 管理外のコピーのそれぞれから実行する。docker は PATH 上のスタブで、
`-p lets_blog_server` のときだけ全サービス healthy を返す。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

WAIT_SCRIPT = "scripts/wait-for-stack-healthy.sh"
SETUP_SCRIPT = "scripts/setup-shared-host-proxy.sh"
LIB = "scripts/lib/compose-project.sh"
MAIN_NAME = "lets_blog_server"

SERVICES = (
    "reverse-proxy web gateway keycloak keycloak-postgres mysql rabbitmq "
    "identity media ai content analytics project publishing platform log-writer"
).split()

FAKE_DOCKER = """#!/bin/bash
echo "$@" >> "$FAKE_DOCKER_LOG"
case "$*" in
  *"network inspect"*)
    case "$*" in
      *"lets_blog_server_lbs-net"*) echo "infra-proxy" ;;
      *) echo "" ;;
    esac
    ;;
  *" ps "*)
    case "$*" in
      *"-p lets_blog_server "*)
        for s in %s; do
          echo "{\\"Service\\":\\"$s\\",\\"State\\":\\"running\\",\\"Health\\":\\"healthy\\",\\"ExitCode\\":0}"
        done
        ;;
    esac
    ;;
esac
exit 0
""" % " ".join(SERVICES)


def git(cwd, *args):
    subprocess.run(
        ["git", "-c", "user.email=t@example.com", "-c", "user.name=t"] + list(args),
        cwd=cwd, check=True, capture_output=True, text=True,
    )


def copy_scripts(dst_root):
    os.makedirs(os.path.join(dst_root, "scripts"), exist_ok=True)
    for rel in (WAIT_SCRIPT, SETUP_SCRIPT):
        shutil.copy(os.path.join(REPO_ROOT, rel), os.path.join(dst_root, rel))
    # setup-shared-host-proxy.sh の事前条件(配置元ファイルの存在)を満たすためのダミー。
    for rel in (
        "infra/shared-host/20-localhost.conf",
        "infra/shared-host/10-server.tonoccho.local.conf",
        "certs/localhost.crt",
        "certs/localhost.key",
    ):
        path = os.path.join(dst_root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write("dummy\n")
    lib_dir = os.path.join(REPO_ROOT, "scripts", "lib")
    if os.path.isdir(lib_dir):
        shutil.copytree(lib_dir, os.path.join(dst_root, "scripts", "lib"))


class ComposeProjectResolutionBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.main = os.path.join(self.tmp, MAIN_NAME)
        os.makedirs(self.main)
        copy_scripts(self.main)
        git(self.main, "init", "-q")
        git(self.main, "add", "-A")
        git(self.main, "commit", "-q", "-m", "init")
        self.worktree = os.path.join(self.tmp, "agent-abc123")
        git(self.main, "worktree", "add", "-q", self.worktree, "-b", "wt")
        self.plain = os.path.join(self.tmp, "plain-copy")
        os.makedirs(self.plain)
        copy_scripts(self.plain)

        self.bindir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bindir)
        self.docker = os.path.join(self.bindir, "docker")
        with open(self.docker, "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(self.docker, 0o755)
        self.log = os.path.join(self.tmp, "docker.log")
        for sub in ("conf.d", "certs"):
            os.makedirs(os.path.join(self.tmp, "infra", "proxy", sub))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def env(self, **overrides):
        env = {k: v for k, v in os.environ.items() if k not in ("COMPOSE_PROJECT_NAME", "LBS_NETWORK")}
        env.update(
            {
                "PATH": self.bindir + os.pathsep + env["PATH"],
                "FAKE_DOCKER_LOG": self.log,
                "E2E_SKIP_HTTP_CHECK": "1",
                "GITLAB_HEALTH_URL": "",
                "LBS_BASE_URL": "",
                "INFRA_DIR": os.path.join(self.tmp, "infra"),
                "DOCKER_BIN": self.docker,
            }
        )
        env.update(overrides)
        return env

    def run_wait(self, root, **env):
        return subprocess.run(
            ["bash", os.path.join(root, WAIT_SCRIPT), "--timeout", "1", "--services", "web"],
            capture_output=True, text=True, timeout=60, env=self.env(**env), cwd=root,
        )

    def run_setup_check(self, root, **env):
        return subprocess.run(
            ["bash", os.path.join(root, SETUP_SCRIPT), "--check"],
            capture_output=True, text=True, timeout=60, env=self.env(**env), cwd=root,
        )

    def docker_log(self):
        if not os.path.exists(self.log):
            return ""
        with open(self.log, encoding="utf-8") as f:
            return f.read()


class WaitScriptResolvesProject(ComposeProjectResolutionBase):
    def test_main_tree_finds_the_healthy_stack(self):
        r = self.run_wait(self.main)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_linked_worktree_finds_the_healthy_stack(self):
        r = self.run_wait(self.worktree)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("-p %s " % MAIN_NAME, self.docker_log())

    def test_explicit_compose_project_name_wins_in_a_worktree(self):
        self.run_wait(self.worktree, COMPOSE_PROJECT_NAME="custom_proj")
        self.assertIn("-p custom_proj ", self.docker_log())
        self.assertNotIn("-p %s " % MAIN_NAME, self.docker_log())

    def test_outside_git_falls_back_to_directory_name(self):
        self.run_wait(self.plain)
        self.assertIn("-p plain-copy ", self.docker_log())


class SetupScriptResolvesNetwork(ComposeProjectResolutionBase):
    def test_linked_worktree_check_resolves_the_shared_network(self):
        self.run_setup_check(self.worktree)
        self.assertIn("network inspect %s_lbs-net" % MAIN_NAME, self.docker_log())
        self.assertNotIn("agent-abc123_lbs-net", self.docker_log())

    def test_explicit_compose_project_name_wins_in_a_worktree(self):
        self.run_setup_check(self.worktree, COMPOSE_PROJECT_NAME="custom_proj")
        self.assertIn("custom_proj_lbs-net", self.docker_log())

    def test_outside_git_falls_back_to_directory_name(self):
        self.run_setup_check(self.plain)
        self.assertIn("plain-copy_lbs-net", self.docker_log())


class DerivationIsDefinedOnce(unittest.TestCase):
    def read(self, rel):
        with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
            return f.read()

    def test_library_defines_the_git_common_dir_comparison(self):
        self.assertTrue(os.path.exists(os.path.join(REPO_ROOT, LIB)), "%s が無い" % LIB)
        self.assertIn("--git-common-dir", self.read(LIB))

    def test_both_scripts_source_the_library_and_do_not_copy_the_derivation(self):
        for rel in (WAIT_SCRIPT, SETUP_SCRIPT):
            with self.subTest(script=rel):
                text = self.read(rel)
                self.assertIn("lib/compose-project.sh", text)
                self.assertNotIn("git-common-dir", text)
                self.assertNotIn('$(basename "$REPO_ROOT")', text)


if __name__ == "__main__":
    unittest.main()
