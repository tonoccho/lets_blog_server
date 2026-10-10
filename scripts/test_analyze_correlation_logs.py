#!/usr/bin/env python3
"""scripts/analyze_correlation_logs.py の単体テスト(#1729)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

`docker compose logs` の出力(平文と JSON の混在)から、処理 ID(相関 ID)の行を集めて
(1) 時系列 (2) 区間ごとの所要時間 (3) 同じ呼び出し先・操作の回数(重複) (4) WARN/ERROR を出す。
"""

import json
import os
import subprocess
import sys
import unittest

import analyze_correlation_logs as acl

HERE = os.path.dirname(os.path.abspath(__file__))
CID = "op-123-abc"


def jline(prefix, **fields):
    base = {"@timestamp": "2026-10-10T01:00:00.000Z", "@version": "1", "level": "INFO",
            "logger_name": "com.letsblog.x.Y", "thread_name": "t", "service": "lets-blog-ai",
            "message": "hello", "correlationId": CID}
    base.update(fields)
    return "%s| %s" % (prefix, json.dumps(base, ensure_ascii=False))


def plain(prefix, ts, level, cid, msg):
    return "%s| %s [http-nio-8080-exec-1] %-5s c.l.c.web.RequestDurationLoggingFilter [%s] - %s" % (
        prefix, ts, level, cid, msg)


SYNC = "sync call: target=ai-service url=http://ai:8080 operation=POST /api/internal/gen duration_ms=%d attempts=1 retried=false outcome=success"
REQ = "service request: method=%s path=%s status=%d duration_ms=%d correlation_id=" + CID


class ParseLine(unittest.TestCase):
    def test_json_line_with_compose_prefix(self):
        rec = acl.parse_line(jline("ai-1  ", message="x"))
        self.assertEqual("ai", rec["service"])
        self.assertEqual("INFO", rec["level"])
        self.assertEqual(CID, rec["correlation_id"])
        self.assertEqual("x", rec["message"])
        self.assertEqual("2026-10-10T01:00:00.000Z", rec["timestamp"])

    def test_json_line_without_prefix(self):
        rec = acl.parse_line(json.dumps({"@timestamp": "t", "level": "WARN", "message": "m"}))
        self.assertEqual("WARN", rec["level"])
        self.assertIsNone(rec["correlation_id"])

    def test_plain_line_takes_service_from_prefix_and_cid_from_brackets(self):
        rec = acl.parse_line(plain("content-1  ", "2026-10-10T01:00:01.000Z", "WARN", CID, "slow"))
        self.assertEqual("content", rec["service"])
        self.assertEqual("WARN", rec["level"])
        self.assertEqual(CID, rec["correlation_id"])
        self.assertEqual("slow", rec["message"])

    def test_plain_gateway_line_takes_cid_from_message(self):
        line = ("gateway-1  | 2026-10-10T01:00:00.100+00:00  INFO 1 --- [gateway] [reactor-http-nio-2] "
                "c.l.g.config.CorrelationIdWebFilter : gateway request: method=GET path=/api/x status=200 "
                "duration_ms=12 correlation_id=" + CID)
        rec = acl.parse_line(line)
        self.assertEqual("gateway", rec["service"])
        self.assertEqual("INFO", rec["level"])
        self.assertEqual(CID, rec["correlation_id"])

    def test_non_log_line_is_kept_as_unparsed_text(self):
        rec = acl.parse_line("something else entirely")
        self.assertIsNone(rec["correlation_id"])

    def test_blank_line_is_none(self):
        self.assertIsNone(acl.parse_line("   "))

    def test_malformed_json_falls_back_to_plain(self):
        rec = acl.parse_line("ai-1  | {not json " + CID)
        self.assertEqual("ai", rec["service"])


class Collect(unittest.TestCase):
    def test_collects_only_lines_of_the_id_in_time_order_for_mixed_input(self):
        lines = [
            jline("ai-1  ", **{"@timestamp": "2026-10-10T01:00:03.000Z", "message": "third"}),
            plain("content-1  ", "2026-10-10T01:00:01.000Z", "INFO", CID, "first"),
            jline("ai-1  ", correlationId="other", message="unrelated"),
            plain("content-1  ", "2026-10-10T01:00:02.000Z", "INFO", "other", "unrelated2"),
            "garbage",
            jline("ai-1  ", **{"@timestamp": "2026-10-10T01:00:02.500Z", "message": "second"}),
        ]
        recs = acl.collect(lines, CID)
        self.assertEqual(["first", "second", "third"], [r["message"] for r in recs])

    def test_id_is_matched_exactly_not_as_substring(self):
        recs = acl.collect([jline("ai-1  ", correlationId=CID + "9", message="longer id")], CID)
        self.assertEqual([], recs)

    def test_matches_correlation_id_in_message_of_a_json_line_without_field(self):
        line = jline("gateway-1  ", correlationId=None, message="gateway request: path=/x correlation_id=" + CID)
        rec = json.loads(line.split("| ", 1)[1])
        del rec["correlationId"]
        line = "gateway-1  | " + json.dumps(rec)
        self.assertEqual(1, len(acl.collect([line], CID)))


class Durations(unittest.TestCase):
    def test_durations_per_service_and_segment(self):
        recs = acl.collect([
            plain("content-1  ", "2026-10-10T01:00:01.000Z", "INFO", CID, REQ % ("POST", "/api/posts", 200, 40)),
            jline("content-1  ", service="lets-blog-content", message=SYNC % 900),
            jline("content-1  ", service="lets-blog-content", message=SYNC % 100),
        ], CID)
        segs = acl.segments(recs)
        by = {(s["service"], s["segment"]): s for s in segs}
        self.assertEqual([40], by[("content", "POST /api/posts")]["durations_ms"])
        sync = by[("content", "sync call ai-service POST /api/internal/gen")]
        self.assertEqual([900, 100], sync["durations_ms"])

    def test_record_without_duration_is_not_a_segment(self):
        recs = acl.collect([jline("ai-1  ", message="no duration here")], CID)
        self.assertEqual([], acl.segments(recs))


class Duplicates(unittest.TestCase):
    def test_same_target_and_operation_twice_is_a_duplicate(self):
        recs = acl.collect([jline("c-1  ", message=SYNC % 10), jline("c-1  ", message=SYNC % 20)], CID)
        dups = acl.duplicates(recs)
        self.assertEqual(1, len(dups))
        self.assertEqual(2, dups[0]["count"])
        self.assertIn("ai-service", dups[0]["call"])
        self.assertIn("POST /api/internal/gen", dups[0]["call"])

    def test_different_operations_are_not_duplicates(self):
        other = SYNC.replace("/api/internal/gen", "/api/internal/other") % 5
        recs = acl.collect([jline("c-1  ", message=SYNC % 10), jline("c-1  ", message=other)], CID)
        self.assertEqual([], acl.duplicates(recs))

    def test_plain_and_json_lines_of_one_service_count_together(self):
        # 平文は compose の接頭辞 `content-1`、JSON は service 項目 `lets-blog-content`。同じサービスとして数える。
        plain_sync = plain("content-1  ", "2026-10-10T01:00:01.000Z", "INFO", CID, SYNC % 10)
        json_sync = jline("content-1  ", service="lets-blog-content", message=SYNC % 20)
        dups = acl.duplicates(acl.collect([plain_sync, json_sync], CID))
        self.assertEqual(1, len(dups))
        self.assertEqual("content", dups[0]["service"])

    def test_same_operation_from_different_services_counts_separately(self):
        recs = acl.collect([jline("a  ", service="a", message=SYNC % 1), jline("b  ", service="b", message=SYNC % 1)], CID)
        self.assertEqual([], acl.duplicates(recs))

    def test_counts_repeated_service_requests(self):
        msg = REQ % ("GET", "/api/items", 200, 5)
        recs = acl.collect([plain("x-1  ", "2026-10-10T01:00:0%d.000Z" % i, "INFO", CID, msg) for i in range(3)], CID)
        dups = acl.duplicates(recs)
        self.assertEqual(3, dups[0]["count"])


class Problems(unittest.TestCase):
    def test_warn_and_error_are_listed_info_is_not(self):
        recs = acl.collect([
            jline("a-1  ", level="INFO", message="fine"),
            jline("a-1  ", level="WARN", message="slow"),
            jline("a-1  ", level="ERROR", message="boom"),
            plain("b-1  ", "2026-10-10T01:00:09.000Z", "WARN", CID, "plain warn"),
        ], CID)
        self.assertEqual({"slow", "boom", "plain warn"}, {r["message"] for r in acl.problems(recs)})


class Report(unittest.TestCase):
    def test_report_has_all_four_sections(self):
        lines = [
            plain("content-1  ", "2026-10-10T01:00:01.000Z", "INFO", CID, REQ % ("POST", "/api/posts", 200, 40)),
            jline("content-1  ", message=SYNC % 900, level="WARN"),
            jline("content-1  ", message=SYNC % 100),
        ]
        out = acl.render(acl.collect(lines, CID), CID)
        for heading in ("== 1. 時系列", "== 2. 区間ごとの所要時間", "== 3. 重複した呼び出し", "== 4. WARN / ERROR"):
            self.assertIn(heading, out)
        self.assertIn("x2", out)
        self.assertIn("POST /api/posts", out)

    def test_report_for_unknown_id_says_nothing_found(self):
        out = acl.render([], "nope")
        self.assertIn("見つかりません", out)


class Cli(unittest.TestCase):
    def run_cli(self, args, stdin=None):
        return subprocess.run([sys.executable, "-I", os.path.join(HERE, "analyze_correlation_logs.py")] + args,
                              input=stdin, capture_output=True, text=True)

    def test_reads_stdin(self):
        p = self.run_cli([CID], stdin=jline("ai-1  ", message="from stdin") + "\n")
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertIn("from stdin", p.stdout)

    def test_reads_file_argument(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "logs.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(jline("ai-1  ", message="from file") + "\n")
            p = self.run_cli([CID, path])
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertIn("from file", p.stdout)

    def test_missing_file_is_an_error(self):
        p = self.run_cli([CID, "/nonexistent/logs.txt"])
        self.assertNotEqual(0, p.returncode)


if __name__ == "__main__":
    unittest.main()
