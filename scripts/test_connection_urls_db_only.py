#!/usr/bin/env python3
"""Ollama / ComfyUI の接続先を環境変数で渡さない構成の検証(#1567)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象は `docker-compose.yml` / `docker-compose.e2e-stubs.yml` / `.env.example` / 関連ドキュメント
と、受け入れテスト前処理の `scripts/e2e-clear-llm-db-overrides.sh` で、コンテナ構成と運用手順そのもの。
Playwright(Web UI)からは観測できない。`CLAUDE.md` → **Test-First Implementation** が認める
「Web UI から到達できない基準はサービス/スクリプトレベルのテストで表現する」に当たる。
(接続先が DB だけで決まる振る舞い自体は、JUnit と Gherkin `ai-connection-panel.feature` /
`system-settings.feature` が固定する。)

## 何を固定するか

接続先は プロジェクト設定 → システム設定(DB) だけで決まり、環境変数では決まらない。
したがって環境変数を渡す口(compose の `environment`、`.env.example`、手順書)も残さない。
`ollama-model-init` が使うモデル名 `LLM_OLLAMA_MODEL` は接続先ではないので残す。
受け入れテスト環境はスタブの URL を DB に投入する(DB の値は暗号化されるため SQL 直書きではなく
`scripts/e2e_encrypt_setting.py` で `CredentialCipher` と同じ形式にする)。
"""

import base64
import os
import re
import unittest

import test_comfyui_gpu_profile as base

REPO_ROOT = base.REPO_ROOT
AT_FILES = (base.COMPOSE, base.STUB_COMPOSE)
CLEAR_SCRIPT = os.path.join(REPO_ROOT, "scripts", "e2e-clear-llm-db-overrides.sh")
REMOVED = ("LLM_OLLAMA_BASE_URL", "COMFYUI_BASE_URL")
DOCS = (
    "README.md",
    ".env.example",
    "docker-compose.yml",
    "docker-compose.e2e-stubs.yml",
    "docs/DOCKER_COMPOSE_ARCHITECTURE.md",
    "docs/ACCEPTANCE_TESTING.md",
    "docs/e2e-testing.md",
)


def read(rel):
    with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
        return f.read()


class ComposeHasNoConnectionUrlEnv(base.ComposeConfigHarness):
    def env_of(self, name, compose_files):
        return self.full_config(compose_files=compose_files)["services"][name]["environment"]

    def test_platform_and_media_do_not_receive_connection_urls(self):
        for files in ((base.COMPOSE,), AT_FILES):
            for name in ("platform", "media"):
                env = self.env_of(name, files)
                for var in REMOVED:
                    self.assertNotIn(var, env, "%s の environment に %s が残っている" % (name, var))

    def test_model_name_variable_is_kept_for_ollama_model_init(self):
        config = self.full_config(extra_env={"COMPOSE_PROFILES": "ollama"})["services"]
        self.assertIn("LLM_OLLAMA_MODEL", config["ollama-model-init"]["environment"])
        self.assertIn("LLM_OLLAMA_MODEL", config["platform"]["environment"])

    def test_stub_overlay_defines_neither_variable(self):
        active = [
            line
            for line in read("docker-compose.e2e-stubs.yml").splitlines()
            if not line.lstrip().startswith("#")
        ]
        for var in REMOVED:
            self.assertEqual([], [l for l in active if var in l], var)


class EnvExampleAndDocs(unittest.TestCase):
    def test_env_example_has_no_connection_url_assignment(self):
        for var in REMOVED:
            self.assertIsNone(re.search(r"^\s*#?\s*%s=" % var, read(".env.example"), re.M), var)

    def test_env_example_keeps_the_model_name(self):
        self.assertRegex(read(".env.example"), r"(?m)^LLM_OLLAMA_MODEL=")

    def test_docs_do_not_tell_readers_to_set_the_removed_variables(self):
        """言及してよいのは「廃止した」と書く行だけ。"""
        offenders = []
        for rel in DOCS:
            for number, line in enumerate(read(rel).splitlines(), 1):
                if any(var in line for var in REMOVED) and "廃止" not in line:
                    offenders.append("%s:%d" % (rel, number))
        self.assertEqual([], offenders)


class ClearScriptSeedsStubUrls(unittest.TestCase):
    def test_script_inserts_stub_urls_for_both_keys(self):
        text = read("scripts/e2e-clear-llm-db-overrides.sh")
        self.assertIn("http://llm-stub:8080", text)
        self.assertIn("http://comfyui-stub:8080", text)
        self.assertRegex(text, r"INSERT INTO lbs_platform\.system_settings")
        self.assertIn("e2e_encrypt_setting.py", text)

    def test_script_header_describes_db_injection_not_env_fallback(self):
        header = "\n".join(
            l for l in read("scripts/e2e-clear-llm-db-overrides.sh").splitlines() if l.startswith("#")
        )
        self.assertIn("スタブ", header)
        self.assertNotIn("環境変数の既定値へ戻る", header)


class EncryptSettingHelper(unittest.TestCase):
    KEY = base64.b64encode(bytes(range(32))).decode()

    def helper(self):
        import e2e_encrypt_setting

        return e2e_encrypt_setting

    def test_output_round_trips_in_credential_cipher_format(self):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        hexed = self.helper().encrypt_hex(self.KEY, "http://llm-stub:8080")
        raw = bytes.fromhex(hexed)
        iv, body = raw[:12], raw[12:]
        plain = AESGCM(base64.b64decode(self.KEY)).decrypt(iv, body, None)
        self.assertEqual(b"http://llm-stub:8080", plain)

    def test_each_call_uses_a_fresh_iv(self):
        h = self.helper()
        self.assertNotEqual(h.encrypt_hex(self.KEY, "x"), h.encrypt_hex(self.KEY, "x"))

    def test_rejects_a_key_that_is_not_32_bytes(self):
        with self.assertRaises(ValueError):
            self.helper().encrypt_hex(base64.b64encode(b"short").decode(), "x")

    def test_main_prints_hex_for_the_key_in_the_environment(self):
        h = self.helper()
        os.environ["APP_ENCRYPTION_KEY"] = self.KEY
        self.addCleanup(os.environ.pop, "APP_ENCRYPTION_KEY", None)
        import io
        import contextlib

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, h.main(["http://comfyui-stub:8080"]))
        self.assertRegex(out.getvalue().strip(), r"^[0-9a-f]+$")

    def test_main_fails_without_a_key_or_argument(self):
        h = self.helper()
        os.environ.pop("APP_ENCRYPTION_KEY", None)
        self.assertNotEqual(0, h.main(["x"]))
        os.environ["APP_ENCRYPTION_KEY"] = self.KEY
        self.addCleanup(os.environ.pop, "APP_ENCRYPTION_KEY", None)
        self.assertNotEqual(0, h.main([]))


if __name__ == "__main__":
    unittest.main()
