#!/usr/bin/env python3
"""別ホストの lets_blog_server_extensions の自己署名証明書を信頼させるオーバーレイの契約テスト(#1763)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない基準を
サービス/スクリプトレベルのテストで表現することを認めている。対象は `docker compose config` が
展開するマウント・エントリポイント・環境変数で、Playwright からは観測できない。
実際に JVM の信頼ストアへ入ること(keytool)は QA で実機確認する。
"""

import os
import unittest

from test_compose_no_bundled_comfyui import COMPOSE, REPO_ROOT, ComposeConfigHarness

EXTRA_CA_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.extra-ca.yml")
TRUSTED = ("ai", "media", "platform")
ENTRYPOINT = ["/__cacert_entrypoint.sh", "java", "-jar", "/app/app.jar"]


def overlay(test, extra_env=None):
    return test.full_config(extra_env=extra_env, compose_files=(COMPOSE, EXTRA_CA_COMPOSE))


def ca_mounts(config, name):
    return [
        v
        for v in config["services"][name].get("volumes") or []
        if str(v.get("target", "")).startswith("/certificates/")
    ]


class ExtraCaOverlayTrust(ComposeConfigHarness):
    """AC1: ai / media / platform だけに証明書のマウントと信頼ストアへ追加する設定がある。"""

    def test_only_three_services_get_the_mount_and_settings(self):
        base = self.full_config()
        over = overlay(self)
        for name, svc in over["services"].items():
            if name in TRUSTED:
                continue
            self.assertEqual(base["services"][name], svc, name)

    def test_mount_is_read_only_default_path(self):
        over = overlay(self)
        for name in TRUSTED:
            mounts = ca_mounts(over, name)
            self.assertEqual(1, len(mounts), name)
            self.assertTrue(mounts[0]["read_only"], name)
            self.assertEqual("bind", mounts[0]["type"], name)
            self.assertTrue(mounts[0]["source"].endswith("/certs/lbx-ca.crt"), name)
            self.assertTrue(mounts[0]["target"].endswith(".crt"), name)

    def test_mount_path_follows_env(self):
        over = overlay(self, {"LBX_CA_CERT_FILE": "/opt/ca/other.crt"})
        for name in TRUSTED:
            self.assertEqual("/opt/ca/other.crt", ca_mounts(over, name)[0]["source"], name)

    def test_adds_to_default_trust_store_via_temurin_entrypoint(self):
        over = overlay(self)
        for name in TRUSTED:
            svc = over["services"][name]
            self.assertEqual(ENTRYPOINT, svc["entrypoint"], name)
            self.assertEqual("1", str(svc["environment"]["USE_SYSTEM_CA_CERTS"]), name)

    def test_no_trust_store_replacement_and_no_trust_all(self):
        over = overlay(self)
        for name in TRUSTED:
            svc = over["services"][name]
            blob = repr(svc).lower()
            for banned in ("javax.net.ssl.truststore", "trustall", "trust-all", "insecure"):
                self.assertNotIn(banned, blob, name)

    def test_base_has_no_ca_settings(self):
        base = self.full_config()
        for name in TRUSTED:
            svc = base["services"][name]
            self.assertEqual([], ca_mounts(base, name), name)
            self.assertNotIn("USE_SYSTEM_CA_CERTS", svc.get("environment") or {}, name)
            self.assertNotIn("__cacert_entrypoint.sh", repr(svc.get("entrypoint")), name)


class ExtraCaOverlayPlantuml(ComposeConfigHarness):
    """AC3(要件3): media / platform の PLANTUML_BASE_URL を LBX_PLANTUML_URL で変えられる。"""

    def test_plantuml_url_follows_env(self):
        url = "https://plantuml.lbx.example:8443"
        over = overlay(self, {"LBX_PLANTUML_URL": url})
        for name in ("media", "platform"):
            self.assertEqual(url, over["services"][name]["environment"]["PLANTUML_BASE_URL"], name)

    def test_plantuml_url_default_keeps_base_value(self):
        base = self.full_config()
        over = overlay(self)
        for name in ("media", "platform"):
            self.assertEqual(
                base["services"][name]["environment"]["PLANTUML_BASE_URL"],
                over["services"][name]["environment"]["PLANTUML_BASE_URL"],
                name,
            )

    def test_ai_has_no_plantuml_setting_added(self):
        over = overlay(self)
        self.assertNotIn("PLANTUML_BASE_URL", over["services"]["ai"].get("environment") or {})


class ExtraCaDocs(unittest.TestCase):
    """AC4: 手順書があり、アーキテクチャ文書のオーバーレイ一覧から参照される。"""

    def read(self, rel):
        with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
            return f.read()

    def test_listed_in_architecture_doc(self):
        self.assertIn("docker-compose.extra-ca.yml", self.read("docs/DOCKER_COMPOSE_ARCHITECTURE.md"))

    def test_doc_covers_requirement_4(self):
        doc = self.read("docs/EXTENSIONS_CONNECTION.md")
        for needle in (
            "docker-compose.extra-ca.yml",
            "docker compose cp proxy:/etc/nginx/certs/tls.crt",
            "certs/lbx-ca.crt",
            "*.localhost",
            "extra_hosts",
            "llm_ollama_base_url",
            "comfyui_base_url",
            "publicUrl",
            "LBX_PLANTUML_URL",
            "期限",
        ):
            self.assertIn(needle, doc, needle)


if __name__ == "__main__":
    unittest.main()
