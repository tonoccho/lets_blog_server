#!/usr/bin/env python3
"""`comfyui` の無条件GPU予約が既定起動を中断させないことの検証(#1066)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求めている。

本Issueの対象は `docker-compose.yml` の `comfyui` サービス定義そのものであり、
検証したい振る舞い(「GPUの無いホストで `docker compose up -d` が中断しないこと」)は
NVIDIA GPU / NVIDIA Container Toolkit の有無というホスト環境に依存する。E2E環境の
Playwrightからは`docker compose`の起動そのものへ到達できず、Web UIから観測できる
振る舞いでもない。同節が認める「Web UIから到達できない基準は、その旨を明示して
サービス/スクリプトレベルのテストで表現する」に当たる。

## 何を検査するか(設計判断: profiles によるオプトイン)

`docs/DOCKER_COMPOSE_ARCHITECTURE.md`(#1086)は「#1066は`comfyui`にollamaと同じ
`runtime: ${GPU_RUNTIME:-}` の1行を適用すればよい」と書いている。しかし ollama と
comfyui には決定的な違いがある。ollama はGPUが無くてもCPUで正常に動作するのに対し、
comfyui(既定イメージ `yanwk/comfyui-boot:cu130-slim`)はCUDA前提のビルドで、
README.mdの「ハードウェア要件」はCPUのみで動かすには**別のイメージタグへの変更**を
要求している。本Issueの Out of Scope は「ComfyUIをCPUで動かすこと」を明示的に除外して
おり、`runtime:` だけを流用すると GPU の無いホストでは既定ランタイムで起動を試み、
CUDA前提のイメージがクラッシュループする(`restart: unless-stopped`のため無限に
再起動を繰り返す)おそれがある。これは「Createdのまま止まる」よりはましだが、
`state=running`に安定しないため `wait-for-stack-healthy.sh --all` の判定
(ヘルスチェックが無いサービスは`state==running`を見る)がタイムアウトするリスクが
残り、AC2(`verify-clean-volume-boot.sh`のタイムアウト無し完了)を確実には満たせない。

そこで本Issueでは `comfyui` に `profiles: ["gpu"]` を付与し、既定の
`docker compose up -d`(サービス無指定)からは**コンテナ自体を作らない**方式を採る。
GPUを持つホストは `.env` の `COMPOSE_PROFILES=gpu`(または `docker compose --profile gpu
up -d`)で明示的にオプトインする。ollamaの`runtime:`と同じ「単一行での回避」ではないが、
comfyuiがCPUで正常動作しない以上、`docker compose ps --all`に存在すること自体を
避けるほうが確実である(Issueの受入基準は「`wait-for-stack-healthy.sh --all`の
待機対象から外れる」を明示的に許容している)。

以下はその静的な検証であり、GPU・NVIDIA Container Toolkit・イメージのpullを
一切必要としない(`docker compose config`はホストのGPU有無を見ない)。
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
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")


class ComposeConfigHarness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="comfyui-gpu-profile-")
        self.addCleanup(shutil_rmtree, self.tmp)
        self.env_file = os.path.join(self.tmp, ".env")
        with open(ENV_EXAMPLE, encoding="utf-8") as src, open(
            self.env_file, "w", encoding="utf-8"
        ) as dst:
            dst.write(src.read())

    def config_services(self, extra_env=None, compose_files=(COMPOSE,)):
        env = dict(os.environ)
        env.pop("COMPOSE_PROFILES", None)
        if extra_env:
            env.update(extra_env)
        args = ["docker", "compose", "--env-file", self.env_file]
        for f in compose_files:
            args += ["-f", f]
        args += ["config", "--services"]
        r = subprocess.run(
            args, cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env
        )
        self.assertEqual(0, r.returncode, "docker compose config --services が失敗した:\n" + r.stdout + r.stderr)
        return r.stdout.split()

    def full_config(self, extra_env=None, compose_files=(COMPOSE,)):
        env = dict(os.environ)
        env.pop("COMPOSE_PROFILES", None)
        if extra_env:
            env.update(extra_env)
        args = ["docker", "compose", "--env-file", self.env_file]
        for f in compose_files:
            args += ["-f", f]
        args += ["config"]
        r = subprocess.run(
            args, cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env
        )
        self.assertEqual(0, r.returncode, "docker compose config が失敗した:\n" + r.stdout + r.stderr)
        return yaml.safe_load(r.stdout)


def shutil_rmtree(path):
    import shutil

    shutil.rmtree(path, ignore_errors=True)


class ComfyuiExcludedFromDefaultUp(ComposeConfigHarness):
    """AC1/AC2: サービス無指定の起動対象からcomfyuiが外れていること。"""

    def test_comfyui_is_not_in_default_service_list(self):
        services = self.config_services()
        self.assertNotIn(
            "comfyui",
            services,
            "既定のdocker compose up -dがcomfyuiをコンテナ化しようとする"
            "(GPUの無いホストでのdeploy.resources.reservations.devices予約に失敗する)",
        )

    def test_comfyui_is_not_in_default_service_list_with_e2e_stub_overlay(self):
        """AC1が名指しするコマンドそのもの
        (`docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d`)
        の起動対象を検査する。"""
        services = self.config_services(compose_files=(COMPOSE, STUB_COMPOSE))
        self.assertNotIn("comfyui", services)


class ComfyuiOptInOnGpuHost(ComposeConfigHarness):
    """AC3: GPUホストでは明示的な切り替えでcomfyuiが従来どおり起動すること。"""

    def test_comfyui_is_included_when_gpu_profile_is_active(self):
        services = self.config_services(extra_env={"COMPOSE_PROFILES": "gpu"})
        self.assertIn(
            "comfyui",
            services,
            "COMPOSE_PROFILES=gpu を指定してもcomfyuiが起動対象に含まれない",
        )

    def test_comfyui_still_reserves_the_nvidia_gpu_when_opted_in(self):
        """GPUホストでの挙動を落とさないこと(無条件のGPU予約自体は維持する)。"""
        config = self.full_config(extra_env={"COMPOSE_PROFILES": "gpu"})
        comfyui = config["services"]["comfyui"]
        devices = comfyui["deploy"]["resources"]["reservations"]["devices"]
        self.assertEqual("nvidia", devices[0]["driver"])
        self.assertEqual(["gpu"], devices[0]["capabilities"])


if __name__ == "__main__":
    unittest.main()
