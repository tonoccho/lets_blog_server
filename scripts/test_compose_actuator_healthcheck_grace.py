#!/usr/bin/env python3
"""Java サービス共通ヘルスチェックの猶予が下限を下回らないことを検査する(#1757)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

猶予 = `start_period` + `interval` × `retries`。これを越えて起動が終わらないと
unhealthy と判定され、`depends_on: condition: service_healthy` の連鎖で後続サービスが
起動されず、ゼロ構築が失敗する(一度 unhealthy になると後から healthy になっても再試行されない)。

下限 170 秒の根拠: リリース検証ログで実測した最長の起動時間は約 75 秒
(content 70.3 秒 / process running 75.3 秒、2026-10-11)。ゼロ構築では全サービスの同時起動で
CPU を取り合うため、実測最長の 2 倍強を取る。
"""

import os
import re
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

MIN_GRACE_SECONDS = 170


def seconds(value):
    m = re.fullmatch(r"(\d+)(ms|s|m)?", str(value))
    n, unit = int(m.group(1)), m.group(2) or "s"
    return {"ms": n / 1000, "s": n, "m": n * 60}[unit]


def grace(healthcheck):
    return (seconds(healthcheck["start_period"])
            + seconds(healthcheck["interval"]) * int(healthcheck["retries"]))


class ActuatorHealthcheckGrace(unittest.TestCase):
    def test_grace_is_not_below_floor(self):
        with open(os.path.join(REPO_ROOT, "docker-compose.yml")) as f:
            services = yaml.safe_load(f)["services"]
        targets = {
            name: s["healthcheck"] for name, s in services.items()
            if "/actuator/health" in " ".join(map(str, s.get("healthcheck", {}).get("test", [])))
        }
        self.assertTrue(targets, "actuator ヘルスチェックを持つサービスが見つからない")
        for name, hc in targets.items():
            with self.subTest(service=name):
                self.assertGreaterEqual(grace(hc), MIN_GRACE_SECONDS)


if __name__ == "__main__":
    unittest.main()
