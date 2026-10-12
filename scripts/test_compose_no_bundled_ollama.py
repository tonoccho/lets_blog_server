#!/usr/bin/env python3
"""同梱の Ollama(3サービスとボリューム)が compose に無いことの検証(#1745)。

    python3 -m unittest scripts.test_compose_no_bundled_ollama

compose の構成は Web UI から到達できないため、Gherkin ではなく契約テストで表す
(CLAUDE.md → Test-First Implementation)。`docker compose config` は静的で、
コンテナもイメージの pull も要らない。

Ollama は GPU 付きの別 PC で動かし、Let's Blog は URL で接続する。したがって
`docker compose --profile '*' config`(profile 付きも含める)にも、受け入れテストのオーバーレイを
重ねた場合にも、`ollama` / `ollama-cpu` / `ollama-model-init` サービスと `ollama_models` ボリュームは現れない。
"""

import json
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

BASE = "docker-compose.yml"
AT_OVERLAY = "docker-compose.e2e-stubs.yml"
REMOVED_SERVICES = ("ollama", "ollama-cpu", "ollama-model-init")
REMOVED_VOLUMES = ("ollama_models",)


def render(files):
    env = {k: v for k, v in os.environ.items() if k != "COMPOSE_PROFILES"}
    args = ["docker", "compose"]
    for f in files:
        args += ["-f", f]
    args += ["--profile", "*", "config", "--format", "json"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)


class NoBundledOllama(unittest.TestCase):
    def check(self, files):
        config = render(files)
        services = config["services"]
        self.assertEqual([], sorted(set(REMOVED_SERVICES) & set(services)), f"同梱 Ollama のサービスが残っている({files})")
        for name, svc in services.items():
            self.assertNotIn("ollama", (svc.get("container_name") or ""), f"{name} が ollama のコンテナ名を持つ")
            for alias in ((svc.get("networks") or {}).get("lbs-net") or {}).get("aliases", []):
                self.assertNotIn("ollama", alias, f"{name} が ollama のエイリアスを持つ")
        self.assertEqual(
            [], sorted(set(REMOVED_VOLUMES) & set(config.get("volumes") or {})), f"同梱 Ollama のボリュームが残っている({files})"
        )

    def test_base_has_no_ollama(self):
        self.check([BASE])

    def test_acceptance_overlay_has_no_ollama(self):
        self.check([BASE, AT_OVERLAY])

    def test_no_service_depends_on_ollama(self):
        for files in ([BASE], [BASE, AT_OVERLAY]):
            for name, svc in render(files)["services"].items():
                deps = svc.get("depends_on") or {}
                self.assertEqual(
                    [], sorted(set(REMOVED_SERVICES) & set(deps)), f"{name} が同梱 Ollama に depends_on している"
                )

    def test_acceptance_llm_provider_and_stub_model_unchanged(self):
        """受け入れテストの LLM は llm-stub。接続先は DB へ投入するので、ここに残るのは種別とモデル名だけ。"""
        env = render([BASE, AT_OVERLAY])["services"]["platform"]["environment"]
        self.assertEqual("OLLAMA", env["LLM_PROVIDER"])
        self.assertEqual("e2e-stub-model", env["LLM_OLLAMA_MODEL"])
        self.assertNotIn("LLM_OLLAMA_BASE_URL", env)


if __name__ == "__main__":
    unittest.main()
