#!/usr/bin/env python3
"""受け入れテスト環境で `ollama` / `ollama-model-init` が起動しないことの検証(#1090)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象は `docker compose up -d` が何をコンテナ化するかという起動構成そのもので、
Playwright(Web UI)からは観測できない。`CLAUDE.md` → **Test-First Implementation** が
認める「Web UI から到達できない基準はサービス/スクリプトレベルのテストで表現する」に当たる。
`docker compose config` は GPU もイメージ取得も要らない。

## 設計判断: `docker-compose.e2e-stubs.yml` での `profiles`

`deploy.replicas: 0` は明示起動が難しい。実測では `docker compose up -d <svc>` は
profile を自動で有効にして起動でき、いったん作られたコンテナは `docker compose start <svc>`
でも起動できる(#1402 の `composeServiceControl` が使う形)。`replicas: 0` はコンテナを
作らないため `start` が使えない。`profiles` は上書きファイル側にだけ置くので
`docker-compose.yml` 単独の挙動は変わらず、`up -d` の引数も増えない。
"""

import os
import unittest

import test_comfyui_gpu_profile as base

OLLAMA_SERVICES = ("ollama", "ollama-model-init")
AT_FILES = (base.COMPOSE, base.STUB_COMPOSE)


class OllamaDisabledInAcceptanceStack(base.ComposeConfigHarness):
    def test_at_stack_default_up_excludes_ollama(self):
        services = self.config_services(compose_files=AT_FILES)
        for name in OLLAMA_SERVICES:
            self.assertNotIn(name, services)

    def test_dev_stack_still_includes_ollama(self):
        services = self.config_services()
        for name in OLLAMA_SERVICES:
            self.assertIn(name, services)

    def test_ollama_can_be_started_explicitly_in_at_stack(self):
        services = self.config_services(
            extra_env={"COMPOSE_PROFILES": "ollama"}, compose_files=AT_FILES
        )
        for name in OLLAMA_SERVICES:
            self.assertIn(name, services)

    def test_ollama_definitions_unchanged_apart_from_profiles(self):
        dev = self.full_config()["services"]
        at = self.full_config(
            extra_env={"COMPOSE_PROFILES": "ollama"}, compose_files=AT_FILES
        )["services"]
        for name in OLLAMA_SERVICES:
            expected = dict(dev[name])
            actual = dict(at[name])
            self.assertEqual(["ollama"], actual.pop("profiles"))
            expected.pop("profiles", None)
            self.assertEqual(expected, actual)

    def test_at_llm_still_uses_the_ollama_provider_and_stub_model(self):
        """#1567: 接続先(スタブの URL)は環境変数ではなく DB へ投入する。ここに残るのは種別とモデル名。"""
        env = self.full_config(compose_files=AT_FILES)["services"]["platform"]["environment"]
        self.assertEqual("OLLAMA", env["LLM_PROVIDER"])
        self.assertEqual("e2e-stub-model", env["LLM_OLLAMA_MODEL"])
        self.assertNotIn("LLM_OLLAMA_BASE_URL", env)


if __name__ == "__main__":
    unittest.main()
