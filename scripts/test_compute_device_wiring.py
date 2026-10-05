#!/usr/bin/env python3
"""ComfyUI 演算デバイス切り替え(#1399)の配線の固定。

    python3 -m unittest discover -s scripts -t scripts -p 'test_compute_device_wiring.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** が認める「Web UI から到達できない基準」のうち、
compose と運用手順の記述そのものの検査である(切り替えの振る舞いは
`features/platform/compute-device.feature` と platform の単体テストが検証する)。

## 何を固定するか

- 受け入れ環境(`docker-compose.e2e-stubs.yml`)が、platform の切り替え専用の向き先だけを
  Docker Engine API スタブへ向け、ダッシュボードの `DOCKER_SOCKET_PROXY_BASE_URL` は変えないこと。
  変えると既存のコンテナ一覧シナリオ(#803 / #725)が壊れる。
- スタブがホスト公開ポート 18089(`apps/web/e2e/support/dockerEngineStub.ts` と同じ)で起動すること。
- 待機側(CPU 構成)の作成手順が `.env.example` と `docs/DOCKER_COMPOSE_ARCHITECTURE.md` にあること。
  アプリはコンテナを作らないので、手順が書かれていないと切り替えが一生使えない。
- 公開ドキュメントが start / stop だけを開けたこと、実機確認の結果(または未確認であること)を
  記録していること。
"""

import os
import re
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))


def read(*parts):
    with open(os.path.join(REPO_ROOT, *parts), encoding="utf-8") as f:
        return f.read()


def compose(name):
    return yaml.safe_load(read(name))["services"]


def env_of(service):
    env = service.get("environment", {})
    if isinstance(env, list):
        env = dict(item.split("=", 1) for item in env)
    return {str(k): str(v) for k, v in env.items()}


CREATE_COMMAND = "docker compose --profile cpu create comfyui-cpu"


class AcceptanceStubWiring(unittest.TestCase):
    def setUp(self):
        self.services = compose("docker-compose.e2e-stubs.yml")

    def test_docker_engine_stub_is_published_on_18089(self):
        stub = self.services["docker-engine-stub"]
        self.assertIn("127.0.0.1:18089:8080", stub["ports"])
        self.assertIn("/app/docker-engine/server.js", stub["command"])

    def test_platform_switching_is_pointed_at_the_stub_only(self):
        env = env_of(self.services["platform"])
        self.assertEqual("http://docker-engine-stub:8080", env.get("COMPUTE_DEVICE_DOCKER_BASE_URL"))
        self.assertNotIn("DOCKER_SOCKET_PROXY_BASE_URL", env,
                         "ダッシュボードのコンテナ一覧は実物のproxyのまま(既存シナリオを壊さない)")

    def test_apply_timeout_is_shortened_for_acceptance(self):
        env = env_of(self.services["platform"])
        self.assertEqual("10", env.get("COMPUTE_DEVICE_APPLY_TIMEOUT_SECONDS"))

    def test_platform_waits_for_the_stub(self):
        depends = self.services["platform"]["depends_on"]
        self.assertEqual("service_healthy", depends["docker-engine-stub"]["condition"])

    def test_stub_uses_the_same_compose_project_label_as_platform(self):
        env = env_of(self.services["docker-engine-stub"])
        self.assertIn("COMPOSE_PROJECT_NAME", env)

    def test_e2e_support_uses_the_same_port(self):
        self.assertIn("http://127.0.0.1:18089", read("apps", "web", "e2e", "support", "dockerEngineStub.ts"))


class OperationalProcedure(unittest.TestCase):
    def test_env_example_documents_creating_the_standby_container(self):
        self.assertIn(CREATE_COMMAND, read(".env.example"))

    def test_architecture_doc_documents_the_procedure_and_the_permission_scope(self):
        doc = read("docs", "DOCKER_COMPOSE_ARCHITECTURE.md")
        self.assertIn(CREATE_COMMAND, doc)
        self.assertIn("ALLOW_START", doc)
        self.assertIn("ALLOW_STOP", doc)
        self.assertIn("ALLOW_RESTARTS", doc, "開けていない許可(kill を含む)もあわせて書く")

    def test_architecture_doc_records_the_live_proxy_verification_honestly(self):
        doc = read("docs", "DOCKER_COMPOSE_ARCHITECTURE.md")
        for endpoint in ("/containers/create", "/exec", "/images/create"):
            self.assertIn(endpoint, doc)
        self.assertRegex(doc, re.compile(r"403"))

    def test_architecture_doc_warns_about_manual_compose_up(self):
        doc = read("docs", "DOCKER_COMPOSE_ARCHITECTURE.md")
        self.assertRegex(doc, r"docker compose up -d.*両方|両方.*docker compose up -d")


if __name__ == "__main__":
    unittest.main()
