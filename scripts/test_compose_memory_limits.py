#!/usr/bin/env python3
"""全コンテナにメモリ上限があり、全 Spring Boot サービスにヒープ上限があることを検査する(#1739)。

    python3 -m unittest scripts.test_compose_memory_limits

上限が無いと JVM はホストメモリ基準(1サービス最大約 1/4)でヒープを決め、負荷時に膨らむ。
`docker-compose.yml` の全サービス(全 profiles)に `mem_limit` を要求するので、新しい
サービスに上限を付け忘れると落ちる。オーバーレイを重ねても上限が消えないことも検査する。
media 単独の余裕検査は test_compose_media_memory_limit.py(#1112)が持つ。
"""

import json
import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

BASE = "docker-compose.yml"
OVERLAYS = ["docker-compose.e2e-stubs.yml", "docker-compose.shared-host.yml"]

SPRING_SERVICES = [
    "gateway", "identity", "content", "media", "ai",
    "analytics", "project", "platform", "publishing", "log-writer",
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


def container_limit_bytes(svc):
    limit = svc.get("mem_limit")
    if not limit:
        limit = (((svc.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("memory")
    return int(limit) if limit else 0


def heap_max_bytes(svc):
    opts = (svc.get("environment") or {}).get("JAVA_TOOL_OPTIONS", "")
    m = re.search(r"-Xmx(\d+)([mMgG])", opts)
    if not m:
        return 0
    return int(m.group(1)) * (1024**2 if m.group(2).lower() == "m" else 1024**3)


class ComposeMemoryLimitsTest(unittest.TestCase):
    def check_heap(self, files):
        services = render(files)
        for name in SPRING_SERVICES:
            with self.subTest(service=name):
                svc = services[name]
                limit = container_limit_bytes(svc)
                heap = heap_max_bytes(svc)
                self.assertGreater(limit, 0, f"{name} にコンテナのメモリ上限が無い")
                self.assertGreater(heap, 0, f"{name} の JAVA_TOOL_OPTIONS に -Xmx が無い")
                self.assertLess(heap, limit, f"{name} のヒープ上限がコンテナ上限以上")

    def check_all_limited(self, files):
        # 対象は docker-compose.yml が定義するサービス。オーバーレイだけが足すスタブは対象外。
        base_names = set(render([BASE]))
        for name, svc in render(files).items():
            if name not in base_names:
                continue
            with self.subTest(service=name):
                self.assertGreater(container_limit_bytes(svc), 0, f"{name} に mem_limit が無い")

    def test_spring_heap_base(self):
        self.check_heap([BASE])

    def test_spring_heap_stacked(self):
        self.check_heap([BASE] + OVERLAYS)

    def test_every_service_has_mem_limit_base(self):
        self.check_all_limited([BASE])

    def test_every_service_has_mem_limit_stacked(self):
        self.check_all_limited([BASE] + OVERLAYS)


if __name__ == "__main__":
    unittest.main()
