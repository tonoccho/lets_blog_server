#!/usr/bin/env python3
"""`scripts/smoke_jre_image.py`(JVM 実行イメージ=JRE の起動スモークテスト)の単体テスト(#1108)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は受入基準を `apps/web/e2e/features/**` の
シナリオで表現することを求めるが、本Issueの対象は開発者向けの検証スクリプトで、
Web UI から到達する経路が無い。同節が認める「Web UI から到達できない基準は、その旨を
明示してサービス/スクリプトレベルのテストで表現する」に当たる。

ここで固定するのは Docker を要しない純粋部分(対象サービスの決定・コマンド組み立て・
ヘルス判定・後始末の範囲)。実イメージを使った検証(#1101 の再現を含む)は Issue コメントに
実行ログとして記録する。
"""

import importlib.util
import os
import tempfile
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "smoke_jre_image",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "smoke_jre_image.py"),
)
smoke = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(smoke)


def make_repo(services):
    root = tempfile.mkdtemp()
    for name, has_dockerfile in services.items():
        os.makedirs(os.path.join(root, "services", name))
        if has_dockerfile:
            open(os.path.join(root, "services", name, "Dockerfile"), "w").close()
    return root


class AvailableServicesTest(unittest.TestCase):
    def test_only_directories_with_a_dockerfile_count(self):
        root = make_repo({"media": True, "ai": True, "docs-only": False})
        self.assertEqual(smoke.available_services(root), ["ai", "media"])


class ServicesFromPathsTest(unittest.TestCase):
    ALL = ["ai", "media", "project"]

    def test_service_path_selects_that_service(self):
        paths = ["services/media/src/main/java/A.java", "services/ai/build.gradle"]
        self.assertEqual(smoke.services_from_paths(paths, self.ALL), ["ai", "media"])

    def test_shared_paths_select_every_service(self):
        for path in ["packages/common/src/A.java", "build.gradle", "gradle/wrapper/x.properties", "config/checkstyle.xml"]:
            self.assertEqual(smoke.services_from_paths([path], self.ALL), self.ALL, path)

    def test_unrelated_paths_select_nothing(self):
        self.assertEqual(smoke.services_from_paths(["apps/web/src/a.ts", "README.md"], self.ALL), [])

    def test_unknown_service_directory_is_ignored(self):
        self.assertEqual(smoke.services_from_paths(["services/nope/x"], self.ALL), [])


class ResolveTargetsTest(unittest.TestCase):
    ALL = ["ai", "media"]

    def test_explicit_names_are_used_as_given(self):
        self.assertEqual(smoke.resolve_targets(["media"], self.ALL, None), ["media"])

    def test_default_is_media_only(self):
        self.assertEqual(smoke.resolve_targets([], self.ALL, None), ["media"])

    def test_all_keyword_selects_every_service(self):
        self.assertEqual(smoke.resolve_targets(["all"], self.ALL, None), self.ALL)

    def test_unknown_name_is_an_error(self):
        with self.assertRaises(SystemExit):
            smoke.resolve_targets(["nope"], self.ALL, None)

    def test_changed_paths_narrow_the_targets(self):
        self.assertEqual(smoke.resolve_targets([], self.ALL, ["services/ai/x"]), ["ai"])

    def test_changed_with_no_matching_paths_selects_nothing(self):
        self.assertEqual(smoke.resolve_targets([], self.ALL, ["README.md"]), [])


class HealthTest(unittest.TestCase):
    def test_up(self):
        self.assertTrue(smoke.health_is_up('{"status":"UP"}'))

    def test_down_and_garbage(self):
        self.assertFalse(smoke.health_is_up('{"status":"DOWN"}'))
        self.assertFalse(smoke.health_is_up("not json"))
        self.assertFalse(smoke.health_is_up(""))
        self.assertFalse(smoke.health_is_up("[]"))


class DockerEnvTest(unittest.TestCase):
    def test_docker_config_is_defaulted_when_unset(self):
        env = smoke.docker_env({"HOME": "/home/x"})
        self.assertEqual(env["DOCKER_CONFIG"], "/home/x/.config/docker-cli")

    def test_existing_docker_config_is_kept(self):
        env = smoke.docker_env({"HOME": "/home/x", "DOCKER_CONFIG": "/custom"})
        self.assertEqual(env["DOCKER_CONFIG"], "/custom")


class CommandTest(unittest.TestCase):
    def test_every_container_and_network_carries_the_run_label(self):
        cmds = [
            smoke.network_create_cmd("r1"),
            smoke.mysql_run_cmd("r1", "pw"),
            smoke.rabbitmq_run_cmd("r1"),
            smoke.service_run_cmd("r1", "media", "img", "pw"),
        ]
        for cmd in cmds:
            self.assertIn(f"{smoke.LABEL}=r1", cmd)

    def test_no_command_publishes_host_ports_or_uses_fixed_names(self):
        for cmd in [smoke.mysql_run_cmd("r1", "pw"), smoke.rabbitmq_run_cmd("r1"), smoke.service_run_cmd("r1", "media", "img", "pw")]:
            self.assertNotIn("-p", cmd)
            self.assertNotIn("-P", cmd)
            self.assertTrue(any(a.startswith("lbs-smoke-r1-") for a in cmd))
            self.assertFalse(any(a == "lbs-media" for a in cmd))

    def test_cleanup_lists_only_this_runs_label(self):
        self.assertEqual(
            smoke.cleanup_filter("r1"), f"label={smoke.LABEL}=r1"
        )

    def test_image_tag_is_namespaced(self):
        self.assertEqual(smoke.image_tag("r1", "media"), "lbs-smoke-r1/media:smoke")


class OutputTailTest(unittest.TestCase):
    def test_keeps_only_the_last_lines(self):
        text = "\n".join(str(i) for i in range(100))
        self.assertEqual(smoke.output_tail(text, 3), "97\n98\n99")

    def test_short_and_empty_output_pass_through(self):
        self.assertEqual(smoke.output_tail("a\nb", 40), "a\nb")
        self.assertEqual(smoke.output_tail(None, 40), "")


if __name__ == "__main__":
    unittest.main()
