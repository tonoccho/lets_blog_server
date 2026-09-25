#!/usr/bin/env python3
"""`setup.sh`(リポジトリ直下)の検証(#960)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルのテストで
表現する」ことを明示的な例外として認めている。

`setup.sh` は「クローン直後の、まだ何も入っていないUbuntu/Debian機」を対象にした
初回プロビジョニングスクリプトであり、sudo で apt パッケージを導入し Docker を起動する。
Playwright を動かす AT ハーネス自体が Docker で構築された`https://localhost`前提のため、
`setup.sh` を素の機械に対して literal に実行する経路が無い(検証対象が検証の前提を壊す)。
`scripts/test_rebuild_acceptance_env.py`(#965)・`scripts/test_shared_host_proxy.py`(#1038)と
同じ、文書化された例外としてここで表現する。

## どう検証するか

本物の apt / docker / sudo を叩くわけにはいかない。`scripts/test_rebuild_acceptance_env.py`
と同じ手法で、`apt-get` / `dpkg` / `docker` / `sudo` / `id` / `nvidia-smi` / `curl` / `gpg` /
`tee` / `nvidia-ctk` / `systemctl` / `usermod` を PATH 上の偽物に差し替える。呼び出しは
`FAKE_LOG` に記録し、実ファイルシステム(/etc 配下等)へは書き込まない。

`setup.sh` 自身は `git clone` 直後のリポジトリ直下に置かれる前提なので、各テストは
一時ディレクトリに最小限のファイル一式(`.env.example` / `docker-compose.yml` /
`scripts/check-env.sh` / `scripts/generate-certs.sh` / `scripts/wait-for-stack-healthy.sh` /
`setup.sh`)を実リポジトリからコピーし、そこで `git init` して動かす。実リポジトリの
`.env` やカレントブランチには一切触れない。

`setup.sh` は末尾で `[[ "${BASH_SOURCE[0]}" == "${0}" ]]` を見て、直接実行された時だけ
`main` を呼ぶ(`source` すれば関数だけを読み込める)。個々の手順(ブランチ確認・OS判定・
`.env` 生成・NVIDIA導入要否)は `source` して関数単体を叩き、
全体の流れ(ブランチ中断・OS非対応・成功パス)だけをプロセスとして実行する end-to-end
テストで確認する。

## 対象外にしたもの

- Docker Engine 自体の導入(`curl -fsSL https://get.docker.com | sh`)・
  NVIDIA Container Toolkit の apt リポジトリ登録から実際の `apt-get install` までを
  通しで検証すること。README に既にある公式コマンドをそのまま転記したものであり、
  ロジックとして分岐するのは「導入済みか」の判定だけなので、その判定(`command -v` /
  `dpkg -s`)がスキップ/実行を正しく分けることをテストし、コマンド列自体の正しさは
  README の記述との一致(文字列としての転記)を見るにとどめる。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SETUP_SCRIPT = os.path.join(REPO_ROOT, "setup.sh")

FAKE_BINS = {}

FAKE_BINS["sudo"] = """#!/bin/bash
echo "sudo $*" >> "$FAKE_LOG"
exec "$@"
"""

FAKE_BINS["apt-get"] = """#!/bin/bash
echo "apt-get $*" >> "$FAKE_LOG"
exit 0
"""

FAKE_BINS["dpkg"] = """#!/bin/bash
if [ "$1" = "-s" ]; then
  pkg="$2"
  for p in $FAKE_DPKG_INSTALLED; do
    if [ "$p" = "$pkg" ]; then
      exit 0
    fi
  done
  exit 1
fi
exit 0
"""

FAKE_BINS["nvidia-ctk"] = """#!/bin/bash
echo "nvidia-ctk $*" >> "$FAKE_LOG"
exit 0
"""

FAKE_BINS["systemctl"] = """#!/bin/bash
echo "systemctl $*" >> "$FAKE_LOG"
exit 0
"""

FAKE_BINS["gpg"] = """#!/bin/bash
echo "gpg $*" >> "$FAKE_LOG"
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
if [ -n "$out" ]; then cat > "$out" 2>/dev/null || cat >/dev/null; else cat >/dev/null; fi
exit 0
"""

FAKE_BINS["tee"] = """#!/bin/bash
echo "tee $*" >> "$FAKE_LOG"
cat >/dev/null
exit 0
"""

FAKE_BINS["curl"] = """#!/bin/bash
echo "curl $*" >> "$FAKE_LOG"
echo "-----BEGIN FAKE DATA-----"
exit 0
"""

FAKE_BINS["usermod"] = """#!/bin/bash
echo "usermod $*" >> "$FAKE_LOG"
exit 0
"""

# id: `id -nG <user>` のみ差し替える。それ以外(id -u 等)は実体へフォールバックする。
FAKE_BINS["id"] = """#!/bin/bash
if [ "$1" = "-nG" ]; then
  echo "${FAKE_ID_GROUPS:-sudo adm}"
  exit 0
fi
exec /usr/bin/id "$@"
"""

# nvidia-smi: 既定では PATH に置かない(=未導入環境)。導入済みを模す個別テストでのみ置く。
FAKE_BINS["nvidia-smi"] = """#!/bin/bash
echo "nvidia-smi $*" >> "$FAKE_LOG"
echo "Fake GPU Driver"
exit 0
"""

# docker: 実際のDaemonソケット(root:docker, 0660)と同じ境界をモデル化する。
# 呼び出し側の(擬似的な)実行時グループに docker が含まれていない限り、
# compose up/ps は permission denied で失敗する。sg 経由(IN_DOCKER_GROUP=1)か、
# 元々 FAKE_ID_GROUPS に docker を含む場合のみ通す。--version は常に許可する
# (docker CLI自体の呼び出しはソケットに触れないため)。
FAKE_BINS["docker"] = """#!/bin/bash
echo "docker $*" >> "$FAKE_LOG"
if [ "$1" = "--version" ]; then
  echo "Docker version 27.0.0, build fake"
  exit 0
fi
if [ "$1" = "compose" ]; then
  shift
  sub=""
  for a in "$@"; do
    case "$a" in
      up|ps|version) sub="$a" ;;
    esac
  done
  if [ "$sub" != "version" ]; then
    if ! echo "${FAKE_ID_GROUPS:-sudo adm}" | tr ' ' '\\n' | grep -qx docker && [ "${IN_DOCKER_GROUP:-0}" != "1" ]; then
      echo "permission denied while trying to connect to the Docker daemon socket" >&2
      exit 1
    fi
  fi
  case "$sub" in
    version) echo "Docker Compose version v2.29.0"; exit 0 ;;
    up) exit 0 ;;
    ps) echo '[{"Service":"web","State":"running","Health":"healthy"}]'; exit 0 ;;
    *) exit 0 ;;
  esac
fi
exit 0
"""

# sg <group> -c '<command>': 新しいグループが実効グループとして反映されたシェルを模す。
# usermod 直後、再ログインなしで同一実行内に反映させるための唯一の経路。
FAKE_BINS["sg"] = """#!/bin/bash
echo "sg $*" >> "$FAKE_LOG"
group="$1"
shift
if [ "$1" = "-c" ]; then
  shift
  IN_DOCKER_GROUP=1 bash -c "$1"
else
  IN_DOCKER_GROUP=1 exec "$@"
fi
"""


def _write_bin(bin_dir, name, content):
    path = os.path.join(bin_dir, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    st = os.stat(path)
    os.chmod(path, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


class SetupShTestCase(unittest.TestCase):
    """全テスト共通: 実リポジトリを汚さない一時リポジトリと、偽コマンドのPATHを用意する。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="setup-sh-test-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        self.repo = os.path.join(self.tmp, "lets_blog_server")
        os.makedirs(os.path.join(self.repo, "scripts"))
        for rel in (
            ".env.example",
            "docker-compose.yml",
            "scripts/check-env.sh",
            "scripts/generate-certs.sh",
            "scripts/wait-for-stack-healthy.sh",
        ):
            shutil.copy(os.path.join(REPO_ROOT, rel), os.path.join(self.repo, rel))
        shutil.copy(SETUP_SCRIPT, os.path.join(self.repo, "setup.sh"))
        st = os.stat(os.path.join(self.repo, "setup.sh"))
        os.chmod(os.path.join(self.repo, "setup.sh"), st.st_mode | stat.S_IEXEC)

        self.bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin_dir)
        self.fake_log = os.path.join(self.tmp, "fake.log")

        self._git("init", "-q")
        self._git("config", "user.email", "test@example.com")
        self._git("config", "user.name", "test")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "initial")
        self._git("branch", "-M", "develop")

    def _git(self, *args):
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

    def _checkout_new_branch(self, name):
        self._git("checkout", "-q", "-b", name)

    def _base_env(self, extra=None, nvidia_smi=False):
        env = dict(os.environ)
        env["PATH"] = self.bin_dir + os.pathsep + env["PATH"]
        env["FAKE_LOG"] = self.fake_log
        env["E2E_SKIP_HTTP_CHECK"] = "1"
        env["HOME"] = self.tmp
        for name, content in FAKE_BINS.items():
            if name == "nvidia-smi" and not nvidia_smi:
                continue
            _write_bin(self.bin_dir, name, content)
        if extra:
            env.update(extra)
        return env

    def _run_source(self, snippet, env=None):
        cmd = f'source "{os.path.join(self.repo, "setup.sh")}"; {snippet}'
        return subprocess.run(
            ["bash", "-c", cmd],
            cwd=self.repo,
            env=env or self._base_env(),
            capture_output=True,
            text=True,
            timeout=30,
        )

    def _run_setup(self, args=None, env=None):
        return subprocess.run(
            ["bash", os.path.join(self.repo, "setup.sh"), *(args or [])],
            cwd=self.repo,
            env=env or self._base_env(),
            capture_output=True,
            text=True,
            timeout=60,
        )

    def _fake_log_lines(self):
        if not os.path.exists(self.fake_log):
            return []
        with open(self.fake_log, encoding="utf-8") as f:
            return [l.rstrip("\n") for l in f]


class ScriptExistsAndIsExecutable(SetupShTestCase):
    def test_setup_sh_at_repo_root(self):
        self.assertTrue(os.path.isfile(SETUP_SCRIPT), "setup.sh がリポジトリ直下に無い")

    def test_setup_sh_is_executable(self):
        st = os.stat(SETUP_SCRIPT)
        self.assertTrue(st.st_mode & stat.S_IXUSR, "setup.sh に実行権限が無い")

    def test_setup_sh_is_valid_bash(self):
        r = subprocess.run(["bash", "-n", SETUP_SCRIPT], capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_does_not_reimplement_health_polling(self):
        """#960 要件6: wait-for-stack-healthy.sh を再利用し、重複実装しない。"""
        with open(SETUP_SCRIPT, encoding="utf-8") as f:
            text = f.read()
        self.assertIn("wait-for-stack-healthy.sh", text)
        self.assertIn("--all", text)
        # 自前のhealthポーリングループ(docker compose ps を直接ループで叩く実装)を
        # 追加していないこと。wait-for-stack-healthy.sh 呼び出し以外で "compose" と
        # "ps" が同時に出てくる行が無いことを確認する。
        for line in text.splitlines():
            if "wait-for-stack-healthy.sh" in line:
                continue
            self.assertFalse(
                "compose" in line and " ps" in line,
                f"独自のhealthポーリングらしき行: {line}",
            )


class BranchCheck(SetupShTestCase):
    def test_passes_on_develop_by_default(self):
        r = self._run_source("check_branch")
        self.assertEqual(0, r.returncode, r.stderr)

    def test_aborts_on_other_branch_by_default(self):
        self._checkout_new_branch("feature/x")
        r = self._run_source("check_branch")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("develop", r.stderr)
        self.assertIn("--branch", r.stderr)
        self.assertIn("--main", r.stderr)

    def test_target_branch_override_accepts_custom_branch(self):
        self._checkout_new_branch("feature/x")
        r = self._run_source('TARGET_BRANCH="feature/x" check_branch')
        self.assertEqual(0, r.returncode, r.stderr)

    def test_target_branch_override_accepts_main(self):
        self._checkout_new_branch("main")
        r = self._run_source('TARGET_BRANCH="main" check_branch')
        self.assertEqual(0, r.returncode, r.stderr)

    def test_end_to_end_aborts_before_any_side_effect(self):
        self._checkout_new_branch("feature/x")
        r = self._run_setup()
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(os.path.join(self.repo, ".env")))
        self.assertEqual([], self._fake_log_lines(), "ブランチ中断前にコマンドが呼ばれている")

    def test_end_to_end_main_flag_allows_main_branch(self):
        self._checkout_new_branch("main")
        r = self._run_setup(["--main"])
        self.assertEqual(0, r.returncode, r.stderr + r.stdout)


class OsCheck(SetupShTestCase):
    def _os_release(self, content):
        path = os.path.join(self.tmp, "os-release")
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        return path

    def test_rejects_unsupported_os(self):
        path = self._os_release('ID=fedora\nID_LIKE="rhel"\n')
        env = self._base_env({"SETUP_SH_OS_RELEASE_FILE": path})
        r = self._run_source("check_os", env=env)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("Ubuntu/Debian", r.stderr)

    def test_accepts_ubuntu(self):
        path = self._os_release('ID=ubuntu\nID_LIKE="debian"\n')
        env = self._base_env({"SETUP_SH_OS_RELEASE_FILE": path})
        r = self._run_source("check_os", env=env)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_accepts_debian_like(self):
        path = self._os_release('ID=pop\nID_LIKE="ubuntu debian"\n')
        env = self._base_env({"SETUP_SH_OS_RELEASE_FILE": path})
        r = self._run_source("check_os", env=env)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_end_to_end_unsupported_os_exits_nonzero_without_side_effects(self):
        path = self._os_release('ID=fedora\nID_LIKE="rhel"\n')
        env = self._base_env({"SETUP_SH_OS_RELEASE_FILE": path})
        r = self._run_setup(env=env)
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(os.path.join(self.repo, ".env")))
        self.assertEqual([], self._fake_log_lines())


class EnvGeneration(SetupShTestCase):
    def test_fresh_generation_has_no_changeme_left(self):
        r = self._run_source("ensure_env_file")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        with open(os.path.join(self.repo, ".env"), encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("changeme_", content)

    def test_fresh_generation_sets_permissions_600(self):
        r = self._run_source("ensure_env_file")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        mode = stat.S_IMODE(os.stat(os.path.join(self.repo, ".env")).st_mode)
        self.assertEqual(0o600, mode)

    def test_fresh_generation_passes_check_env_sh(self):
        r = self._run_source("ensure_env_file")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        chk = subprocess.run(
            ["bash", "scripts/check-env.sh"],
            cwd=self.repo,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, chk.returncode, chk.stdout + chk.stderr)

    def test_fresh_generation_reports_external_keys_still_unset(self):
        r = self._run_source("ensure_env_file")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("LLM_API_KEY", r.stdout)
        self.assertIn("BRAVE_SEARCH_API_KEY", r.stdout)
        self.assertIn("MAIL_PASSWORD", r.stdout)

    def test_fresh_generation_does_not_touch_keycloak_client_secrets(self):
        """realm-export.json と一致させる必要があるため、setup.sh は再生成しない。"""
        with open(os.path.join(self.repo, ".env.example"), encoding="utf-8") as f:
            before = dict(
                l.split("=", 1) for l in f if "=" in l and not l.startswith("#")
            )
        r = self._run_source("ensure_env_file")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        with open(os.path.join(self.repo, ".env"), encoding="utf-8") as f:
            after = dict(l.rstrip("\n").split("=", 1) for l in f if "=" in l and not l.startswith("#"))
        for key in ("KEYCLOAK_SERVICES_CLIENT_SECRET", "KEYCLOAK_WEB_CLIENT_SECRET"):
            self.assertEqual(before[key].strip(), after[key].strip())

    def test_existing_env_is_never_overwritten(self):
        env_path = os.path.join(self.repo, ".env")
        with open(os.path.join(self.repo, ".env.example"), encoding="utf-8") as f:
            original = f.read()
        # 意図的に不足のある `.env`(利用者が手編集途中のもの)を置く。
        custom = original.replace("changeme_root", "my-own-password")
        with open(env_path, "w", encoding="utf-8") as f:
            f.write(custom)
        os.chmod(env_path, 0o600)

        r = self._run_source("ensure_env_file")
        # 既存の .env は上書きしない(check-env.sh がまだ足りないキーを見つけて
        # 非0を返しても、ensure_env_file 自体は「報告」であって「失敗」ではない)。
        with open(env_path, encoding="utf-8") as f:
            after = f.read()
        self.assertEqual(custom, after)

    def test_end_to_end_rerun_with_existing_env_changes_nothing(self):
        r1 = self._run_setup()
        self.assertEqual(0, r1.returncode, r1.stdout + r1.stderr)
        env_path = os.path.join(self.repo, ".env")
        with open(env_path, encoding="utf-8") as f:
            first = f.read()
        first_mtime = os.stat(env_path).st_mtime_ns

        r2 = self._run_setup()
        self.assertEqual(0, r2.returncode, r2.stdout + r2.stderr)
        with open(env_path, encoding="utf-8") as f:
            second = f.read()
        self.assertEqual(first, second)
        self.assertEqual(first_mtime, os.stat(env_path).st_mtime_ns, ".env が再書き込みされている")


class CertGeneration(SetupShTestCase):
    def test_end_to_end_generates_certs_with_permissions_600(self):
        r = self._run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        key_path = os.path.join(self.repo, "certs", "localhost.key")
        self.assertTrue(os.path.exists(key_path))
        mode = stat.S_IMODE(os.stat(key_path).st_mode)
        self.assertEqual(0o600, mode)


class NvidiaToolkit(SetupShTestCase):
    def test_skips_without_nvidia_smi_and_exits_zero(self):
        env = self._base_env(nvidia_smi=False)
        r = self._run_source("ensure_nvidia_toolkit", env=env)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("スキップ", r.stderr)

    def test_skips_install_when_already_installed(self):
        env = self._base_env(nvidia_smi=True, extra={"FAKE_DPKG_INSTALLED": "nvidia-container-toolkit"})
        r = self._run_source("ensure_nvidia_toolkit", env=env)
        self.assertEqual(0, r.returncode, r.stderr)
        for line in self._fake_log_lines():
            self.assertNotIn("apt-get install", line)

    def test_installs_when_nvidia_smi_present_and_not_installed(self):
        env = self._base_env(nvidia_smi=True, extra={"FAKE_DPKG_INSTALLED": ""})
        r = self._run_source("ensure_nvidia_toolkit", env=env)
        self.assertEqual(0, r.returncode, r.stderr)
        log = "\n".join(self._fake_log_lines())
        self.assertIn("nvidia-container-toolkit", log)

    def test_end_to_end_without_nvidia_smi_exits_zero(self):
        r = self._run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("スキップ", r.stdout + r.stderr)


class AptPackageIdempotency(SetupShTestCase):
    def test_skips_when_command_already_present(self):
        r = self._run_source('ensure_apt_package fakepkg bash')
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual([], self._fake_log_lines())

    def test_installs_when_command_missing(self):
        r = self._run_source('ensure_apt_package fakepkg definitely-not-a-real-cmd-xyz')
        self.assertEqual(0, r.returncode, r.stderr)
        log = "\n".join(self._fake_log_lines())
        self.assertIn("apt-get", log)
        self.assertIn("install", log)
        self.assertIn("fakepkg", log)


class DockerGroup(SetupShTestCase):
    def test_no_usermod_when_already_in_group(self):
        env = self._base_env({"FAKE_ID_GROUPS": "sudo adm docker"})
        r = self._run_source("ensure_docker_group", env=env)
        self.assertEqual(0, r.returncode, r.stderr)
        for line in self._fake_log_lines():
            self.assertNotIn("usermod", line)

    def test_usermod_and_relogin_warning_when_missing(self):
        env = self._base_env({"FAKE_ID_GROUPS": "sudo adm"})
        r = self._run_source("ensure_docker_group", env=env)
        self.assertEqual(0, r.returncode, r.stderr)
        log = "\n".join(self._fake_log_lines())
        self.assertIn("usermod", log)
        self.assertIn("docker", log)
        self.assertIn("再ログイン", r.stderr)


class DockerAlreadyInstalled(SetupShTestCase):
    def test_ensure_docker_skips_install_when_present(self):
        r = self._run_source("ensure_docker")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = "\n".join(self._fake_log_lines())
        self.assertNotIn("get.docker.com", log)


class DockerGroupActivation(SetupShTestCase):
    """#960 レビュー指摘: usermod -aG docker 直後、同一実行内で docker compose を
    叩くには sg docker 経由で実効グループを反映しないと permission denied になる
    (再ログイン待ちにしてはAC1の「1回の実行で完走」を満たせない)。"""

    def test_end_to_end_activates_group_via_sg_when_freshly_added(self):
        env = self._base_env({"FAKE_ID_GROUPS": "sudo adm"})
        r = self._run_setup(env=env)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

        log = self._fake_log_lines()
        self.assertTrue(
            any(l.startswith("sg docker") for l in log),
            f"docker グループ追加直後は sg docker 経由で compose を叩く必要がある: {log}",
        )

    def test_end_to_end_skips_sg_when_already_in_group(self):
        env = self._base_env({"FAKE_ID_GROUPS": "sudo adm docker"})
        r = self._run_setup(env=env)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

        log = self._fake_log_lines()
        self.assertFalse(
            any(l.startswith("sg docker") for l in log),
            f"既に docker グループに所属しているのに sg 経由にする必要は無い: {log}",
        )


class HappyPath(SetupShTestCase):
    def test_end_to_end_success(self):
        r = self._run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("https://localhost", r.stdout)
        self.assertIn("/setup", r.stdout)

        log = "\n".join(self._fake_log_lines())
        self.assertIn("compose", log)
        self.assertIn("up", log)

        env_path = os.path.join(self.repo, ".env")
        self.assertTrue(os.path.exists(env_path))
        self.assertEqual(0o600, stat.S_IMODE(os.stat(env_path).st_mode))


class DocumentationLeadsWithSetupSh(unittest.TestCase):
    """#960 要件8: README / docs/setup.md / docs/GETTING_STARTED.md を setup.sh 前提に書き換える。"""

    def test_readme_mentions_setup_sh_in_startup_section(self):
        with open(os.path.join(REPO_ROOT, "README.md"), encoding="utf-8") as f:
            text = f.read()
        section = text.split("## アプリケーションの起動(Docker)", 1)[1]
        section = section.split("## VSCode拡張機能", 1)[0]
        self.assertIn("setup.sh", section)

    def test_setup_md_quickstart_mentions_setup_sh(self):
        with open(os.path.join(REPO_ROOT, "docs", "setup.md"), encoding="utf-8") as f:
            text = f.read()
        section = text.split("## クイックスタート", 1)[1]
        section = section.split("## システム要件", 1)[0]
        self.assertIn("setup.sh", section)

    def test_getting_started_mentions_setup_sh(self):
        with open(os.path.join(REPO_ROOT, "docs", "GETTING_STARTED.md"), encoding="utf-8") as f:
            text = f.read()
        self.assertIn("setup.sh", text)


if __name__ == "__main__":
    unittest.main()
