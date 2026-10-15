#!/usr/bin/env python3
"""lets_blog_server_extensions への共有ネットワーク接続オーバーレイの契約テスト(#1762)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない基準を
サービス/スクリプトレベルのテストで表現することを認めている。対象は `docker compose config` が
展開するネットワーク参加と環境変数で、Playwright からは観測できない。
"""

import os
import unittest

from test_compose_no_bundled_comfyui import COMPOSE, REPO_ROOT, ComposeConfigHarness

EXTENSIONS_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.extensions.yml")
JOINED = ("ai", "media", "platform", "publishing", "keycloak")
SHARED = "lbx-shared"


def service_networks(config, name):
    return set((config["services"][name].get("networks") or {}).keys())


class ExtensionsOverlayNetworks(ComposeConfigHarness):
    """AC1: ai / media / platform / publishing / keycloak(#1805)だけが外部ネットワークへ参加し、元のネットワークも保つ。"""

    def test_overlay_joins_only_four_services_and_keeps_existing_networks(self):
        base = self.full_config()
        over = self.full_config(compose_files=(COMPOSE, EXTENSIONS_COMPOSE))
        self.assertTrue(over["networks"][SHARED]["external"])
        for name in over["services"]:
            joined = SHARED in service_networks(over, name)
            self.assertEqual(name in JOINED, joined, name)
            if name in JOINED:
                self.assertTrue(service_networks(base, name) <= service_networks(over, name), name)
        self.assertIn("docker-socket-proxy-net", service_networks(over, "platform"))
        self.assertIn("lbs-net", service_networks(over, "platform"))
        self.assertEqual({"lbs-net", SHARED}, service_networks(over, "publishing"))

    def test_base_has_no_external_network(self):
        base = self.full_config()
        for net in (base.get("networks") or {}).values():
            self.assertFalse((net or {}).get("external"))
        for name in base["services"]:
            self.assertNotIn(SHARED, service_networks(base, name))


class ExtensionsOverlayKeycloak(ComposeConfigHarness):
    """#1805 AC1: keycloak は重ねると lbs-net と lbx-shared に別名つきで参加し、重ねないと lbs-net だけ。"""

    def test_overlay_joins_keycloak_to_both_networks_with_an_alias(self):
        over = self.full_config(compose_files=(COMPOSE, EXTENSIONS_COMPOSE))
        self.assertEqual({"lbs-net", SHARED}, service_networks(over, "keycloak"))
        aliases = over["services"]["keycloak"]["networks"][SHARED].get("aliases") or []
        self.assertTrue(aliases, "lbx-shared での別名が無い")
        self.assertIn("lbs-keycloak", aliases)

    def test_base_keeps_keycloak_on_lbs_net_only(self):
        base = self.full_config()
        self.assertEqual({"lbs-net"}, service_networks(base, "keycloak"))

    def test_overlay_passes_redirect_uri_and_client_secret_vars_to_keycloak(self):
        over = self.full_config(
            extra_env={"LBX_BASE_DOMAIN": "x.example", "LBX_HTTPS_PORT": "9443"},
            compose_files=(COMPOSE, EXTENSIONS_COMPOSE),
        )
        env = over["services"]["keycloak"]["environment"]
        self.assertEqual("x.example", env["LBX_BASE_DOMAIN"])
        self.assertEqual("9443", env["LBX_HTTPS_PORT"])
        for var in (
            "KEYCLOAK_GITLAB_CLIENT_SECRET",
            "KEYCLOAK_PENPOT_CLIENT_SECRET",
            "KEYCLOAK_PROXY_CLIENT_SECRET",
        ):
            self.assertIn(var, env)

    def test_base_does_not_pass_the_extensions_vars_to_keycloak(self):
        env = self.full_config()["services"]["keycloak"]["environment"]
        for var in env:
            self.assertFalse(var.startswith("LBX_"), var)
            self.assertNotIn(var, (
                "KEYCLOAK_GITLAB_CLIENT_SECRET",
                "KEYCLOAK_PENPOT_CLIENT_SECRET",
                "KEYCLOAK_PROXY_CLIENT_SECRET",
            ))


class ExtensionsOverlayEnv(ComposeConfigHarness):
    """AC2: PlantUML の接続先と、ネットワーク名の環境変数による変更。"""

    def test_plantuml_base_url(self):
        over = self.full_config(compose_files=(COMPOSE, EXTENSIONS_COMPOSE))
        for name in ("media", "platform"):
            self.assertEqual(
                "http://lbx-plantuml:8080",
                over["services"][name]["environment"]["PLANTUML_BASE_URL"],
                name,
            )

    def test_network_name_follows_env(self):
        over = self.full_config(
            extra_env={"LBX_SHARED_NETWORK": "my-shared"},
            compose_files=(COMPOSE, EXTENSIONS_COMPOSE),
        )
        nets = [n.get("name", k) for k, n in over["networks"].items() if n.get("external")]
        self.assertEqual(["my-shared"], nets)


if __name__ == "__main__":
    unittest.main()
