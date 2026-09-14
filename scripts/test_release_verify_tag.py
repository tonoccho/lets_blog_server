#!/usr/bin/env python3
"""develop の検証済みコミットを main へ直接マージし、semver タグを付けるスクリプトの検証(#1274)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

このスクリプトが対象にするのは、受け入れテストを走らせる**土台そのもの**
(隔離チェックアウト、`cycle.lock` との排他、`main` への直接 push、semver タグ)であり、
製品の画面には一切現れない。`CLAUDE.md` → Test-First Implementation が明示する例外
(`scripts/test_rebuild_acceptance_env.py`、`scripts/test_git_hooks_binding.py` と同じ)
に従い、`scripts/test_*.py` で表現する。

## どう検証するか

本物の origin / 本物の共有スタックには絶対に触れない(Issue の Out of Scope)。

  - origin はローカルの bare リポジトリで、実際の形(main は develop の祖先ではないが、
    main の木は develop の祖先の木と同一で、main に注釈付き `0.3.0` がある)を再現する。
  - 「メイン作業ツリー」もテスト専用の fixture clone を使う(本物のチェックアウトには触れない)。
  - docker / npm / gradle は `RELEASE_VERIFY_STEP_TABLE` / `RELEASE_VERIFY_HANDOFF_COMMAND`
    (本番では絶対に設定しないテスト専用の差し替え口)で、実行せずに済ませる。
  - `flock` は Python の `fcntl.flock` を直接使う(テスト側も同じ fd 方式でロックを保持できる)。
"""

import fcntl
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import importlib.util

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "release-verify-tag.py")

_spec = importlib.util.spec_from_file_location("release_verify_tag", SCRIPT)
rvt = None
if _spec and os.path.exists(SCRIPT):
    rvt = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(rvt)


def git(args, cwd, check=True, env=None):
    e = dict(os.environ)
    for k in list(e):
        if k.startswith("GIT_"):
            del e[k]
    if env:
        e.update(env)
    r = subprocess.run(
        ["git"] + args, cwd=cwd, capture_output=True, text=True, env=e, timeout=60
    )
    if check and r.returncode != 0:
        raise RuntimeError("git %s failed: %s\n%s" % (args, r.stdout, r.stderr))
    return r


def rev_parse(cwd, ref):
    return git(["rev-parse", ref], cwd=cwd).stdout.strip()


def tree_of(cwd, ref):
    return git(["rev-parse", ref + "^{tree}"], cwd=cwd).stdout.strip()


def write_file(path, content="x"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def commit(cwd, message, files=None, empty=False, env=None):
    if files:
        for rel, content in files.items():
            write_file(os.path.join(cwd, rel), content)
            git(["add", rel], cwd=cwd)
    args = ["commit", "-m", message]
    if empty:
        args.append("--allow-empty")
    git(args, cwd=cwd, env=env)
    return rev_parse(cwd, "HEAD")


class OriginFixture:
    """実際の origin の形を再現する bare リポジトリを作る。

    develop: R -> ... -> A(祖先) -> ... -> D(develop tip)
    main:    A -> M(空コミット。木は A と同一) -- develop の祖先ではない
    tag 0.3.0(注釈付き) は main 上(A)に付ける。
    """

    def __init__(self, tmp):
        self.tmp = tmp
        self.bare = os.path.join(tmp, "origin.git")
        git(["init", "--bare", "-b", "develop", self.bare], cwd=tmp)
        self.seed = os.path.join(tmp, "seed")
        git(["clone", self.bare, self.seed], cwd=tmp)
        git(["config", "user.email", "test@example.com"], cwd=self.seed)
        git(["config", "user.name", "Test"], cwd=self.seed)

        commit(self.seed, "root", {"README.md": "root"})
        commit(self.seed, "version fixture files (#1305)", self._version_fixture_files())
        self._add_git_hooks_fixture()
        self.a = commit(self.seed, "ancestor A", {"a.txt": "a"})
        git(["tag", "-a", "0.3.0", "-m", "release 0.3.0", self.a], cwd=self.seed)

        # develop はAの後に進む
        self.d = commit(self.seed, "develop tip D", {"d.txt": "d"})
        git(["push", "origin", "develop"], cwd=self.seed)
        git(["push", "origin", "0.3.0"], cwd=self.seed)

        # main は A から分岐し、空コミットを1つ積む(木は A と同一)
        git(["checkout", "-b", "main", self.a], cwd=self.seed)
        self.m = commit(self.seed, "main tip M (revert, tree==A)", empty=True)
        git(["push", "origin", "main"], cwd=self.seed)

        git(["checkout", "develop"], cwd=self.seed)

    def _version_fixture_files(self, version="0.1.0"):
        """#1305: `rvt.VERSION_LOCATIONS` の全ファイル・全箇所を、実物と同じ形(npmの
        package.json/lock の `version` と `packages[""].version`、build.gradle の
        `version = '...'`、server.js の health 応答の `version: '...'`)で fixture に置く。
        本物のリスト(`rvt.VERSION_LOCATIONS`)を直接使うので、対象が増減しても
        fixture が自動的に追従する。"""
        files = {}
        for rel, kind in rvt.VERSION_LOCATIONS:
            if kind == "npm_pkg":
                name = os.path.basename(os.path.dirname(rel))
                files[rel] = json.dumps({"name": name, "version": version}, indent=2) + "\n"
            elif kind == "npm_lock":
                name = os.path.basename(os.path.dirname(rel))
                files[rel] = json.dumps(
                    {
                        "name": name,
                        "version": version,
                        "lockfileVersion": 3,
                        "requires": True,
                        "packages": {"": {"name": name, "version": version}},
                    },
                    indent=2,
                ) + "\n"
            elif kind == "gradle":
                files[rel] = "subprojects {\n    version = '%s'\n}\n" % version
            elif kind == "server_js":
                files[rel] = (
                    "res.json({ status: 'ok', service: 'x', version: '%s' });\n" % version
                )
            else:
                raise AssertionError("未知の kind: %s" % kind)
        return files

    def _add_git_hooks_fixture(self):
        """#1298: 隔離チェックアウトでの `bash scripts/setup-git-hooks.sh` 実行を検証できるよう、
        本物の `scripts/setup-git-hooks.sh` と、実行ビット付きのダミー `pre-commit` を
        fixture リポジトリへコミットする(実物の origin には両方とも入っている)。

        `pre-commit` はダミー(`exit 0`)で十分: この Issue が検証したいのは
        「束縛されるかどうか」(`core.hooksPath`)であって、pre-commit の中身(#1039 が
        別途担保)ではない。ダミーなら、束縛後に隔離チェックアウト内で行われるマージ
        コミット(事前確認・本番)がフックの実ロジック(フェーズ分離チェック等)に
        巻き込まれてテストを不安定にすることもない。
        """
        setup_script_src = os.path.join(REPO_ROOT, "scripts", "setup-git-hooks.sh")
        setup_script_dst = os.path.join(self.seed, "scripts", "setup-git-hooks.sh")
        os.makedirs(os.path.dirname(setup_script_dst), exist_ok=True)
        shutil.copy(setup_script_src, setup_script_dst)

        pre_commit_dst = os.path.join(self.seed, "scripts", "git-hooks", "pre-commit")
        write_file(pre_commit_dst, "#!/bin/sh\nexit 0\n")
        os.chmod(pre_commit_dst, 0o755)

        git(["add", "scripts/setup-git-hooks.sh", "scripts/git-hooks/pre-commit"], cwd=self.seed)
        commit(self.seed, "add git-hooks fixture (#1298)")

    def add_direct_main_commit(self, message="unexpected direct commit to main"):
        """main の木をAからずらす(木不一致 fixture)。"""
        git(["checkout", "main"], cwd=self.seed)
        sha = commit(self.seed, message, {"unexpected.txt": "oops"})
        git(["push", "origin", "main"], cwd=self.seed)
        git(["checkout", "develop"], cwd=self.seed)
        return sha

    def tags(self):
        r = git(["tag", "--list"], cwd=self.bare)
        return [l for l in r.stdout.splitlines() if l.strip()]

    def main_sha(self):
        return git(["rev-parse", "refs/heads/main"], cwd=self.bare).stdout.strip()

    def develop_sha(self):
        return git(["rev-parse", "refs/heads/develop"], cwd=self.bare).stdout.strip()


class Harness(unittest.TestCase):
    maxDiff = None

    def setUp(self):
        self.assertTrue(os.path.exists(SCRIPT), "%s が無い" % SCRIPT)
        self.tmp = tempfile.mkdtemp(prefix="rvt-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.origin = OriginFixture(self.tmp)

        # テスト専用の「メイン作業ツリー」。本物のリポジトリには一切触れない。
        self.main_worktree = os.path.join(self.tmp, "main-worktree")
        git(["clone", self.origin.bare, self.main_worktree], cwd=self.tmp)
        git(["config", "user.email", "test@example.com"], cwd=self.main_worktree)
        git(["config", "user.name", "Test"], cwd=self.main_worktree)
        git(["checkout", "develop"], cwd=self.main_worktree)

        # 要件2: gitignore対象の入力(.env / certs/)。実際のメイン作業ツリーに
        # あるはずのものを再現する(git管理外なのでcloneには含まれない)。
        write_file(os.path.join(self.main_worktree, ".env"), "MYSQL_ROOT_PASSWORD=secret\n")
        write_file(
            os.path.join(self.main_worktree, "certs", "reverse-proxy.crt"), "dummy-cert"
        )

        # 要件2: 資格情報ファイル(~/.config/lets-blog-e2e.env)のテスト専用差し替え。
        self.cred_env_file = os.path.join(self.tmp, "lets-blog-e2e.env")
        write_file(self.cred_env_file, "E2E_SYNTHETIC_ACCOUNT_TOKEN=tok-123\n")

        self.state_dir = os.path.join(self.tmp, "state")
        os.makedirs(self.state_dir)
        self.log_dir_parent = os.path.join(self.tmp, "logs")
        self.checkout_parent = os.path.join(self.tmp, "checkouts")
        self.docker_log = os.path.join(self.tmp, "steps.log")

    # -- ステップ表 -------------------------------------------------------
    def write_step_table(self, steps):
        p = os.path.join(self.tmp, "steps.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(steps, f)
        return p

    def fake_step(self, name, rc=0, touches_stack=False, counts=None):
        """`self.docker_log` に呼び出しを記録するだけの、即座に終わる偽の手順。"""
        step = {
            "name": name,
            "argv": [
                sys.executable,
                "-c",
                "import sys,os; open(%r,'a').write(%r+chr(10)); sys.exit(%d)"
                % (self.docker_log, name, rc),
            ],
            "cwd": "",
            "touches_stack": touches_stack,
        }
        if counts is not None:
            counts_file = "counts-%s.json" % name
            step["counts_file"] = counts_file
            step["argv"] = [
                sys.executable,
                "-c",
                "import json,sys,os; open(%r,'a').write(%r+chr(10)); "
                "json.dump(%r, open(os.path.join(sys.argv[1], %r), 'w')); sys.exit(%d)"
                % (self.docker_log, name, counts, counts_file, rc),
                "%CHECKOUT%",
            ]
        return step

    def fake_step_checking_checkout(self, name, checks, rc=0):
        """`checks` は checkout_dir 相対パスのリスト。存在確認の結果を docker_log へ記録する
        (要件2: .env / certs のコピーが最初の実手順より前に終わっていることを確かめる)。"""
        code = (
            "import os,sys\n"
            "checkout = sys.argv[1]\n"
            "results = []\n"
            "for rel in %r:\n"
            "    results.append(rel + '=' + str(os.path.exists(os.path.join(checkout, rel))))\n"
            "open(%r, 'a').write(%r + ':' + ','.join(results) + chr(10))\n"
            "sys.exit(%d)\n"
        ) % (checks, self.docker_log, name, rc)
        return {
            "name": name,
            "argv": [sys.executable, "-c", code, "%CHECKOUT%"],
            "cwd": "",
            "touches_stack": False,
        }

    def fake_step_checking_env(self, name, env_var, rc=0):
        """環境変数 `env_var` の値を docker_log へ記録する(要件2: 資格情報の読み込み確認)。"""
        code = (
            "import os,sys\n"
            "open(%r, 'a').write(%r + ':' + os.environ.get(%r, '<absent>') + chr(10))\n"
            "sys.exit(%d)\n"
        ) % (self.docker_log, name, env_var, rc)
        return {
            "name": name,
            "argv": [sys.executable, "-c", code],
            "cwd": "",
            "touches_stack": False,
        }

    def fake_step_checking_hooks_path(self, name, rc=0):
        """隔離チェックアウト自身の `core.hooksPath` を docker_log へ記録する
        (#1298: 手順が始まる時点で束縛済みかを確かめるため)。"""
        code = (
            "import subprocess,sys\n"
            "checkout = sys.argv[1]\n"
            "r = subprocess.run(['git', 'config', '--get', 'core.hooksPath'], "
            "cwd=checkout, capture_output=True, text=True)\n"
            "value = r.stdout.strip() if r.returncode == 0 else '<unset>'\n"
            "open(%r, 'a').write(%r + ':' + value + chr(10))\n"
            "sys.exit(%d)\n"
        ) % (self.docker_log, name, rc)
        return {
            "name": name,
            "argv": [sys.executable, "-c", code, "%CHECKOUT%"],
            "cwd": "",
            "touches_stack": False,
        }

    def default_steps(self):
        return [
            self.fake_step("unit-tests"),
            self.fake_step(
                "acceptance-tests",
                touches_stack=True,
                counts={"passed": 5, "failed": 0, "skipped": 0, "did_not_run": 0},
            ),
        ]

    def write_handoff(self, rc=0):
        p = os.path.join(self.tmp, "handoff.json")
        handoff = {
            "argv": [
                sys.executable,
                "-c",
                "open(%r,'a').write('handoff' + chr(10)); import sys; sys.exit(%d)"
                % (self.docker_log, rc),
            ],
            "cwd": "",
        }
        with open(p, "w", encoding="utf-8") as f:
            json.dump(handoff, f)
        return p

    def run_script(self, *args, extra_env=None, timeout=90):
        env = dict(os.environ)
        env["RELEASE_VERIFY_MAIN_WORKTREE"] = self.main_worktree
        env["RELEASE_VERIFY_ORIGIN_URL"] = self.origin.bare
        env["RELEASE_VERIFY_STATE_DIR"] = self.state_dir
        env["RELEASE_VERIFY_LOG_DIR"] = self.log_dir_parent
        env["RELEASE_VERIFY_CHECKOUT_PARENT"] = self.checkout_parent
        env.setdefault("RELEASE_VERIFY_CRED_ENV_FILE", self.cred_env_file)
        env.setdefault("RELEASE_VERIFY_LOCK_POLL_INTERVAL", "0.2")
        env.setdefault("RELEASE_VERIFY_LOCK_TIMEOUT", "3")
        if extra_env:
            env.update(extra_env)
        r = subprocess.run(
            [sys.executable, SCRIPT] + list(args),
            capture_output=True,
            text=True,
            env=env,
            timeout=timeout,
        )
        return r

    def out(self, r):
        return r.stdout + r.stderr

    def calls(self):
        if not os.path.exists(self.docker_log):
            return []
        with open(self.docker_log, encoding="utf-8") as f:
            return [l.strip() for l in f if l.strip()]


class SemverBumpArithmetic(unittest.TestCase):
    """AC3: 次の番号の決め方。"""

    def test_default_bump_is_patch_when_omitted(self):
        self.assertEqual("0.4.1", rvt.compute_next_version(["0.4.0"]))

    def test_minor_bump_from_0_3_0_gives_0_4_0(self):
        self.assertEqual("0.4.0", rvt.compute_next_version(["0.3.0"], "minor"))

    def test_numeric_comparison_not_string_0_9_0_vs_0_10_0(self):
        self.assertEqual(
            "0.10.1", rvt.compute_next_version(["0.9.0", "0.10.0"], "patch")
        )

    def test_ignores_non_strict_semver_tags(self):
        tags = ["dev-20260913-1", "v1.0.0", "0.5.0-rc1", "0.4.0"]
        self.assertEqual("0.4.1", rvt.compute_next_version(tags, "patch"))

    def test_major_bump(self):
        self.assertEqual("1.0.0", rvt.compute_next_version(["0.9.5"], "major"))


class DevVersionArithmetic(unittest.TestCase):
    """#1305 要件4: X から次の開発版数 `X.Y.(Z+1)-DEVELOP` を決める。"""

    def test_bumps_patch_and_appends_develop_suffix(self):
        self.assertEqual("0.4.1-DEVELOP", rvt.compute_dev_version("0.4.0"))

    def test_uses_uppercase_develop_literally(self):
        self.assertIn("-DEVELOP", rvt.compute_dev_version("1.2.3"))
        self.assertNotIn("-develop", rvt.compute_dev_version("1.2.3"))


FIXTURE_CONTENT = {
    "npm_pkg": '{\n  "name": "x",\n  "version": "0.1.0"\n}\n',
    "npm_lock": (
        '{\n  "name": "x",\n  "version": "0.1.0",\n  "lockfileVersion": 3,\n'
        '  "requires": true,\n  "packages": {\n    "": {\n      "name": "x",\n'
        '      "version": "0.1.0"\n    },\n    "node_modules/other": {\n'
        '      "version": "2.0.0"\n    }\n  }\n}\n'
    ),
    "gradle": "subprojects {\n    version = '0.1.0'\n}\n",
    "server_js": "res.json({ status: 'ok', service: 'x', version: '0.1.0' });\n",
}


class SetVersionEverywhereWritesAllLocations(unittest.TestCase):
    """AC1: 版数設定の処理は Requirement 1 の全ファイル・全箇所を書き換え、それ以外には
    触れない。版数欄が見つからないファイルがあれば非0(例外)で失敗する。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="rvt-setver-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        for rel, kind in rvt.VERSION_LOCATIONS:
            path = os.path.join(self.tmp, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(FIXTURE_CONTENT[kind])
        git(["init", "-q", "-b", "main"], cwd=self.tmp)
        git(["config", "user.email", "t@t"], cwd=self.tmp)
        git(["config", "user.name", "t"], cwd=self.tmp)
        git(["add", "."], cwd=self.tmp)
        git(["commit", "-q", "-m", "seed"], cwd=self.tmp)

    def test_writes_version_to_every_location(self):
        rvt.set_version_everywhere(self.tmp, "9.8.7", io.StringIO())
        for rel, kind in rvt.VERSION_LOCATIONS:
            with open(os.path.join(self.tmp, rel), encoding="utf-8") as f:
                text = f.read()
            self.assertEqual("9.8.7", rvt.read_version(text, kind), rel)

    def test_npm_lock_other_packages_version_is_left_untouched(self):
        """`packages[""].version` 以外(依存パッケージ自身の version)は変えない。"""
        rvt.set_version_everywhere(self.tmp, "9.8.7", io.StringIO())
        lock_rel = next(rel for rel, kind in rvt.VERSION_LOCATIONS if kind == "npm_lock")
        with open(os.path.join(self.tmp, lock_rel), encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(
            "2.0.0", data["packages"]["node_modules/other"]["version"]
        )

    def test_git_diff_shows_only_version_files(self):
        rvt.set_version_everywhere(self.tmp, "9.8.7", io.StringIO())
        r = git(["diff", "--name-only"], cwd=self.tmp)
        changed = sorted(l for l in r.stdout.splitlines() if l.strip())
        expected = sorted(rel for rel, _ in rvt.VERSION_LOCATIONS)
        self.assertEqual(expected, changed)

    def test_missing_version_field_fails_loudly_not_silently(self):
        path = os.path.join(self.tmp, "apps/web/package.json")
        with open(path, "w", encoding="utf-8") as f:
            f.write('{\n  "name": "web"\n}\n')
        with self.assertRaises(rvt.VersionFieldNotFound):
            rvt.set_version_everywhere(self.tmp, "9.8.7", io.StringIO())


class FullSuccessProducesTheReleaseCommitDevCommitAndTag(Harness):
    """AC2: 成功経路で、origin の main・develop・タグが仕様どおりになる。"""

    def setUp(self):
        super().setUp()
        self.before_main = self.origin.main_sha()
        table = self.write_step_table(self.default_steps())
        handoff = self.write_handoff()
        self.r = self.run_script(
            "--bump",
            "minor",
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": handoff,
            },
        )

    def test_exits_zero(self):
        self.assertEqual(0, self.r.returncode, self.out(self.r))

    def _shas(self):
        v_sha = self.origin.develop_sha()
        r_sha = git(["rev-parse", v_sha + "^"], cwd=self.origin.bare).stdout.strip()
        p_sha = git(["rev-parse", v_sha + "^^"], cwd=self.origin.bare).stdout.strip()
        return p_sha, r_sha, v_sha

    def test_develop_tip_v_has_r_as_parent_and_p_as_grandparent(self):
        p_sha, r_sha, v_sha = self._shas()
        self.assertEqual(self.origin.d, p_sha, "P は事前の develop 先頭のはず")

    def _assert_all_versions_are(self, sha, expected):
        for rel, kind in rvt.VERSION_LOCATIONS:
            text = git(["show", "%s:%s" % (sha, rel)], cwd=self.origin.bare).stdout
            self.assertEqual(expected, rvt.read_version(text, kind), "%s @ %s" % (rel, sha))

    def test_r_has_version_x_everywhere(self):
        _, r_sha, _ = self._shas()
        self._assert_all_versions_are(r_sha, "0.4.0")

    def test_v_has_the_next_dev_version_everywhere(self):
        _, _, v_sha = self._shas()
        self._assert_all_versions_are(v_sha, "0.4.1-DEVELOP")

    def test_main_tip_is_merge_of_old_main_and_r(self):
        after = self.origin.main_sha()
        _, r_sha, _ = self._shas()
        parents = git(
            ["log", "-1", "--format=%P", after], cwd=self.origin.bare
        ).stdout.split()
        self.assertEqual([self.before_main, r_sha], parents)
        self.assertEqual(
            tree_of(self.origin.bare, after), tree_of(self.origin.bare, r_sha)
        )

    def test_tag_points_at_main_tip(self):
        after = self.origin.main_sha()
        pointed = git(
            ["rev-list", "-n", "1", "0.4.0"], cwd=self.origin.bare
        ).stdout.strip()
        self.assertEqual(after, pointed)

    def test_new_tag_is_annotated_with_the_right_message(self):
        tags = self.origin.tags()
        self.assertIn("0.4.0", tags, "0.3.0 からの --bump minor は 0.4.0 のはず: %s" % tags)
        obj_type = git(["cat-file", "-t", "0.4.0"], cwd=self.origin.bare).stdout.strip()
        self.assertEqual("tag", obj_type, "注釈付きタグになっていない")

    def test_tag_message_records_both_p_and_r(self):
        _, r_sha, _ = self._shas()
        msg = git(["tag", "-l", "-n99", "0.4.0"], cwd=self.origin.bare).stdout
        for expect in (self.origin.d, r_sha, "acceptance-tests"):
            with self.subTest(expect=expect):
                self.assertIn(expect, msg)

    def test_main_tree_has_no_develop_suffix_anywhere(self):
        after = self.origin.main_sha()
        for rel, kind in rvt.VERSION_LOCATIONS:
            text = git(["show", "%s:%s" % (after, rel)], cwd=self.origin.bare).stdout
            self.assertNotIn("-DEVELOP", text, rel)


class SingleFailedScenarioLeavesMainUnchanged(Harness):
    """AC2-a: 受け入れシナリオ1件の失敗 → main 不変、タグ無し、非0、理由表示。"""

    def test_reports_failure_and_touches_nothing(self):
        before_main = self.origin.main_sha()
        before_tags = self.origin.tags()
        steps = [
            self.fake_step("unit-tests"),
            self.fake_step("acceptance-tests", rc=1, touches_stack=True),
        ]
        table = self.write_step_table(steps)
        handoff = self.write_handoff()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": handoff,
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("acceptance-tests", out)
        self.assertEqual(before_main, self.origin.main_sha())
        self.assertEqual(before_tags, self.origin.tags())
        # 手順が共有スタックへ触れた(touches_stack)ので、引き渡し(handoff)は実行される。
        self.assertIn("handoff", self.calls())


class SkippedScenarioIsTreatedAsFailure(Harness):
    """AC2-b: skipped / did not run が1件でもゼロ許容に反する。"""

    def test_skip_count_fails_the_run(self):
        before_main = self.origin.main_sha()
        steps = [
            self.fake_step(
                "acceptance-tests",
                touches_stack=True,
                counts={"passed": 4, "failed": 0, "skipped": 1, "did_not_run": 0},
            )
        ]
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("skipped", out.lower() + out)
        self.assertEqual(before_main, self.origin.main_sha())


class TreeMismatchAbortsBeforeAnyStep(Harness):
    """AC2-c: main への直接コミットで木がずれていたら、手順を1つも始めずに失敗する。"""

    def test_aborts_before_running_any_step_stub(self):
        self.origin.add_direct_main_commit()
        before_main = self.origin.main_sha()
        steps = self.default_steps()
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertEqual([], self.calls(), "木不一致なのに手順のスタブが呼ばれた:\n" + out)
        self.assertEqual(before_main, self.origin.main_sha())
        self.assertEqual(["0.3.0"], self.origin.tags())


class TargetCommitMustBeTheCurrentDevelopTip(Harness):
    """AC5(要件6): P(指定コミット)が origin/develop の先頭でなければ、
    どの手順も始めずに拒否する。古いコミットを指定したリリースはできない。"""

    def test_stale_ancestor_is_rejected_without_any_step(self):
        table = self.write_step_table(self.default_steps())
        r = self.run_script(
            self.origin.a,  # develop 先頭 D の祖先であって、先頭そのものではない
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertEqual([], self.calls(), "P起点チェックに落ちたのに手順が呼ばれた:\n" + out)
        self.assertEqual(self.origin.m, self.origin.main_sha())
        self.assertEqual(self.origin.d, self.origin.develop_sha())

    def test_commit_unreachable_from_develop_is_rejected_without_any_step(self):
        unreachable = self.origin.add_direct_main_commit()
        table = self.write_step_table(self.default_steps())
        r = self.run_script(
            unreachable,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertEqual([], self.calls())


class DevelopAdvancingDuringVerificationAbortsThePushWithNoPartialState(Harness):
    """AC4: 検証中(手順の途中)に origin/develop が別プロセスで進んだ場合、
    push 全体を拒否し、main・develop・タグのいずれも実行前から変えない
    (develop 自体はレース側のコミットで進むが、それはスクリプトの外の変化であって、
    スクリプトが V を積んだ結果ではないことを確かめる)。"""

    def race_advance_step(self, name="race-advance-develop"):
        code = (
            "import subprocess, tempfile, os\n"
            "bare = %r\n"
            "tmp = tempfile.mkdtemp()\n"
            "subprocess.run(['git', 'clone', bare, tmp], check=True, capture_output=True)\n"
            "subprocess.run(['git', '-C', tmp, 'config', 'user.email', 'race@test'], check=True)\n"
            "subprocess.run(['git', '-C', tmp, 'config', 'user.name', 'race'], check=True)\n"
            "open(os.path.join(tmp, 'race.txt'), 'w').write('race')\n"
            "subprocess.run(['git', '-C', tmp, 'add', 'race.txt'], check=True)\n"
            "subprocess.run(['git', '-C', tmp, 'commit', '-m', 'race advance'], check=True)\n"
            "subprocess.run(['git', '-C', tmp, 'push', 'origin', 'develop'], check=True)\n"
            "open(%r, 'a').write(%r + chr(10))\n"
        ) % (self.origin.bare, self.docker_log, name)
        return {
            "name": name,
            "argv": [sys.executable, "-c", code],
            "cwd": "",
            "touches_stack": False,
        }

    def test_push_is_aborted_and_origin_keeps_only_the_race_commit(self):
        before_main = self.origin.main_sha()
        before_tags = self.origin.tags()
        steps = [self.race_advance_step(), self.fake_step("unit-tests")]
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertEqual(before_main, self.origin.main_sha())
        self.assertEqual(before_tags, self.origin.tags())
        tip = self.origin.develop_sha()
        raced = git(["show", "%s:race.txt" % tip], cwd=self.origin.bare, check=False)
        self.assertEqual(
            0, raced.returncode, "developの先頭がレースコミットのはず(スクリプトのVは積まれない)"
        )


class StepsSeeVersionXInTheCheckedOutTree(Harness):
    """AC3: 手順が見る checkout の版数ファイルはすべて X(検証しているのはタグの付く木R)。"""

    def version_probe_step(self, name="version-probe"):
        code = (
            "import sys, json, os, importlib.util\n"
            "spec = importlib.util.spec_from_file_location('rvt', %r)\n"
            "rvt = importlib.util.module_from_spec(spec)\n"
            "spec.loader.exec_module(rvt)\n"
            "checkout = sys.argv[1]\n"
            "values = {}\n"
            "for rel, kind in rvt.VERSION_LOCATIONS:\n"
            "    with open(os.path.join(checkout, rel), encoding='utf-8') as f:\n"
            "        text = f.read()\n"
            "    values[rel] = rvt.read_version(text, kind)\n"
            "open(%r, 'a').write(%r + ':' + json.dumps(values) + chr(10))\n"
        ) % (SCRIPT, self.docker_log, name)
        return {
            "name": name,
            "argv": [sys.executable, "-c", code, "%CHECKOUT%"],
            "cwd": "",
            "touches_stack": False,
        }

    def test_all_locations_report_x_during_steps(self):
        step = self.version_probe_step()
        table = self.write_step_table([step])
        r = self.run_script(
            self.origin.d,
            "--bump",
            "minor",
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        matching = [l for l in self.calls() if l.startswith("version-probe:")]
        self.assertEqual(1, len(matching), out)
        values = json.loads(matching[0].split(":", 1)[1])
        for rel, _ in rvt.VERSION_LOCATIONS:
            self.assertEqual("0.4.0", values[rel], rel)


class PushRejectionIsHandledAsFailure(Harness):
    """AC2-d: origin への push が拒否された場合(main が先に進んでいた等)。"""

    def test_push_rejection_leaves_no_partial_state(self):
        before_tags = self.origin.tags()
        table = self.write_step_table(self.default_steps())

        # push を確実に拒否させるための偽 git: push 以外は本物の git に転送する。
        fake_bin = os.path.join(self.tmp, "fakebin")
        os.makedirs(fake_bin)
        real_git = shutil.which("git")
        fake_git_path = os.path.join(fake_bin, "git")
        with open(fake_git_path, "w", encoding="utf-8") as f:
            f.write(
                "#!/usr/bin/env python3\n"
                "import subprocess, sys\n"
                "args = sys.argv[1:]\n"
                "if 'push' in args:\n"
                "    sys.stderr.write('! [rejected] main -> main (non-fast-forward)\\n')\n"
                "    sys.exit(1)\n"
                "sys.exit(subprocess.call([%r] + args))\n" % real_git
            )
        os.chmod(fake_git_path, 0o755)

        env_path = fake_bin + os.pathsep + os.environ["PATH"]
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
                "PATH": env_path,
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("push", out.lower())
        self.assertEqual(self.origin.m, self.origin.main_sha())
        self.assertEqual(self.origin.d, self.origin.develop_sha(), "atomic push なので develop も無傷のはず")
        self.assertEqual(before_tags, self.origin.tags())


class LockContentionBlocksEverything(Harness):
    """AC4: 別プロセスがロックを保持している間は何も始めない。"""

    def test_waits_reports_progress_and_times_out(self):
        lock_path = os.path.join(self.state_dir, "cycle.lock")
        os.makedirs(self.state_dir, exist_ok=True)
        holder_fh = open(lock_path, "a+")
        fcntl.flock(holder_fh, fcntl.LOCK_EX)
        try:
            before_main = self.origin.main_sha()
            table = self.write_step_table(self.default_steps())
            r = self.run_script(
                self.origin.d,
                extra_env={
                    "RELEASE_VERIFY_STEP_TABLE": table,
                    "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
                    "RELEASE_VERIFY_LOCK_TIMEOUT": "1",
                    "RELEASE_VERIFY_LOCK_POLL_INTERVAL": "0.2",
                },
                timeout=30,
            )
            out = self.out(r)
            self.assertNotEqual(0, r.returncode, out)
            self.assertIn("経過", out)
            self.assertEqual([], self.calls(), "ロック待ち中に手順が始まった:\n" + out)
            self.assertEqual(before_main, self.origin.main_sha())
        finally:
            fcntl.flock(holder_fh, fcntl.LOCK_UN)
            holder_fh.close()


class MainWorktreeAndStackAreRestoredAfterwards(Harness):
    """AC5: 実行後(成功・失敗いずれも)、メイン作業ツリーは不変で、引き渡しが行われる。"""

    def snapshot(self):
        head = rev_parse(self.main_worktree, "HEAD")
        branch = git(
            ["rev-parse", "--abbrev-ref", "HEAD"], cwd=self.main_worktree
        ).stdout.strip()
        status = git(["status", "--porcelain"], cwd=self.main_worktree).stdout
        return head, branch, status

    def test_main_worktree_state_is_unchanged_and_handoff_ran_after_success(self):
        before = self.snapshot()
        table = self.write_step_table(self.default_steps())
        handoff = self.write_handoff()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": handoff,
            },
        )
        self.assertEqual(0, r.returncode, self.out(r))
        self.assertEqual(before, self.snapshot())
        self.assertIn("handoff", self.calls())

    def test_main_worktree_state_is_unchanged_and_handoff_ran_after_failure(self):
        before = self.snapshot()
        steps = [self.fake_step("acceptance-tests", rc=1, touches_stack=True)]
        table = self.write_step_table(steps)
        handoff = self.write_handoff()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": handoff,
            },
        )
        self.assertNotEqual(0, r.returncode, self.out(r))
        self.assertEqual(before, self.snapshot())
        self.assertIn("handoff", self.calls())

    def test_handoff_is_skipped_when_the_stack_was_never_touched(self):
        """木不一致で手順が1つも始まらなかった場合、引き渡しは不要(既に無傷)。"""
        self.origin.add_direct_main_commit()
        table = self.write_step_table(self.default_steps())
        handoff = self.write_handoff()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": handoff,
            },
        )
        self.assertNotEqual(0, r.returncode, self.out(r))
        self.assertEqual([], self.calls())


class JestCountsParser(unittest.TestCase):
    """要件5: jest --json の出力から passed/failed/skipped を抽出する。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="rvt-jest-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write_report(self, data, rel="report.json"):
        p = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f)
        return rel

    def test_all_passed_is_ok(self):
        rel = self.write_report(
            {"numPassedTests": 10, "numFailedTests": 0, "numPendingTests": 0, "numTodoTests": 0}
        )
        counts = rvt.parse_jest_json_counts(self.tmp, rel)
        self.assertEqual(
            {"passed": 10, "failed": 0, "skipped": 0, "did_not_run": 0}, counts
        )

    def test_pending_test_is_reported_as_skipped_and_fails_ok(self):
        rel = self.write_report(
            {"numPassedTests": 9, "numFailedTests": 0, "numPendingTests": 1, "numTodoTests": 0}
        )
        counts = rvt.parse_jest_json_counts(self.tmp, rel)
        self.assertEqual(1, counts["skipped"])
        result = rvt.StepResult("web-test", 0, counts, 1.0, "npm test", "/tmp/x.log")
        self.assertFalse(result.ok)

    def test_missing_report_is_treated_as_failure(self):
        counts = rvt.parse_jest_json_counts(self.tmp, "does-not-exist.json")
        self.assertIsNone(counts)


class PlaywrightJsonCountsParser(unittest.TestCase):
    """要件5: playwright --reporter=json の出力から expected/unexpected/skipped/flaky を抽出する。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="rvt-pw-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write_report(self, stats, rel="playwright.json"):
        p = os.path.join(self.tmp, rel)
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"stats": stats}, f)
        return rel

    def test_all_expected_is_ok(self):
        rel = self.write_report({"expected": 25, "unexpected": 0, "skipped": 0, "flaky": 0})
        counts = rvt.parse_playwright_json_counts(self.tmp, rel)
        self.assertEqual(0, counts["failed"])
        self.assertEqual(0, counts["skipped"])
        self.assertEqual(0, counts.get("flaky", 0))

    def test_skipped_scenario_fails_ok(self):
        rel = self.write_report({"expected": 24, "unexpected": 0, "skipped": 1, "flaky": 0})
        counts = rvt.parse_playwright_json_counts(self.tmp, rel)
        result = rvt.StepResult("web-test-at-clean", 0, counts, 1.0, "playwright test", "/tmp/x.log")
        self.assertFalse(result.ok)

    def test_flaky_scenario_fails_ok(self):
        rel = self.write_report({"expected": 24, "unexpected": 0, "skipped": 0, "flaky": 1})
        counts = rvt.parse_playwright_json_counts(self.tmp, rel)
        result = rvt.StepResult("web-test-at-clean", 0, counts, 1.0, "playwright test", "/tmp/x.log")
        self.assertFalse(result.ok, "flaky>0 は要件5のゼロ許容に反するので失敗のはず")


class JunitXmlGlobCountsParser(unittest.TestCase):
    """要件5: `./gradlew test` の JUnit XML(全11モジュール)を集計する。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="rvt-junit-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write_xml(self, rel, tests, failures, errors, skipped):
        p = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(
                '<testsuite tests="%d" failures="%d" errors="%d" skipped="%d"></testsuite>'
                % (tests, failures, errors, skipped)
            )

    def test_aggregates_across_modules(self):
        self.write_xml(
            "services/blog-service/build/test-results/test/TEST-a.xml", 5, 0, 0, 0
        )
        self.write_xml(
            "packages/lbs-common/build/test-results/test/TEST-b.xml", 3, 0, 0, 0
        )
        counts = rvt.parse_junit_xml_glob_counts(
            self.tmp, "**/build/test-results/test/*.xml"
        )
        self.assertEqual(8, counts["passed"])
        self.assertEqual(0, counts["failed"])
        self.assertEqual(0, counts["skipped"])

    def test_skipped_testcase_in_any_module_fails_ok(self):
        self.write_xml(
            "services/blog-service/build/test-results/test/TEST-a.xml", 5, 0, 0, 0
        )
        self.write_xml(
            "services/media-service/build/test-results/test/TEST-c.xml", 4, 0, 0, 1
        )
        counts = rvt.parse_junit_xml_glob_counts(
            self.tmp, "**/build/test-results/test/*.xml"
        )
        self.assertEqual(1, counts["skipped"])
        result = rvt.StepResult("backend-gradle-test-lint", 0, counts, 1.0, "./gradlew test lint", "/tmp/x.log")
        self.assertFalse(result.ok)

    def test_no_report_files_is_treated_as_failure(self):
        counts = rvt.parse_junit_xml_glob_counts(
            self.tmp, "**/build/test-results/test/*.xml"
        )
        self.assertIsNone(counts)


class DefaultStepsWireRealCountsParsers(unittest.TestCase):
    """要件5: DEFAULT_STEPS の該当4手順が、exit codeだけでなく実際の出力から
    skipped/did_not_run/flakyを抽出するよう配線されている。"""

    def steps_by_name(self):
        return {s["name"]: s for s in rvt.DEFAULT_STEPS}

    def test_web_test_parses_jest_json(self):
        step = self.steps_by_name()["web-test"]
        self.assertEqual("jest", step.get("counts_parser"))
        self.assertIn("--json", step["argv"])

    def test_web_test_at_clean_parses_playwright_json(self):
        step = self.steps_by_name()["web-test-at-clean"]
        self.assertEqual("playwright_json", step.get("counts_parser"))
        self.assertTrue(
            any("json" in a for a in step["argv"]),
            "playwright の json レポーターが有効になっていない: %s" % step["argv"],
        )

    def test_extension_test_at_parses_jest_json(self):
        step = self.steps_by_name()["extension-test-at"]
        self.assertEqual("jest", step.get("counts_parser"))
        self.assertIn("--json", step["argv"])

    def test_backend_gradle_test_lint_parses_junit_xml(self):
        step = self.steps_by_name()["backend-gradle-test-lint"]
        self.assertEqual("junit_xml_glob", step.get("counts_parser"))
        self.assertIn("build/test-results", step.get("counts_source", ""))


class RealShapedStepEndToEndSkipIsCaughtByParser(Harness):
    """要件5(統合): counts_parser 配線が main() の実行を通じて実際に効くこと
    (exit codeが0でも、パーサが skipped>0 を検出したら失敗する)。"""

    def test_jest_style_skip_in_real_report_fails_the_run_even_with_exit_code_zero(self):
        report_rel = "apps/web/jest-report.json"
        code = (
            "import json,os,sys\n"
            "checkout = sys.argv[1]\n"
            "path = os.path.join(checkout, %r)\n"
            "os.makedirs(os.path.dirname(path), exist_ok=True)\n"
            "json.dump({'numPassedTests': 9, 'numFailedTests': 0, "
            "'numPendingTests': 1, 'numTodoTests': 0}, open(path, 'w'))\n"
            "sys.exit(0)\n"
        ) % report_rel
        steps = [
            {
                "name": "web-test",
                "argv": [sys.executable, "-c", code, "%CHECKOUT%"],
                "cwd": "",
                "counts_parser": "jest",
                "counts_source": report_rel,
            }
        ]
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertEqual(self.origin.m, self.origin.main_sha())


class GitignoredInputsAreCopiedBeforeSteps(Harness):
    """要件2: .env / certs/ をメイン作業ツリーからコピーし、最初の実手順より前に
    隔離チェックアウトに揃っていること。資格情報も手順のサブプロセス環境へ読み込む。"""

    def test_env_and_certs_exist_when_the_first_step_runs(self):
        steps = [
            self.fake_step_checking_checkout(
                "check-inputs", [".env", "certs/reverse-proxy.crt"]
            )
        ]
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        calls = self.calls()
        self.assertIn("check-inputs:.env=True,certs/reverse-proxy.crt=True", calls)

    def test_credential_env_file_is_loaded_into_step_subprocess_env(self):
        steps = [
            self.fake_step_checking_env(
                "check-cred-env", "E2E_SYNTHETIC_ACCOUNT_TOKEN"
            )
        ]
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        self.assertIn("check-cred-env:tok-123", self.calls())


class MissingEnvFileFailsCleanlyBeforeAnyStep(Harness):
    """要件2: メイン作業ツリーに .env が無い場合、手順を1つも始めずに明確な理由で失敗する。"""

    def test_missing_env_file_is_reported_and_no_step_runs(self):
        os.remove(os.path.join(self.main_worktree, ".env"))
        table = self.write_step_table(self.default_steps())
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn(".env", out)
        self.assertEqual([], self.calls())
        self.assertEqual(self.origin.m, self.origin.main_sha())


class EvidenceIsRetainedBeforeCheckoutIsDeleted(Harness):
    """要件9: 隔離チェックアウトを削除する前に、Playwright のレポートと
    `docker compose logs` をリポジトリ外(log_dir)へ保全する。"""

    def write_docker_logs_command(self, marker="docker-logs-captured"):
        p = os.path.join(self.tmp, "docker-logs-command.json")
        cmd = {
            "argv": [
                sys.executable,
                "-c",
                "print(%r)" % marker,
            ],
            "cwd": "",
        }
        with open(p, "w", encoding="utf-8") as f:
            json.dump(cmd, f)
        return p

    def find_log_dir(self):
        entries = [
            os.path.join(self.log_dir_parent, e)
            for e in os.listdir(self.log_dir_parent)
        ]
        self.assertEqual(1, len(entries), "log_dir がちょうど1つ作られているはず: %s" % entries)
        return entries[0]

    def test_playwright_report_and_docker_logs_survive_checkout_deletion(self):
        report_html = "apps/web/playwright-report/index.html"
        test_results_file = "apps/web/test-results/some-test/trace.zip"
        code = (
            "import os,sys\n"
            "checkout = sys.argv[1]\n"
            "for rel, content in %r:\n"
            "    p = os.path.join(checkout, rel)\n"
            "    os.makedirs(os.path.dirname(p), exist_ok=True)\n"
            "    open(p, 'w').write(content)\n"
            "sys.exit(0)\n"
        ) % [(report_html, "<html>report</html>"), (test_results_file, "trace")]
        steps = [
            {
                "name": "web-test-at-clean",
                "argv": [sys.executable, "-c", code, "%CHECKOUT%"],
                "cwd": "",
                "touches_stack": True,
            }
        ]
        table = self.write_step_table(steps)
        docker_logs_cmd = self.write_docker_logs_command()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
                "RELEASE_VERIFY_DOCKER_LOGS_COMMAND": docker_logs_cmd,
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)

        log_dir = self.find_log_dir()
        saved_report = os.path.join(log_dir, "evidence", report_html)
        saved_test_results = os.path.join(log_dir, "evidence", test_results_file)
        self.assertTrue(os.path.exists(saved_report), "Playwrightレポートが保全されていない: %s" % saved_report)
        with open(saved_report, encoding="utf-8") as f:
            self.assertEqual("<html>report</html>", f.read())
        self.assertTrue(
            os.path.exists(saved_test_results), "test-results が保全されていない: %s" % saved_test_results
        )

        docker_log_path = os.path.join(log_dir, "docker-compose-logs.txt")
        self.assertTrue(os.path.exists(docker_log_path), "docker compose logs が保全されていない")
        with open(docker_log_path, encoding="utf-8") as f:
            self.assertIn("docker-logs-captured", f.read())

    def test_evidence_capture_is_skipped_when_the_stack_was_never_touched(self):
        """要件9: そもそも受け入れテストの手順まで到達していない失敗では、レポートも
        docker compose logs も存在しないので、保全処理は何もしなくてよい(エラーにしない)。"""
        self.origin.add_direct_main_commit()
        table = self.write_step_table(self.default_steps())
        docker_logs_cmd = self.write_docker_logs_command()
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
                "RELEASE_VERIFY_DOCKER_LOGS_COMMAND": docker_logs_cmd,
            },
        )
        self.assertNotEqual(0, r.returncode, self.out(r))
        log_dir = self.find_log_dir()
        self.assertFalse(os.path.exists(os.path.join(log_dir, "docker-compose-logs.txt")))


class TouchesStackStepsReceiveTheSharedComposeProjectName(Harness):
    """#1297: 隔離チェックアウトは `tempfile.mkdtemp(prefix="checkout-")` に作られ、
    `.env` にも `docker-compose.yml` にも compose プロジェクト名が無いので、
    明示しない呼び出しは clone のディレクトリ名(`checkout-XXXX`)に解決されてしまう。
    `touches_stack` な手順は共有スタックのプロジェクト `lets_blog_server` を明示的に
    サブプロセス環境で受け取ること。"""

    def test_touches_stack_step_env_has_compose_project_name(self):
        step = self.fake_step_checking_env("touches-stack-step", "COMPOSE_PROJECT_NAME")
        step["touches_stack"] = True
        table = self.write_step_table([step])
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        self.assertIn(
            "touches-stack-step:lets_blog_server",
            self.calls(),
            "touches_stack な手順のサブプロセス環境に COMPOSE_PROJECT_NAME=lets_blog_server が無い:\n"
            + out,
        )

    def test_backend_expose_mysql_step_is_wired_to_the_shared_compose_project(self):
        """要件2: `backend-expose-mysql` が別プロジェクトの `mysql` を作らないよう、
        既定の手順表でも同じ配線になっていること。"""
        checkout_dir = tempfile.mkdtemp(prefix="checkout-")
        log_dir = tempfile.mkdtemp(prefix="rvt-logs-")
        self.addCleanup(shutil.rmtree, checkout_dir, ignore_errors=True)
        self.addCleanup(shutil.rmtree, log_dir, ignore_errors=True)
        step = next(s for s in rvt.DEFAULT_STEPS if s["name"] == "backend-expose-mysql")
        self.assertTrue(step.get("touches_stack"), "backend-expose-mysql が touches_stack でない")
        probe = dict(step)
        probe["argv"] = [
            sys.executable,
            "-c",
            "import os,sys; sys.stdout.write(os.environ.get('COMPOSE_PROJECT_NAME','<unset>'))",
        ]
        res = rvt.run_step(probe, checkout_dir, log_dir)
        with open(res.log_path, encoding="utf-8") as f:
            content = f.read()
        self.assertIn(
            "lets_blog_server",
            content,
            "backend-expose-mysql 相当の手順が COMPOSE_PROJECT_NAME=lets_blog_server を受け取っていない",
        )


class DefaultStepsCoverEveryRequiredCommand(unittest.TestCase):
    """要件4: 「しっかり」= 全部。既定の手順表が要求されたコマンドを網羅している。"""

    def setUp(self):
        with open(SCRIPT, encoding="utf-8") as f:
            self.text = f.read()

    def test_mentions_every_required_command(self):
        required = [
            '"ci"',
            "apps/web",
            "apps/extension",
            "apps/mcp-server",
            "apps/penpot-plugin",
            "packages/api-client",
            "unittest",
            "discover",
            ".claude/hooks",
            "test:at:clean",
            "ACCEPTANCE_RESET",
            "AT_WORKTREE_CHECK_BYPASS",
            "test:at",
            "docker-compose.host-tests.yml",
            "check-test-db.sh",
            "gradlew",
            "lint",
            "typecheck",
        ]
        for token in required:
            with self.subTest(token=token):
                self.assertIn(token, self.text, "既定の手順表に %s が無い" % token)


class IsolatedCheckoutBindsGitHooks(Harness):
    """#1298: 隔離チェックアウトは `scripts/setup-git-hooks.sh` で git フックを束縛する。

    束縛しないと、`python-unittest-scripts` 手順内の
    `test_git_hooks_binding.py::ThisCheckoutIsBound.test_core_hooks_path_is_bound` が
    その隔離チェックアウト自身に対して必ず落ちる(#1039 で追加されたテストが見ているのは
    「このチェックアウトの」`core.hooksPath` であり、隔離チェックアウトはメイン作業ツリーとは
    別の `.git/config` を持つため)。
    """

    def test_core_hooks_path_is_bound_in_the_isolated_checkout_before_the_first_step(self):
        steps = [self.fake_step_checking_hooks_path("check-hooks")] + self.default_steps()
        table = self.write_step_table(steps)
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        self.assertIn(
            "check-hooks:scripts/git-hooks",
            self.calls(),
            "隔離チェックアウトで手順開始前に core.hooksPath=scripts/git-hooks に"
            "束縛されていない:\n" + out,
        )

    def test_release_verify_tag_invokes_setup_git_hooks_script(self):
        """AC4: 束縛の手段が `scripts/setup-git-hooks.sh` であることをソース上でも名指しする。"""
        with open(SCRIPT, encoding="utf-8") as f:
            text = f.read()
        self.assertIn(
            "setup-git-hooks.sh",
            text,
            "release-verify-tag.py が scripts/setup-git-hooks.sh を呼び出していない",
        )


class MainWorktreeHooksPathIsUnaffectedByReleaseVerification(Harness):
    """#1298: 隔離チェックアウトでの束縛が、メイン作業ツリー(fixture)の
    `core.hooksPath` を変えないこと。"""

    def hooks_path_of_main_worktree(self):
        r = git(
            ["config", "--get", "core.hooksPath"], cwd=self.main_worktree, check=False
        )
        return r.stdout.strip() if r.returncode == 0 else None

    def test_main_worktree_core_hooks_path_is_unchanged_after_a_successful_run(self):
        before = self.hooks_path_of_main_worktree()
        self.assertIsNone(
            before, "fixture のメイン作業ツリーは束縛されていない状態から始まるはず"
        )
        table = self.write_step_table(self.default_steps())
        r = self.run_script(
            self.origin.d,
            extra_env={
                "RELEASE_VERIFY_STEP_TABLE": table,
                "RELEASE_VERIFY_HANDOFF_COMMAND": self.write_handoff(),
            },
        )
        self.assertEqual(0, r.returncode, self.out(r))
        after = self.hooks_path_of_main_worktree()
        self.assertEqual(
            before, after, "メイン作業ツリーの core.hooksPath が変更されてしまった"
        )


if __name__ == "__main__":
    unittest.main()
