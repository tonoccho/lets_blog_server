#!/usr/bin/env python3
"""Penpot の基盤が compose・環境変数・nginx から消えていることの検証(#1752)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない基準を
サービス/スクリプトレベルのテストで表現することを認めている。対象は `docker-compose.yml` が
宣言するサービス・ボリューム・環境変数と `.env.example` / nginx 設定の記述で、Playwright からは
観測できない。`/penpot/` が中継されないことだけは受け入れシナリオ
(`apps/web/e2e/features/cross-cutting/reverse-proxy-upstreams.feature`)でも確かめる。

## 経緯

Penpot は #1748(メールキャッチャー)・#1749(プラグインと MCP サーバー)・#1750(アプリケーション
からの呼び出し)で周辺を外した後、本Issue(#1752)で基盤そのものを削除した。外部の Penpot への接続は #1753。
"""

import os
import re
import unittest

from test_compose_no_bundled_comfyui import (
    ComposeConfigHarness,
    OVERLAYS,
    REPO_ROOT,
)

REMOVED_SERVICES = (
    "penpot-frontend",
    "penpot-backend",
    "penpot-mcp",
    "penpot-exporter",
    "penpot-postgres",
    "penpot-valkey",
)
REMOVED_VOLUMES = ("penpot_postgres", "penpot_assets")


def read(rel):
    with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
        return f.read()


class NoPenpotInComposeConfig(ComposeConfigHarness):
    """AC1: `docker compose --profile '*' config` に Penpot が無い(オーバーレイの重ね掛けを含む)。"""

    def configs(self):
        return [
            (name, self.full_config(compose_files=files, profiles=("*",)))
            for name, files in OVERLAYS.items()
        ]

    def test_no_penpot_services(self):
        for overlay, config in self.configs():
            for name in REMOVED_SERVICES:
                self.assertNotIn(name, config["services"], overlay)
            offenders = [n for n in config["services"] if "penpot" in n]
            self.assertEqual([], offenders, overlay)

    def test_no_penpot_container_names(self):
        for overlay, config in self.configs():
            names = [s.get("container_name") or "" for s in config["services"].values()]
            self.assertEqual([], [n for n in names if "penpot" in n], overlay)

    def test_no_penpot_volumes(self):
        for overlay, config in self.configs():
            for name in REMOVED_VOLUMES:
                self.assertNotIn(name, config.get("volumes") or {}, overlay)

    def test_no_service_mounts_a_penpot_volume_or_path(self):
        for overlay, config in self.configs():
            for svc_name, svc in config["services"].items():
                for v in svc.get("volumes") or []:
                    text = "%s %s" % (v.get("source", ""), v.get("target", ""))
                    self.assertNotIn("penpot", text.lower(), "[%s] %s: %s" % (overlay, svc_name, v))

    def test_no_penpot_environment_variables(self):
        for overlay, config in self.configs():
            for svc_name, svc in config["services"].items():
                env = svc.get("environment") or {}
                keys = env.keys() if isinstance(env, dict) else [e.split("=")[0] for e in env]
                offenders = [k for k in keys if str(k).startswith("PENPOT_")]
                self.assertEqual([], offenders, "[%s] %s" % (overlay, svc_name))

    def test_nothing_depends_on_penpot(self):
        for overlay, config in self.configs():
            for svc_name, svc in config["services"].items():
                for dep in svc.get("depends_on") or {}:
                    self.assertNotIn("penpot", dep, "[%s] %s が %s に依存している" % (overlay, svc_name, dep))

    def test_default_up_has_no_penpot(self):
        for name in self.config_services():
            self.assertNotIn("penpot", name)


class NoPenpotInEnvExample(unittest.TestCase):
    def test_no_penpot_variables(self):
        offenders = [
            line for line in read(".env.example").splitlines()
            if re.match(r"^\s*#?\s*PENPOT_", line)
        ]
        self.assertEqual([], offenders)

    # #1805: extensions の Penpot を Keycloak の OIDC クライアントとして登録するための secret だけは例外。
    # 同梱 Penpot の秘密値ではない(同梱の Penpot は #1753 で撤去済みのまま)。
    OIDC_CLIENT_SECRET = "KEYCLOAK_PENPOT_CLIENT_SECRET"

    def test_no_penpot_secret_is_generated(self):
        text = read("scripts/lib/env-secrets.sh").replace(self.OIDC_CLIENT_SECRET, "")
        self.assertNotIn("PENPOT", text)

    def test_check_env_does_not_mention_penpot(self):
        text = read("scripts/check-env.sh").upper().replace(self.OIDC_CLIENT_SECRET, "")
        self.assertNotIn("PENPOT", text)


class NoPenpotInNginx(unittest.TestCase):
    def test_default_conf_has_no_penpot_location_or_upstream(self):
        self.assertNotIn("penpot", read("infra/nginx/conf.d/default.conf").lower())

    def test_infra_penpot_directory_is_gone(self):
        self.assertFalse(os.path.exists(os.path.join(REPO_ROOT, "infra", "penpot")))


class MigrationGuideIsDocumented(unittest.TestCase):
    """既存環境の移行手順(バックアップと手動削除)が文書にある。自動削除はしない。"""

    DOC = "docs/DOCKER_COMPOSE_ARCHITECTURE.md"

    def test_guide_covers_backup_and_manual_removal(self):
        text = read(self.DOC)
        for phrase in (
            "penpot_postgres",
            "penpot_assets",
            "docker compose down --remove-orphans",
            "docker volume rm",
            "tar",
            "#1753",
        ):
            self.assertIn(phrase, text)

    def test_nothing_removes_the_volumes_automatically(self):
        for rel in ("scripts/rebuild-acceptance-env.sh", "scripts/update.sh", "scripts/setup.sh"):
            path = os.path.join(REPO_ROOT, rel)
            if os.path.exists(path):
                self.assertNotIn("penpot", read(rel).lower(), rel)


if __name__ == "__main__":
    unittest.main()
