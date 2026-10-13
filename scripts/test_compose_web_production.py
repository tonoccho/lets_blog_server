#!/usr/bin/env python3
"""lbs-web が既定で本番ビルド(next start)で動き、next dev はオプトインであることを検査する(#1743)。

    python3 -m unittest scripts.test_compose_web_production

## なぜ Gherkin ではないのか

対象は compose / Dockerfile の宣言(どのプロセスで、何をマウントして起動するか)であり、
Web UI からは観測できない。`CLAUDE.md` → Test-First Implementation が認める、サービス/
スクリプトレベルのテストで表現する例外として、`docker compose config` の描画結果を検査する。
稼働中のプロセスは実機の `docker exec lbs-web ps` で別途確かめる。

## 何を守るか

- 既定(オーバーレイ無し)の `web` は `next dev` を起動せず、ソースをバインドマウントしない
  (ホストの `.next` と衝突しない)。
- イメージのビルド時に `next build` を済ませる。
- `next dev` + ホットリロードは `docker-compose.dev-web.yml` を重ねたときだけ。
- `working_dir: /`(#1000)は本番モードでも、開発オーバーレイでも保つ。
- 鮮度確認(#1686)のソースパス表は、ビルドに焼き込まれる `src` 等を含む。
"""

import importlib.util
import json
import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

BASE = "docker-compose.yml"
DEV_OVERLAY = "docker-compose.dev-web.yml"


def render_web(files):
    args = ["docker", "compose"]
    for f in files:
        args += ["-f", f]
    args += ["config", "--format", "json"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)["services"]["web"]


def read(path):
    with open(os.path.join(REPO_ROOT, path), encoding="utf-8") as f:
        return f.read()


def command_text(web):
    cmd = web.get("command") or []
    return " ".join(cmd) if isinstance(cmd, list) else str(cmd)


class DefaultWebIsProduction(unittest.TestCase):
    def setUp(self):
        self.web = render_web([BASE])

    def test_command_does_not_run_next_dev(self):
        text = command_text(self.web)
        self.assertNotRegex(text, r"\bdev\b", text)
        self.assertRegex(text, r"\b(next start|npm (run )?start)\b", text)

    def test_no_source_bind_mount(self):
        binds = [v for v in self.web.get("volumes", []) if v.get("type") == "bind"]
        self.assertEqual(binds, [])

    def test_working_dir_stays_outside_app(self):
        self.assertEqual(self.web.get("working_dir"), "/")

    def test_dockerfile_builds_at_image_build_time_and_serves_production(self):
        text = read("apps/web/Dockerfile")
        self.assertRegex(text, r"(?m)^RUN\s+npm run build\b")
        self.assertNotRegex(text, r'(?m)^CMD\s+\["npm",\s*"run",\s*"dev"\]')

    def test_no_next_public_env_needed_at_build_time(self):
        # next build は NEXT_PUBLIC_* をバンドルへ焼き込む。compose の web に無いことを固定する。
        self.assertEqual([k for k in self.web.get("environment", {}) if k.startswith("NEXT_PUBLIC_")], [])


class DevOverlayKeepsHotReload(unittest.TestCase):
    def setUp(self):
        self.assertTrue(os.path.exists(os.path.join(REPO_ROOT, DEV_OVERLAY)), DEV_OVERLAY)
        self.web = render_web([BASE, DEV_OVERLAY])

    def test_runs_next_dev(self):
        self.assertRegex(command_text(self.web), r"\bnpm run dev\b")

    def test_bind_mounts_the_source(self):
        binds = [v for v in self.web.get("volumes", []) if v.get("type") == "bind"]
        self.assertEqual([b["target"] for b in binds], ["/app"])

    def test_working_dir_stays_outside_app(self):
        self.assertEqual(self.web.get("working_dir"), "/")


class FreshnessTableCoversBakedSource(unittest.TestCase):
    def test_source_paths_include_src_and_config(self):
        spec = importlib.util.spec_from_file_location(
            "check_image_freshness", os.path.join(HERE, "check-image-freshness.py")
        )
        cif = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cif)
        paths = cif.SERVICE_SOURCE_PATHS["web"]
        for p in ("apps/web/src", "apps/web/next.config.ts", "apps/web/public"):
            self.assertIn(p, paths)


if __name__ == "__main__":
    unittest.main()
