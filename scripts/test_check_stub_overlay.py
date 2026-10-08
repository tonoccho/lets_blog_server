#!/usr/bin/env python3
"""`scripts/check-stub-overlay.py` の単体テスト(#1683)。

## なぜ Gherkin ではないのか

対象は「稼働中コンテナが `docker-compose.e2e-stubs.yml` を重ねて作られたか」という、Web UI から
観測できない共有 docker スタックの状態である。`scripts/test_check_image_freshness.py`(#1653)と
同じ文書化された例外として、`docker` を呼ぶ `_docker` を差し替えて判定ロジックだけを検証する。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import importlib.util
import os
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-stub-overlay.py")

_spec = importlib.util.spec_from_file_location("check_stub_overlay", SCRIPT)
cso = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cso)

WITH = "docker-compose.yml,docker-compose.e2e-stubs.yml,docker-compose.shared-host.yml"
WITHOUT = "docker-compose.yml,docker-compose.shared-host.yml"


def fake_docker(rows, code=0, err=""):
    """`docker ps --format` の出力(`サービス<TAB>config_files` の行)を返す差し替え。"""
    out = "".join("%s\t%s\n" % r for r in rows)
    return lambda args: (code, out, err)


class FindMissing(unittest.TestCase):
    def test_lists_services_without_overlay(self):
        with mock.patch.object(
            cso, "_docker", fake_docker([("ai", WITH), ("media", WITHOUT), ("web", WITH)])
        ):
            self.assertEqual(cso.find_missing(), ["media"])

    def test_absolute_paths_in_label_are_matched_by_file_name(self):
        # 実機のラベルは絶対パス(`/home/.../docker-compose.e2e-stubs.yml`)で並ぶ。
        root = "/home/u/lets_blog_server/"
        with_abs = ",".join(root + f for f in WITH.split(","))
        without_abs = ",".join(root + f for f in WITHOUT.split(","))
        with mock.patch.object(
            cso, "_docker", fake_docker([("ai", with_abs), ("media", without_abs)])
        ):
            self.assertEqual(cso.find_missing(), ["media"])

    def test_similarly_named_file_is_not_the_overlay(self):
        with mock.patch.object(
            cso, "_docker", fake_docker([("ai", "/x/not-docker-compose.e2e-stubs.yml")])
        ):
            self.assertEqual(cso.find_missing(), ["ai"])

    def test_empty_when_all_have_overlay(self):
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITH), ("web", WITH)])):
            self.assertEqual(cso.find_missing(), [])

    def test_blank_lines_are_ignored_and_empty_label_counts_as_missing(self):
        out = "\nai\t\n"
        with mock.patch.object(cso, "_docker", lambda args: (0, out, "")):
            self.assertEqual(cso.find_missing(), ["ai"])

    def test_docker_failure_raises_unavailable(self):
        with mock.patch.object(cso, "_docker", fake_docker([], code=1, err="no daemon")):
            with self.assertRaises(cso.DockerUnavailable):
                cso.find_missing()


class Check(unittest.TestCase):
    def setUp(self):
        self.env = mock.patch.dict(os.environ, {}, clear=False)
        self.env.start()
        os.environ.pop(cso.BYPASS_ENV, None)

    def tearDown(self):
        self.env.stop()

    def test_missing_overlay_fails_with_service_and_rebuild_command(self):
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITHOUT), ("web", WITH)])):
            ok, msg = cso.check()
        self.assertFalse(ok)
        self.assertIn("ai", msg)
        self.assertIn("docker-compose.e2e-stubs.yml", msg)
        self.assertIn("docker compose", msg)
        self.assertIn(cso.BYPASS_ENV, msg)

    def test_all_with_overlay_passes(self):
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITH)])):
            ok, _msg = cso.check()
        self.assertTrue(ok)

    def test_bypass_records_services_and_passes(self):
        os.environ[cso.BYPASS_ENV] = "1"
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITHOUT)])):
            ok, msg = cso.check()
        self.assertTrue(ok)
        self.assertIn("ai", msg)
        self.assertIn("迂回", msg)

    def test_bypass_with_nothing_missing_notes_it(self):
        os.environ[cso.BYPASS_ENV] = "1"
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITH)])):
            ok, msg = cso.check()
        self.assertTrue(ok)
        self.assertIn(cso.BYPASS_ENV, msg)

    def test_docker_unavailable_skips(self):
        with mock.patch.object(cso, "_docker", fake_docker([], code=1, err="no daemon")):
            ok, msg = cso.check()
        self.assertTrue(ok)
        self.assertIn("省略", msg)


class Main(unittest.TestCase):
    def test_exit_codes(self):
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITHOUT)])):
            with mock.patch.dict(os.environ, {}, clear=False):
                os.environ.pop(cso.BYPASS_ENV, None)
                self.assertEqual(cso.main(["x"]), 1)
        with mock.patch.object(cso, "_docker", fake_docker([("ai", WITH)])):
            self.assertEqual(cso.main(["x"]), 0)
        self.assertEqual(cso.main(["x", "y"]), 2)


if __name__ == "__main__":
    unittest.main()
