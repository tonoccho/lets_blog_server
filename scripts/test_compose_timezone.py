#!/usr/bin/env python3
"""全サービスが TZ=UTC を明示していること、MySQL が default-time-zone を固定していることを検査する(#1257)。

    python3 -m unittest scripts.test_compose_timezone
    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

システム内部で使う時刻はすべてUTCという方針だが、各コンテナがUTCで動くのは多くがイメージ既定値による
偶然だった。ホストTZが変わったり、誰かがコンテナへ別の `TZ` を与えたりすると、DBへ書く値がUTCと
ローカル時刻で混在する。サービスを足すときの `TZ` 指定漏れをここで機械的に検出する。

## 検査対象

`docker compose config --format json`(アンカー・マージ・オーバーライド解決済み)。
`--profile '*'` で profile 付きも含め、追加の compose 3 本を重ねた場合も見る。
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


def without_utc(services):
    """`TZ=UTC` を持たないサービス名を返す。"""
    bad = []
    for name, svc in sorted(services.items()):
        env = svc.get("environment") or {}
        if isinstance(env, list):
            env = dict(item.split("=", 1) for item in env if "=" in item)
        if env.get("TZ") != "UTC":
            bad.append(name)
    return bad


def mysql_time_zone_fixed(services):
    command = services["mysql"].get("command") or []
    if isinstance(command, str):
        command = command.split()
    return "--default-time-zone=+00:00" in command


class ComposeTimezoneTest(unittest.TestCase):
    def test_base_services_have_utc(self):
        services = render([BASE])
        self.assertTrue(services)
        self.assertEqual([], without_utc(services))

    def test_stacked_services_have_utc(self):
        services = render([BASE] + OVERLAYS)
        self.assertEqual([], without_utc(services))

    def test_stub_services_are_covered(self):
        self.assertIn("llm-stub", render([BASE] + OVERLAYS))

    def test_mysql_default_time_zone_is_fixed(self):
        for files in ([BASE], [BASE] + OVERLAYS):
            self.assertTrue(mysql_time_zone_fixed(render(files)), files)

    def test_without_utc_detects_missing_and_wrong_tz(self):
        services = {
            "ok": {"environment": {"TZ": "UTC"}},
            "ok-list": {"environment": ["TZ=UTC", "A=b"]},
            "none": {},
            "noenv": {"environment": {"A": "b"}},
            "auckland": {"environment": {"TZ": "Pacific/Auckland"}},
        }
        self.assertEqual(["auckland", "noenv", "none"], without_utc(services))

    def test_mysql_time_zone_fixed_accepts_string_and_list(self):
        self.assertTrue(mysql_time_zone_fixed({"mysql": {"command": "mysqld --default-time-zone=+00:00"}}))
        self.assertTrue(mysql_time_zone_fixed({"mysql": {"command": ["--default-time-zone=+00:00"]}}))
        self.assertFalse(mysql_time_zone_fixed({"mysql": {"command": ["--foo"]}}))
        self.assertFalse(mysql_time_zone_fixed({"mysql": {}}))


if __name__ == "__main__":
    unittest.main()
