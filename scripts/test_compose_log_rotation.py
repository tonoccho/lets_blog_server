#!/usr/bin/env python3
"""全サービスの json-file ログにサイズ上限があることを検査する(#1247)。

    python3 -m unittest scripts.test_compose_log_rotation
    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

Docker の既定 json-file ドライバはサイズ無制限でログを書く。2026-09-10 に log-writer 1 コンテナの
ログが約 352GB に達してホストのディスクが埋まりかけた。1 サービスの暴走をホスト全体の停止に
しないため、compose が描く全サービスに max-size / max-file を要求する。

## 検査対象

`docker compose config --format json` の出力(アンカー・マージ・オーバーライドを解決済み)。
`--profile '*'` で profile 付き(ollama など)も含める。追加の compose 3 本は本体に重ねて描く。
"""

import json
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

BASE = "docker-compose.yml"
OVERLAYS = [
    "docker-compose.e2e-stubs.yml",
    "docker-compose.host-tests.yml",
    "docker-compose.shared-host.yml",
]


def render(files):
    args = ["docker", "compose"]
    for f in files:
        args += ["-f", f]
    args += ["--profile", "*", "config", "--format", "json"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)["services"]


def missing_limits(services):
    """ログ上限が無い(または json-file でない)サービス名を返す。"""
    bad = []
    for name, svc in sorted(services.items()):
        logging = svc.get("logging") or {}
        opts = logging.get("options") or {}
        if logging.get("driver") != "json-file" or "max-size" not in opts or "max-file" not in opts:
            bad.append(name)
    return bad


class ComposeLogRotationTest(unittest.TestCase):
    def test_base_services_have_log_limits(self):
        services = render([BASE])
        self.assertTrue(services)
        self.assertEqual([], missing_limits(services))

    def test_stacked_services_have_log_limits(self):
        services = render([BASE] + OVERLAYS)
        self.assertEqual([], missing_limits(services))

    def test_stub_services_are_covered(self):
        services = render([BASE] + OVERLAYS)
        self.assertIn("llm-stub", services)

    def test_missing_limits_detects_absent_and_wrong_driver(self):
        ok = {"driver": "json-file", "options": {"max-size": "50m", "max-file": "5"}}
        services = {
            "ok": {"logging": ok},
            "none": {},
            "local": {"logging": {"driver": "local", "options": ok["options"]}},
            "nosize": {"logging": {"driver": "json-file", "options": {"max-file": "5"}}},
            "nofile": {"logging": {"driver": "json-file", "options": {"max-size": "50m"}}},
        }
        self.assertEqual(["local", "nofile", "none", "nosize"], missing_limits(services))


if __name__ == "__main__":
    unittest.main()
