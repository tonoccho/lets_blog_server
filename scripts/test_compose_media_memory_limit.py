#!/usr/bin/env python3
"""media コンテナにメモリ上限と JVM ヒープ上限が明示されていることを検査する(#1112)。

    python3 -m unittest scripts.test_compose_media_memory_limit

同期 `POST /api/ai/image` は最大256枚の Base64 をレスポンス完了までオンヒープに保持する。
上限が無いとホストのメモリ量次第になるため、コンテナ上限と -Xmx を compose に明示する。
オーバーレイ(e2e-stubs / shared-host)を重ねても打ち消されないことも検査する。
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


def render_media(files):
    args = ["docker", "compose"]
    for f in files:
        args += ["-f", f]
    args += ["--profile", "*", "config", "--format", "json"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)["services"]["media"]


def container_limit_bytes(media):
    limit = media.get("mem_limit")
    if not limit:
        limit = (((media.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("memory")
    return int(limit) if limit else 0


def heap_max_bytes(media):
    opts = (media.get("environment") or {}).get("JAVA_TOOL_OPTIONS", "")
    m = re.search(r"-Xmx(\d+)([mMgG])", opts)
    if not m:
        return 0
    return int(m.group(1)) * (1024**2 if m.group(2).lower() == "m" else 1024**3)


class ComposeMediaMemoryLimitTest(unittest.TestCase):
    def check(self, files):
        media = render_media(files)
        limit = container_limit_bytes(media)
        heap = heap_max_bytes(media)
        self.assertGreater(limit, 0, "media にコンテナのメモリ上限が無い")
        self.assertGreater(heap, 0, "media の JAVA_TOOL_OPTIONS に -Xmx が無い")
        self.assertLess(heap, limit, "ヒープ上限がコンテナ上限以上")

    def test_base_has_limits(self):
        self.check([BASE])

    def test_stacked_keeps_limits(self):
        self.check([BASE] + OVERLAYS)

    def test_headroom_for_chromium_and_non_heap(self):
        media = render_media([BASE])
        headroom = container_limit_bytes(media) - heap_max_bytes(media)
        self.assertGreaterEqual(headroom, 768 * 1024**2, "非ヒープ+Chromium の余裕が 768MiB 未満")


if __name__ == "__main__":
    unittest.main()
