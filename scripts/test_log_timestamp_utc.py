#!/usr/bin/env python3
"""自前ログの時刻が UTC の ISO-8601 で出る設定になっていることを検証する(#1258)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

受け入れシナリオ(Gherkin)は `docker logs` や nginx のアクセスログを観測できない
(apps/web/e2e は Web UI 越しにしか振る舞いを見ない)ため、設定ファイルの静的検査で表す。

- JVM 10サービス: ログ時刻のゾーンをパターン側で UTC に固定する(JVM 既定ゾーン非依存)。
  logback の `%d{pattern, UTC}` が固定の仕組み。
- pattern サービス: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`(UTC なので Z は固定文字)で、
  相関ID `%X{correlationId}` などの既存フィールドを維持する。
- gateway: Spring Boot 既定パターンのまま、`logging.pattern.dateformat` にゾーン指定を含める。
- nginx: アクセスログ時刻は `$time_iso8601`(`$time_local` ではない)。
"""

import glob
import os
import re
import unittest

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PATTERN_SERVICES = [
    "ai", "analytics", "content", "identity", "log-writer",
    "media", "platform", "project", "publishing",
]
ALL_SERVICES = PATTERN_SERVICES + ["gateway"]

UTC_ISO_DATE = "%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z', UTC}"


def _logging(service):
    path = os.path.join(ROOT, "services", service, "src/main/resources/application.yml")
    with open(path, encoding="utf-8") as f:
        merged = {}
        for doc in yaml.safe_load_all(f):  # platform / publishing は複数ドキュメント
            merged.update(((doc or {}).get("logging") or {}).get("pattern") or {})
        return merged


class JvmServiceLogTimestamp(unittest.TestCase):
    def test_all_ten_services_are_covered(self):
        on_disk = {
            p.split(os.sep)[-5]
            for p in glob.glob(os.path.join(ROOT, "services/*/src/main/resources/application.yml"))
        }
        self.assertEqual(set(ALL_SERVICES), on_disk)

    def test_pattern_services_use_utc_iso8601_with_z(self):
        for svc in PATTERN_SERVICES:
            with self.subTest(service=svc):
                console = _logging(svc).get("console", "")
                self.assertTrue(console.startswith(UTC_ISO_DATE), console)

    def test_pattern_services_keep_correlation_id(self):
        for svc in PATTERN_SERVICES:
            with self.subTest(service=svc):
                self.assertIn("[%X{correlationId}]", _logging(svc).get("console", ""))

    def test_gateway_pins_dateformat_to_utc(self):
        fmt = _logging("gateway").get("dateformat", "")
        self.assertRegex(fmt, r"^yyyy-MM-dd'T'HH:mm:ss\.SSSXXX,\s*UTC$")


class NginxAccessLogTimestamp(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(ROOT, "infra/nginx/nginx.conf"), encoding="utf-8") as f:
            text = f.read()
        m = re.search(r"log_format\s+main\s+(.*?);", text, re.S)
        self.assertIsNotNone(m, "log_format main not found")
        self.log_format = m.group(1)

    def test_uses_time_iso8601(self):
        self.assertIn("$time_iso8601", self.log_format)

    def test_does_not_use_time_local(self):
        self.assertNotIn("$time_local", self.log_format)


if __name__ == "__main__":
    unittest.main()
