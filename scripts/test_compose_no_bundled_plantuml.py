#!/usr/bin/env python3
"""同梱の PlantUML コンテナが無く、extensions(または別途起動した PlantUML サーバー)へ接続する構成であることの検証(#1784)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

Web UI から到達できない compose の宣言なので、Gherkin ではなく契約テストで表す
(CLAUDE.md → Test-First Implementation)。`docker compose config` は静的で、イメージの取得は要らない。
読み出しの土台は test_compose_no_bundled_comfyui.py の ComposeConfigHarness を使う。

## 既定値の判断(Requirements 2)

media / platform の `PLANTUML_BASE_URL` の既定値は extensions の別名 `http://lbx-plantuml:8080` にする。
空にすると media の `application.yml` の既定 `http://localhost:8085` に落ち、コンテナ内の自分自身を
指すだけで原因が分かりにくい。別名なら extensions を同じ PC に置いた構成でそのまま届く。
"""

import os
import unittest

from test_compose_no_bundled_comfyui import (
    COMPOSE,
    REPO_ROOT,
    STUB_COMPOSE,
    ComposeConfigHarness,
)

EXTRA_CA_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.extra-ca.yml")
EXTENSIONS_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.extensions.yml")
REMOVED_URL = "http://plantuml:8080"
DEFAULT_URL = "http://lbx-plantuml:8080"
CONSUMERS = ("media", "platform")


class NoBundledPlantuml(ComposeConfigHarness):
    """AC1: `--profile diagram` でも plantuml サービスは無く、drawio は残る。"""

    def test_diagram_profile_has_no_plantuml_but_keeps_drawio(self):
        services = self.config_services(profiles=("diagram",))
        self.assertNotIn("plantuml", services)
        self.assertIn("drawio", services)

    def test_all_profiles_have_no_plantuml_service_or_container_name(self):
        config = self.full_config(profiles=("*",))
        self.assertNotIn("plantuml", config["services"])
        names = {s.get("container_name") for s in config["services"].values()}
        self.assertNotIn("lbs-plantuml", names)

    def test_nothing_depends_on_plantuml(self):
        config = self.full_config(profiles=("*",))
        for name, svc in config["services"].items():
            self.assertNotIn("plantuml", svc.get("depends_on") or {}, name)


class PlantumlBaseUrl(ComposeConfigHarness):
    """AC2: 既定値は削除したサービスを指さない。extensions を重ねると lbx-plantuml。"""

    def url(self, config, name):
        return config["services"][name]["environment"]["PLANTUML_BASE_URL"]

    def test_base_default_is_not_the_removed_service(self):
        config = self.full_config()
        for name in CONSUMERS:
            self.assertNotEqual(REMOVED_URL, self.url(config, name), name)
            self.assertEqual(DEFAULT_URL, self.url(config, name), name)

    def test_extra_ca_without_env_is_not_the_removed_service(self):
        config = self.full_config(compose_files=(COMPOSE, EXTRA_CA_COMPOSE))
        for name in CONSUMERS:
            self.assertNotEqual(REMOVED_URL, self.url(config, name), name)
            self.assertEqual(DEFAULT_URL, self.url(config, name), name)

    def test_extensions_overlay_keeps_lbx_plantuml(self):
        config = self.full_config(compose_files=(COMPOSE, EXTENSIONS_COMPOSE))
        for name in CONSUMERS:
            self.assertEqual(DEFAULT_URL, self.url(config, name), name)


FORBIDDEN_TOOL_IMAGES = (
    "plantuml/plantuml-server",
    "ollama/ollama",
    "comfyui",
    "penpotapp/",
    "gitlab/gitlab-ce",
)


class E2eStubsUseNoExtensionsToolImage(ComposeConfigHarness):
    """受け入れテストのスタックは extensions のツールの実物(イメージ)を起動しない(#1802)。

    規則: 受け入れテストは extensions のツール(Ollama・ComfyUI・PlantUML・ポータル・GitLab・Penpot)を
    すべてモックで受ける。実物は UAT で確かめる。`lbx-plantuml` は PlantUML のスタブが受ける。
    """

    FILES = (COMPOSE, STUB_COMPOSE)

    def test_no_service_in_the_e2e_stack_uses_an_extensions_tool_image(self):
        config = self.full_config(compose_files=self.FILES)
        offenders = []
        for name, svc in config["services"].items():
            image = str(svc.get("image", "")).lower()
            if any(tool in image for tool in FORBIDDEN_TOOL_IMAGES):
                offenders.append("%s: %s" % (name, image))
        self.assertEqual([], offenders)

    def test_the_overlay_file_itself_declares_no_extensions_tool_image(self):
        with open(STUB_COMPOSE, encoding="utf-8") as f:
            text = f.read().lower()
        for tool in FORBIDDEN_TOOL_IMAGES:
            self.assertNotIn("image: " + tool, text)

    def test_lbx_plantuml_alias_belongs_to_a_node_stub(self):
        config = self.full_config(compose_files=self.FILES)
        holders = [
            (n, s) for n, s in config["services"].items()
            if "lbx-plantuml" in ((s.get("networks") or {}).get("lbs-net") or {}).get("aliases", [])
        ]
        self.assertEqual(["plantuml-stub"], [n for n, _ in holders])
        svc = holders[0][1]
        self.assertIn("node", str(svc["image"]))
        self.assertEqual(["node", "/app/plantuml/server.js"], svc["command"])

    def test_the_stub_starts_without_extra_profiles(self):
        services = self.config_services(compose_files=self.FILES)
        self.assertIn("plantuml-stub", services)

    def test_media_and_platform_point_at_the_alias(self):
        config = self.full_config(compose_files=self.FILES)
        for name in CONSUMERS:
            self.assertEqual(DEFAULT_URL, config["services"][name]["environment"]["PLANTUML_BASE_URL"], name)

    def test_media_waits_for_the_stub_to_be_healthy(self):
        config = self.full_config(compose_files=self.FILES)
        depends = config["services"]["media"]["depends_on"]
        self.assertNotIn("e2e-plantuml", depends)
        self.assertEqual("service_healthy", depends["plantuml-stub"]["condition"])

    def test_production_compose_alone_has_no_plantuml_server_image(self):
        config = self.full_config(profiles=("*",))
        for name, svc in config["services"].items():
            self.assertNotIn("plantuml/plantuml-server", str(svc.get("image", "")), name)


class DocumentationDescribesExternalPlantuml(unittest.TestCase):
    def read(self, name):
        with open(os.path.join(REPO_ROOT, name), encoding="utf-8") as f:
            return f.read()

    def test_architecture_doc_no_longer_lists_a_bundled_plantuml(self):
        text = self.read("docs/DOCKER_COMPOSE_ARCHITECTURE.md")
        self.assertNotIn("| `plantuml` |", text)
        self.assertNotIn("PLANTUML_BASE_URL=http://plantuml:8080", text)

    def test_extensions_doc_states_the_default_and_no_old_fallback(self):
        text = self.read("docs/EXTENSIONS_CONNECTION.md")
        self.assertNotIn("従来の `http://plantuml:8080`", text)
        self.assertIn("#1784", text)

    def test_contracts_doc_says_plantuml_removed_first(self):
        self.assertIn("PlantUML は #1784 で先に削除", self.read("docs/plugins/CONTRACTS.md"))


if __name__ == "__main__":
    unittest.main()
