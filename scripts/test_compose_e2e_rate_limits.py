#!/usr/bin/env python3
"""受け入れテスト構成だけが gateway の api-internal / operation-log-endpoint の枠を引き上げることを検査する(#1704)。

    python3 -m unittest scripts.test_compose_e2e_rate_limits
    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

本番既定値は INTERNAL_API_RATE_LIMIT_REQUESTS=600 / OPERATION_LOG_RATE_LIMIT_REQUESTS=300(gateway の
application.yml)。受け入れテストは全ワーカーが e2e-admin 1 人に相乗りするため、既定値では尽きる。
値の根拠は docs/API_RATE_LIMITING.md「Acceptance-test override (issue #1704)」。
"""

import json
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

PRODUCTION_DEFAULTS = {"INTERNAL_API_RATE_LIMIT_REQUESTS": 600, "OPERATION_LOG_RATE_LIMIT_REQUESTS": 300}


def gateway_env(files):
    args = ["docker", "compose"]
    for f in files:
        args += ["-f", f]
    args += ["--profile", "*", "config", "--format", "json"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    env = json.loads(r.stdout)["services"]["gateway"].get("environment") or {}
    if isinstance(env, list):
        env = dict(item.split("=", 1) for item in env if "=" in item)
    return env


class E2eRateLimitOverride(unittest.TestCase):
    def test_overlay_raises_both_buckets_above_production_default(self):
        env = gateway_env(["docker-compose.yml", "docker-compose.e2e-stubs.yml"])
        for key, default in PRODUCTION_DEFAULTS.items():
            self.assertIn(key, env, key + " が受け入れテスト構成に無い")
            self.assertGreater(int(env[key]), default, key)

    def test_production_compose_leaves_defaults_untouched(self):
        env = gateway_env(["docker-compose.yml"])
        for key in PRODUCTION_DEFAULTS:
            self.assertNotIn(key, env, key + " が本番構成で上書きされている")


if __name__ == "__main__":
    unittest.main()
