#!/usr/bin/env python3
"""`scripts/check-image-freshness.py` の単体テスト(#1653)。

## なぜ Gherkin ではないのか

対象は「稼働中コンテナのイメージ作成時刻が、ソースパスの最新コミットより古いか」という、
Web UI から観測できない共有 docker スタックの状態である。この開発環境には docker デーモンも
再ビルド済み/未済のスタックの対も無い。`scripts/test_check_worktree_match.py`(#1202)と同じ
文書化された例外として、`docker` / `git` を呼ぶ2関数(`_docker` / `_git`)を差し替えて判定
ロジックだけを検証する。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import importlib.util
import os
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "check-image-freshness.py")

_spec = importlib.util.spec_from_file_location("check_image_freshness", SCRIPT)
cif = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cif)

# 2026-10-06T00:00:00Z
T0 = 1791244800


def iso(epoch):
    import datetime

    return (
        datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%S"
        )
        + ".123456789Z"
    )


def fakes(image_created, commits, running=None, missing_image=()):
    """`_docker` / `_git` の代わりを返す。

    image_created: {service: epoch}。running に無い/ここに無いサービスは稼働していない扱い。
    commits: {service: (epoch, hash)} 。無いサービスはコミットが触れていない(git が空を返す)。
    """
    running = set(image_created) if running is None else set(running)
    paths_to_service = {tuple(v): k for k, v in cif.SERVICE_SOURCE_PATHS.items()}

    def _docker(args):
        if args[0] == "ps":
            svc = [a for a in args if a.startswith("label=com.docker.compose.service=")][0]
            svc = svc.split("=", 2)[2]
            return (0, "cid-%s\n" % svc, "") if svc in running else (0, "", "")
        if args[:2] == ["inspect", "--format"] and args[2] == "{{.Created}}":
            # コンテナ自身の作成時刻(イメージが既に消えている場合のフォールバック)
            return 0, iso(image_created[args[-1][4:]]) + "\n", ""
        if args[:2] == ["inspect", "--format"]:
            return 0, "sha256:img-%s\n" % args[-1][4:], ""
        if args[:3] == ["image", "inspect", "--format"]:
            svc = args[-1][len("sha256:img-"):]
            if svc in missing_image:
                return 1, "", "Error response from daemon: No such image: " + args[-1]
            return 0, iso(image_created[svc]) + "\n", ""
        raise AssertionError("unexpected docker args: %r" % (args,))

    def _git(args, cwd):
        assert args[:2] == ["log", "-1"], args
        paths = tuple(args[args.index("--") + 1:])
        svc = paths_to_service[paths]
        if svc not in commits:
            return 0, "", ""
        ts, h = commits[svc]
        return 0, "%d %s\n" % (ts, h), ""

    return _docker, _git


class SourcePathTable(unittest.TestCase):
    def test_table_covers_every_compose_build_service(self):
        """compose に build: を持つサービスが増えて対応表に無ければ失敗する(AC5)。"""
        services = cif.compose_build_services(os.path.join(REPO_ROOT, "docker-compose.yml"))
        self.assertTrue(services)
        missing = sorted(set(services) - set(cif.SERVICE_SOURCE_PATHS))
        self.assertEqual(missing, [], "対応表に無い build: サービス: %s" % missing)
        extra = sorted(set(cif.SERVICE_SOURCE_PATHS) - set(services))
        self.assertEqual(extra, [], "compose に build: が無い対応表の項目: %s" % extra)

    def test_expected_services_present(self):
        for s in ("web media ai content analytics platform project publishing "
                  "log-writer gateway identity wordpress").split():
            self.assertIn(s, cif.SERVICE_SOURCE_PATHS)

    def test_compose_parser_detects_new_build_service(self):
        import tempfile

        text = "services:\n  a:\n    build:\n      context: .\n  b:\n    image: x\n  c:\n    build: .\nvolumes:\n  v:\n"
        with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False) as f:
            f.write(text)
        try:
            self.assertEqual(cif.compose_build_services(f.name), ["a", "c"])
        finally:
            os.unlink(f.name)

    def test_web_excludes_bind_mounted_paths(self):
        for p in cif.SERVICE_SOURCE_PATHS["web"]:
            self.assertFalse(p.startswith("apps/web/src"), p)
        self.assertIn("apps/web/package.json", cif.SERVICE_SOURCE_PATHS["web"])

    def test_java_services_include_services_and_shared_roots(self):
        paths = cif.SERVICE_SOURCE_PATHS["media"]
        for p in ("services/media", "packages", "build.gradle", "settings.gradle",
                  "gradle", "config", "services/gateway/build.gradle"):
            self.assertIn(p, paths)


class ParseCreated(unittest.TestCase):
    def test_parses_nanosecond_rfc3339(self):
        self.assertEqual(cif.parse_created(iso(T0)), T0)

    def test_parses_without_fraction(self):
        self.assertEqual(cif.parse_created("2026-10-06T00:00:00Z"), T0)

    def test_offset(self):
        self.assertEqual(cif.parse_created("2026-10-06T09:00:00.5+09:00"), T0)

    def test_garbage_returns_none(self):
        self.assertIsNone(cif.parse_created("not a date"))


class FindStale(unittest.TestCase):
    def run_find(self, created, commits, running=None, missing_image=()):
        d, g = fakes(created, commits, running, missing_image)
        with mock.patch.object(cif, "_docker", d), mock.patch.object(cif, "_git", g):
            return cif.find_stale(cwd=REPO_ROOT)

    def test_image_older_than_commit_is_stale(self):
        stale = self.run_find({"media": T0 - 100}, {"media": (T0, "abc1234")})
        self.assertEqual([s["service"] for s in stale], ["media"])
        self.assertEqual(stale[0]["commit"], "abc1234")
        self.assertEqual(stale[0]["commit_time"], T0)
        self.assertEqual(stale[0]["image_created"], T0 - 100)

    def test_image_newer_than_commit_is_fresh(self):
        self.assertEqual(self.run_find({"media": T0 + 1}, {"media": (T0, "abc")}), [])

    def test_equal_times_are_fresh(self):
        self.assertEqual(self.run_find({"media": T0}, {"media": (T0, "abc")}), [])

    def test_no_commit_touching_path_is_fresh(self):
        self.assertEqual(self.run_find({"media": T0}, {}), [])

    def test_not_running_service_is_skipped(self):
        self.assertEqual(
            self.run_find({"media": T0 - 100}, {"media": (T0, "abc")}, running=[]), []
        )

    def test_missing_image_falls_back_to_container_created_stale(self):
        """タグが付け替わってイメージが消えていても、コンテナ作成時刻で判定する。"""
        stale = self.run_find(
            {"media": T0 - 100}, {"media": (T0, "abc")}, missing_image=["media"]
        )
        self.assertEqual([s["service"] for s in stale], ["media"])

    def test_missing_image_falls_back_to_container_created_fresh(self):
        self.assertEqual(
            self.run_find({"media": T0 + 1}, {"media": (T0, "abc")}, missing_image=["media"]),
            [],
        )

    def test_multiple_stale_listed_in_table_order(self):
        stale = self.run_find(
            {"gateway": T0 - 1, "media": T0 - 1, "ai": T0 + 5},
            {"gateway": (T0, "g"), "media": (T0, "m"), "ai": (T0, "a")},
        )
        self.assertEqual(sorted(s["service"] for s in stale), ["gateway", "media"])


class Check(unittest.TestCase):
    def run_check(self, created, commits, env=None):
        d, g = fakes(created, commits)
        env = env or {}
        with mock.patch.object(cif, "_docker", d), mock.patch.object(
            cif, "_git", g
        ), mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop(cif.BYPASS_ENV, None)
            if env:
                os.environ.update(env)
            return cif.check(cwd=REPO_ROOT)

    def test_stale_aborts_with_names_times_hash_and_rebuild_command(self):
        ok, msg = self.run_check(
            {"media": T0 - 100, "ai": T0 + 100},
            {"media": (T0, "abc1234"), "ai": (T0, "def5678")},
        )
        self.assertFalse(ok)
        self.assertIn("media", msg)
        self.assertNotIn("def5678", msg)
        self.assertIn("abc1234", msg)
        self.assertIn(iso(T0 - 100)[:19], msg)
        self.assertIn("docker compose", msg)
        self.assertIn("up -d --build media", msg)
        self.assertIn(cif.BYPASS_ENV, msg)

    def test_rebuild_command_lists_all_stale_services(self):
        ok, msg = self.run_check(
            {"media": T0 - 1, "gateway": T0 - 1},
            {"media": (T0, "m"), "gateway": (T0, "g")},
        )
        self.assertFalse(ok)
        self.assertRegex(msg, r"up -d --build (media gateway|gateway media)")

    def test_all_fresh_passes(self):
        ok, msg = self.run_check({"media": T0 + 1}, {"media": (T0, "abc")})
        self.assertTrue(ok)

    def test_bypass_continues_and_logs_stale_and_bypass(self):
        ok, msg = self.run_check(
            {"media": T0 - 100}, {"media": (T0, "abc1234")}, env={cif.BYPASS_ENV: "1"}
        )
        self.assertTrue(ok)
        self.assertIn("media", msg)
        self.assertIn(cif.BYPASS_ENV, msg)

    def test_bypass_with_nothing_stale_still_notes_bypass(self):
        ok, msg = self.run_check({"media": T0 + 1}, {}, env={cif.BYPASS_ENV: "1"})
        self.assertTrue(ok)
        self.assertIn(cif.BYPASS_ENV, msg)

    def test_bypass_other_value_does_not_bypass(self):
        ok, _ = self.run_check(
            {"media": T0 - 100}, {"media": (T0, "a")}, env={cif.BYPASS_ENV: "0"}
        )
        self.assertFalse(ok)

    def test_docker_unavailable_skips(self):
        def _docker(args):
            return 1, "", "Cannot connect to the Docker daemon"

        with mock.patch.object(cif, "_docker", _docker), mock.patch.dict(os.environ, {}):
            os.environ.pop(cif.BYPASS_ENV, None)
            ok, msg = cif.check(cwd=REPO_ROOT)
        self.assertTrue(ok)
        self.assertIn("docker", msg)

    def test_git_failure_aborts_unsafely_unknown(self):
        d, _ = fakes({"media": T0}, {})

        def _git(args, cwd):
            return 128, "", "fatal: bad"

        with mock.patch.object(cif, "_docker", d), mock.patch.object(cif, "_git", _git), \
                mock.patch.dict(os.environ, {}):
            os.environ.pop(cif.BYPASS_ENV, None)
            ok, msg = cif.check(cwd=REPO_ROOT)
        self.assertFalse(ok)
        self.assertIn("git", msg)

    def test_unparseable_image_created_aborts(self):
        def _docker(args):
            if args[0] == "ps":
                return 0, "cid\n", ""
            if args[:2] == ["inspect", "--format"]:
                return 0, "sha256:x\n", ""
            return 0, "garbage\n", ""

        def _git(args, cwd):
            return 0, "%d abc\n" % T0, ""

        with mock.patch.object(cif, "_docker", _docker), mock.patch.object(cif, "_git", _git), \
                mock.patch.dict(os.environ, {}):
            os.environ.pop(cif.BYPASS_ENV, None)
            ok, msg = cif.check(cwd=REPO_ROOT)
        self.assertFalse(ok)


class Main(unittest.TestCase):
    def test_exit_codes(self):
        with mock.patch.object(cif, "check", return_value=(True, "ok")):
            self.assertEqual(cif.main(["x"]), 0)
        with mock.patch.object(cif, "check", return_value=(False, "bad")):
            self.assertEqual(cif.main(["x"]), 1)

    def test_usage(self):
        self.assertEqual(cif.main(["x", "extra"]), 2)


class GlobalSetupWiring(unittest.TestCase):
    """AC4: ゼロ構築(ACCEPTANCE_RESET=1)の後・テスト開始前にチェックが呼ばれる。

    global-setup.ts は Playwright の起動前フックで実機の docker を要するため、呼び出しの
    順序は原文の位置関係で検証する。
    """

    def setUp(self):
        with open(os.path.join(REPO_ROOT, "apps", "web", "e2e", "global-setup.ts")) as f:
            self.src = f.read()

    def test_check_is_invoked_after_zero_build_and_data_reset_before_health_wait(self):
        call = self.src.find("'scripts', 'check-image-freshness.py'")
        self.assertGreater(call, 0, "global-setup.ts が check-image-freshness.py を呼んでいない")
        rebuild = self.src.find("'scripts', 'rebuild-acceptance-env.sh'")
        reset = self.src.find("'scripts', 'reset-acceptance-env.sh'")
        health = self.src.find("waitForServicesHealthy(undefined")
        self.assertTrue(0 < rebuild < call)
        self.assertTrue(0 < reset < call)
        self.assertLess(call, health)

    def test_check_failure_throws(self):
        call = self.src.find("'scripts', 'check-image-freshness.py'")
        self.assertIn("throw new Error", self.src[call:call + 600])


class RealGitBoundary(unittest.TestCase):
    def test_git_wrapper_reports_failure_for_bad_cwd(self):
        code, _out, _err = cif._git(["log", "-1"], "/nonexistent-dir-1653")
        self.assertNotEqual(code, 0)

    def test_docker_wrapper_returns_failure_when_missing(self):
        with mock.patch.object(cif.subprocess, "run", side_effect=OSError("no docker")):
            code, _o, _e = cif._docker(["ps"])
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
