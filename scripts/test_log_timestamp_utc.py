#!/usr/bin/env python3
"""自前ログの時刻が UTC の ISO-8601 で出る設定になっていることを検証する(#1258)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

受け入れシナリオ(Gherkin)は `docker logs` や nginx のアクセスログを観測できない
(apps/web/e2e は Web UI 越しにしか振る舞いを見ない)ため、設定ファイルの静的検査で表す。

- JVM 10サービス(#1729 で構造化ログに変更): 標準出力は logstash 形式の 1 行 1 JSON。
  時刻は JSON の `@timestamp`、ゾーンは compose の `TZ: UTC`。
- nginx: アクセスログ時刻は `$time_iso8601`(`$time_local` ではない)。
"""

import glob
import os
import re
import unittest

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ALL_SERVICES = [
    "ai", "analytics", "content", "identity", "log-writer",
    "media", "platform", "project", "publishing", "gateway",
]


def _config(service):
    """application.yml(platform / publishing は複数ドキュメント)を1つの dict に統合して返す。"""
    path = os.path.join(ROOT, "services", service, "src/main/resources/application.yml")
    with open(path, encoding="utf-8") as f:
        merged = {}
        for doc in yaml.safe_load_all(f):
            merged.update(doc or {})
        return merged


class JvmServiceLogTimestamp(unittest.TestCase):
    """#1729: 10サービスとも構造化ログ(logstash形式)。時刻の UTC 固定は compose の TZ=UTC と
    構造化ログの @timestamp(UTC の ISO-8601)で保つ。"""

    def test_all_ten_services_are_covered(self):
        on_disk = {
            p.split(os.sep)[-5]
            for p in glob.glob(os.path.join(ROOT, "services/*/src/main/resources/application.yml"))
        }
        self.assertEqual(set(ALL_SERVICES), on_disk)

    def test_all_services_use_structured_logstash_console(self):
        for svc in ALL_SERVICES:
            with self.subTest(service=svc):
                fmt = (((_config(svc).get("logging") or {}).get("structured") or {}).get("format") or {})
                self.assertEqual("logstash", fmt.get("console"))

    def test_no_service_keeps_a_plain_console_pattern(self):
        for svc in ALL_SERVICES:
            with self.subTest(service=svc):
                pattern = ((_config(svc).get("logging") or {}).get("pattern") or {})
                self.assertNotIn("console", pattern)

    def test_compose_pins_jvm_services_to_utc(self):
        with open(os.path.join(ROOT, "docker-compose.yml"), encoding="utf-8") as f:
            text = f.read()
        self.assertGreaterEqual(len(re.findall(r"^\s+TZ: UTC\s*$", text, re.M)), 10)


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
