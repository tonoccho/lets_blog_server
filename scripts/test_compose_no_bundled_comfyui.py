#!/usr/bin/env python3
"""同梱の ComfyUI コンテナが無く、外部(GPU PC)の ComfyUI へ URL で接続する構成であることの検証(#1746)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない基準を
サービス/スクリプトレベルのテストで表現することを認めている。対象は `docker-compose.yml` が
何をコンテナ・ボリューム・マウントとして宣言するか、および `.env.example` / 文書の記述で、
Playwright からは観測できない。`docker compose config` は GPU もイメージ取得も要らない。

## 経緯

かつて同梱の `comfyui`(gpu profile、#1066)と `comfyui-cpu`(cpu profile、#1395)があり、
その契約テスト `test_comfyui_gpu_profile.py` が compose の読み出し土台(`ComposeConfigHarness`)を
持っていた。ComfyUI を GPU PC で動かす構成へ移した(#1746)ため、同梱専用のテストは削除し
(機能削除に伴う削除であり skip ではない)、土台だけをこのファイルへ移した。
"""

import os
import subprocess
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
STUB_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.e2e-stubs.yml")
SHARED_HOST_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.shared-host.yml")
HOST_TESTS_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.host-tests.yml")
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")


class ComposeConfigHarness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="compose-config-")
        self.addCleanup(shutil_rmtree, self.tmp)
        self.env_file = os.path.join(self.tmp, ".env")
        with open(ENV_EXAMPLE, encoding="utf-8") as src, open(
            self.env_file, "w", encoding="utf-8"
        ) as dst:
            dst.write(src.read())

    def _compose(self, subcommand, extra_env, compose_files, profiles=()):
        env = dict(os.environ)
        env.pop("COMPOSE_PROFILES", None)
        if extra_env:
            env.update(extra_env)
        args = ["docker", "compose", "--env-file", self.env_file]
        for f in compose_files:
            args += ["-f", f]
        for p in profiles:
            args += ["--profile", p]
        args += subcommand
        r = subprocess.run(
            args, cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env
        )
        self.assertEqual(0, r.returncode, "docker compose %s が失敗した:\n%s%s" % (" ".join(subcommand), r.stdout, r.stderr))
        return r.stdout

    def config_services(self, extra_env=None, compose_files=(COMPOSE,), profiles=()):
        return self._compose(["config", "--services"], extra_env, compose_files, profiles).split()

    def full_config(self, extra_env=None, compose_files=(COMPOSE,), profiles=()):
        return yaml.safe_load(self._compose(["config"], extra_env, compose_files, profiles))


def shutil_rmtree(path):
    import shutil

    shutil.rmtree(path, ignore_errors=True)


# 利用者が重ねうる組み合わせ(オーバーレイの重ね掛けを含む)。
OVERLAYS = {
    "base": (COMPOSE,),
    "e2e-stubs": (COMPOSE, STUB_COMPOSE),
    "shared-host": (COMPOSE, SHARED_HOST_COMPOSE),
    "host-tests": (COMPOSE, HOST_TESTS_COMPOSE),
    "all-overlays": (COMPOSE, STUB_COMPOSE, SHARED_HOST_COMPOSE, HOST_TESTS_COMPOSE),
}
REMOVED_SERVICES = ("comfyui", "comfyui-cpu")
REMOVED_VOLUMES = ("comfyui_models", "comfyui_output")
REMOVED_CONTAINER_NAMES = ("lbs-comfyui", "lbs-comfyui-cpu")


class NoBundledComfyui(ComposeConfigHarness):
    """AC1: `docker compose --profile '*' config` に同梱の ComfyUI が無い。"""

    def configs(self):
        """重ね掛けの組み合わせごとの `(名前, config)`(`--profile '*'` で全プロファイルを有効にして読む)。"""
        return [
            (name, self.full_config(compose_files=files, profiles=("*",)))
            for name, files in OVERLAYS.items()
        ]

    def test_no_comfyui_services(self):
        for overlay, config in self.configs():
            for name in REMOVED_SERVICES:
                self.assertNotIn(name, config["services"], overlay)

    def test_no_comfyui_container_names(self):
        for overlay, config in self.configs():
            names = {s.get("container_name") for s in config["services"].values()}
            for name in REMOVED_CONTAINER_NAMES:
                self.assertNotIn(name, names, overlay)

    def test_no_comfyui_volumes(self):
        for overlay, config in self.configs():
            for name in REMOVED_VOLUMES:
                self.assertNotIn(name, config.get("volumes") or {}, overlay)

    def test_no_service_mounts_a_comfyui_volume(self):
        for overlay, config in self.configs():
            for svc_name, svc in config["services"].items():
                for v in svc.get("volumes") or []:
                    self.assertFalse(
                        str(v.get("source", "")).startswith("comfyui_"),
                        "[%s] %s が %s をマウントしている" % (overlay, svc_name, v),
                    )

    def test_media_has_no_comfyui_models_mount(self):
        for overlay, config in self.configs():
            targets = [v.get("target") for v in config["services"]["media"].get("volumes") or []]
            self.assertNotIn("/comfyui-models", targets, overlay)

    def test_media_has_no_comfyui_models_path_env(self):
        for overlay, config in self.configs():
            self.assertNotIn("COMFYUI_MODELS_PATH", config["services"]["media"]["environment"], overlay)

    def test_media_keeps_the_default_checkpoint_name(self):
        """COMFYUI_CHECKPOINT は選択の既定値(`app.comfyui-checkpoint`)として使われるので残す。"""
        env = self.full_config(profiles=("*",))["services"]["media"]["environment"]
        self.assertIn("COMFYUI_CHECKPOINT", env)

    def test_comfyui_profiles_are_gone_from_default_up_and_opt_in(self):
        for profile in ("gpu", "cpu"):
            services = self.config_services(extra_env={"COMPOSE_PROFILES": profile})
            for name in REMOVED_SERVICES:
                self.assertNotIn(name, services)

    def test_e2e_stub_overlay_keeps_the_comfyui_stub(self):
        """受け入れテストの画像生成はスタブで通す。スタブは同梱 ComfyUI の代替ではなく検証用の別物。"""
        services = self.config_services(compose_files=OVERLAYS["e2e-stubs"])
        self.assertIn("comfyui-stub", services)

    def test_media_waits_for_nothing_bundled_comfyui(self):
        for overlay, config in self.configs():
            for svc_name, svc in config["services"].items():
                for dep in (svc.get("depends_on") or {}):
                    self.assertNotIn(dep, REMOVED_SERVICES, "[%s] %s が %s に依存している" % (overlay, svc_name, dep))


class EnvExampleHasNoBundledComfyui(unittest.TestCase):
    def setUp(self):
        with open(ENV_EXAMPLE, encoding="utf-8") as f:
            self.text = f.read()

    def test_bundled_comfyui_variables_are_removed(self):
        for var in ("COMFYUI_IMAGE", "COMFYUI_CLI_ARGS", "COMFYUI_MEM_LIMIT", "COMFYUI_CPU_MEM_LIMIT", "COMFYUI_MODELS_PATH"):
            self.assertNotIn(var, self.text)

    def test_gpu_and_cpu_profiles_are_no_longer_explained(self):
        for phrase in ("COMPOSE_PROFILES=gpu", "COMPOSE_PROFILES=cpu", "comfyui-cpu", "comfyui-boot"):
            self.assertNotIn(phrase, self.text)

    def test_default_checkpoint_name_stays_and_points_to_the_gpu_pc(self):
        self.assertIn("COMFYUI_CHECKPOINT=", self.text)
        self.assertNotIn("docker cp", self.text)
        self.assertIn("GPU PC", self.text)


class DocumentationDescribesExternalComfyui(unittest.TestCase):
    EXTERNAL_DOC = "docs/EXTERNAL_COMFYUI.md"

    def read(self, name):
        with open(os.path.join(REPO_ROOT, name), encoding="utf-8") as f:
            return f.read()

    def test_readme_no_longer_describes_a_bundled_comfyui(self):
        text = self.read("README.md")
        for phrase in (
            "comfyui-cpu",
            "COMPOSE_PROFILES=cpu",
            "COMPOSE_PROFILES=gpu",
            "COMFYUI_IMAGE",
            "ComfyUI (GPU)",
            "ComfyUIのモデルファイル",
        ):
            self.assertNotIn(phrase, text)

    def test_readme_does_not_require_a_gpu_for_comfyui_on_this_host(self):
        text = self.read("README.md")
        self.assertNotIn("NVIDIA GPU(VRAM 16GB以上)必須", text)
        self.assertNotIn("ComfyUI(画像生成)がGPUを使用するため", text)

    def test_readme_links_to_the_external_comfyui_guide(self):
        self.assertIn(self.EXTERNAL_DOC, self.read("README.md"))

    def test_architecture_doc_no_longer_lists_a_bundled_comfyui(self):
        text = self.read("docs/DOCKER_COMPOSE_ARCHITECTURE.md")
        self.assertNotIn("| `comfyui` |", text)
        for phrase in (
            "lbs-comfyui",
            "comfyui-cpu",
            "COMFYUI_IMAGE",
            "COMFYUI_MEM_LIMIT",
            "COMFYUI_CPU_MEM_LIMIT",
            "test_comfyui_gpu_profile",
            "comfyui_models",
            "comfyui_output",
        ):
            self.assertNotIn(phrase, text)
        self.assertIn(self.EXTERNAL_DOC, text)

    def test_external_guide_covers_setup_security_models_and_ui(self):
        text = self.read(self.EXTERNAL_DOC)
        for phrase in (
            "--listen 0.0.0.0",  # ComfyUI の起動
            "認証",  # 認証が無いことの注意
            "LAN",  # LAN 内からだけ到達できるようにする
            "models/checkpoints",  # チェックポイントの置き場
            "models/loras",  # LoRA の置き場
            "http://192.168.1.71:8188",  # 管理画面で設定する URL の例
            "システム設定",
            "プロジェクト設定",
        ):
            self.assertIn(phrase, text)

    def test_external_guide_covers_migration_of_existing_environments(self):
        text = self.read(self.EXTERNAL_DOC)
        for phrase in (
            "comfyui_base_url",  # 接続先の変更
            "http://comfyui:8188",  # 旧既定値
            "comfyui_models",  # 移すべきボリューム
            "docker volume rm",  # 手動削除
            "comfyui_output",
        ):
            self.assertIn(phrase, text)


if __name__ == "__main__":
    unittest.main()
