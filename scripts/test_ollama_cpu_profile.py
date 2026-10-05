#!/usr/bin/env python3
"""`ollama-cpu` サービスの compose 契約(#1585)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

検証したいのは `docker-compose.yml` の定義そのもの(待機側コンテナを `docker compose create` で
作れること、既定の起動で二重に起動しないこと、モデルのボリュームを共有すること)で、Web UI から
観測できない。受入基準5「CPU 構成へ切り替えた後も GPU 構成で取得済みのモデルを使った LLM 要求が成功する」
のうち、compose で保証できる部分(`ollama_models` の共有・接続先エイリアス `ollama` が不変)をここで
静的に検査する。実機での LLM 要求は #1402 の範囲である。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")


class OllamaCpuComposeContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="ollama-cpu-profile-")
        cls.env_file = os.path.join(cls.tmp, ".env")
        shutil.copy(ENV_EXAMPLE, cls.env_file)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def compose(self, *args, profiles=None):
        env = dict(os.environ)
        env.pop("COMPOSE_PROFILES", None)
        if profiles is not None:
            env["COMPOSE_PROFILES"] = profiles
        r = subprocess.run(
            ["docker", "compose", "--env-file", self.env_file, "-f", COMPOSE, *args],
            cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env,
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return r.stdout

    def services(self, profiles=None):
        return self.compose("config", "--services", profiles=profiles).split()

    def config(self, profiles="ollama-cpu"):
        return yaml.safe_load(self.compose("config", profiles=profiles))["services"]

    def test_ollama_cpu_is_not_in_the_default_service_list(self):
        self.assertNotIn("ollama-cpu", self.services())
        self.assertIn("ollama", self.services())

    def test_cpu_profile_does_not_pull_in_ollama_cpu(self):
        """`cpu` に入れると GPU の無いホストで ollama と二重に起動する(Issue #1585 Problem 3)。"""
        self.assertNotIn("ollama-cpu", self.services(profiles="cpu"))

    def test_ollama_cpu_is_selectable_with_its_own_profile(self):
        self.assertIn("ollama-cpu", self.services(profiles="ollama-cpu"))

    def test_ollama_cpu_has_no_runtime_and_a_distinct_container_name(self):
        cpu = self.config()["ollama-cpu"]
        self.assertNotIn("runtime", cpu)
        self.assertEqual("lbs-ollama-cpu", cpu["container_name"])

    def test_ollama_cpu_keeps_the_connection_alias_and_shares_models(self):
        cpu = self.config()["ollama-cpu"]
        self.assertIn("ollama", cpu["networks"]["lbs-net"]["aliases"])
        self.assertTrue(
            any(str(v.get("source")) == "ollama_models" and v.get("target") == "/root/.ollama"
                for v in cpu["volumes"]),
            cpu["volumes"],
        )

    def test_ollama_cpu_shares_environment_and_healthcheck_with_ollama(self):
        services = self.config()
        gpu, cpu = services["ollama"], services["ollama-cpu"]
        self.assertEqual(gpu["healthcheck"]["test"], cpu["healthcheck"]["test"])
        for key in ("OLLAMA_KEEP_ALIVE", "OLLAMA_MAX_LOADED_MODELS", "OLLAMA_CONTEXT_LENGTH"):
            self.assertEqual(gpu["environment"][key], cpu["environment"][key], key)
        self.assertEqual(gpu["image"], cpu["image"])

    def test_the_operator_procedure_is_documented(self):
        command = "docker compose --profile ollama-cpu create ollama-cpu"
        for path in (ENV_EXAMPLE, os.path.join(REPO_ROOT, "docs", "DOCKER_COMPOSE_ARCHITECTURE.md")):
            with open(path, encoding="utf-8") as f:
                self.assertIn(command, f.read(), path)


if __name__ == "__main__":
    unittest.main()
