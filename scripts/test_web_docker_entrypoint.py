#!/usr/bin/env python3
"""`apps/web/docker-entrypoint.sh` の検証(#1042)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない基準を
「その旨を明示してサービス/スクリプトレベルのテストで表現する」ことを明示的な例外として
認めている。`docker-entrypoint.sh` はコンテナの起動プロセスを差し替えるシェルスクリプトで
あり、Playwright は`docker compose up`後の稼働結果(HTTP経由の振る舞い)にしか触れられず、
「rootで作られたファイルが残るかどうか」というホスト側ファイル所有権はブラウザからは
一切観測できない。`scripts/test_setup_sh.py`(#960)と同じ、文書化された例外として
ここで表現する。

## どう検証するか

本物の `npm` / `chown` / `su-exec` を叩くわけにはいかない(前者はネットワークに触れ、
後者はホストに未インストールなことがある)。`scripts/test_setup_sh.py` と同じ手法で、
これらをPATH上の偽物に差し替え、呼び出しを `FAKE_LOG` に記録する。`stat` と
`chown`(所有者を変えない自己chown)は実体をそのまま使う——`APP_DIR` に
一時ディレクトリ(呼び出しユーザー自身が所有)を使うため、権限昇格なしに検証できる。

`docker-entrypoint.sh` は `APP_DIR` 環境変数で対象ディレクトリを上書きできる
(既定は `/app`)。本番の動作(Dockerfileからの起動)は既定値のままで変わらない。
"""

import os
import shutil
import stat as stat_module
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
ENTRYPOINT_SCRIPT = os.path.join(REPO_ROOT, "apps", "web", "docker-entrypoint.sh")

FAKE_BINS = {}

FAKE_BINS["npm"] = """#!/bin/bash
echo "npm $*" >> "$FAKE_LOG"
if [ "$1" = "ci" ]; then
  if [ -n "$FAKE_NPM_CI_FAIL" ]; then
    exit 1
  fi
  mkdir -p node_modules/.bin
  printf '#!/bin/sh\\nexit 0\\n' > node_modules/.bin/next
  chmod +x node_modules/.bin/next
fi
exit 0
"""

# su-exec: 本物は uid:gid でexecし直すが、テスト環境では権限昇格を伴う検証はしない。
# 呼び出し(uid:gid と後続コマンド)をログに残し、そのまま同一ユーザーで実行を継続する。
FAKE_BINS["su-exec"] = """#!/bin/bash
echo "su-exec $*" >> "$FAKE_LOG"
target="$1"
shift
exec "$@"
"""


def _write_bin(bin_dir, name, content):
    path = os.path.join(bin_dir, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    st = os.stat(path)
    os.chmod(path, st.st_mode | stat_module.S_IEXEC | stat_module.S_IXGRP | stat_module.S_IXOTH)


class DockerEntrypointTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="web-entrypoint-test-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        self.app_dir = os.path.join(self.tmp, "app")
        os.makedirs(self.app_dir)

        self.bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin_dir)
        self.fake_log = os.path.join(self.tmp, "fake.log")
        for name, content in FAKE_BINS.items():
            _write_bin(self.bin_dir, name, content)

        self._write_lock("lock-v1")

        st = os.stat(self.app_dir)
        self.expected_uid = st.st_uid
        self.expected_gid = st.st_gid

    def _run(self, args, extra_env=None):
        env = dict(os.environ)
        env["PATH"] = self.bin_dir + os.pathsep + env["PATH"]
        env["FAKE_LOG"] = self.fake_log
        env["APP_DIR"] = self.app_dir
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            ["sh", ENTRYPOINT_SCRIPT, *args],
            cwd=self.app_dir,
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def _fake_log_lines(self):
        if not os.path.exists(self.fake_log):
            return []
        with open(self.fake_log, encoding="utf-8") as f:
            return [l.rstrip("\n") for l in f]

    def _write_lock(self, content):
        with open(os.path.join(self.app_dir, "package-lock.json"), "w", encoding="utf-8") as f:
            f.write(content)

    def _npm_ci_count(self):
        return sum(1 for l in self._fake_log_lines() if l.startswith("npm ci"))

    def _make_next_present(self):
        bin_dir = os.path.join(self.app_dir, "node_modules", ".bin")
        os.makedirs(bin_dir, exist_ok=True)
        next_path = os.path.join(bin_dir, "next")
        with open(next_path, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\nexit 0\n")
        st = os.stat(next_path)
        os.chmod(next_path, st.st_mode | stat_module.S_IEXEC)


class PrivilegeDropping(DockerEntrypointTestCase):
    """#1042 AC1/AC2: 最終コマンドはホストの実行ユーザー(バインドマウント元の所有者)
    へ権限を落としてから実行される。next dev が稼働中に作るファイル(.next 等)が
    root所有にならないための唯一の防御線。"""

    def test_final_command_runs_through_su_exec_with_bind_mount_owner(self):
        self._make_next_present()
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = "\n".join(self._fake_log_lines())
        self.assertIn(
            f"su-exec {self.expected_uid}:{self.expected_gid} true",
            log,
            f"su-exec でバインドマウント元の所有者へ権限を落としていない: {log!r}",
        )

    def test_final_command_is_actually_executed(self):
        """権限を落とすだけでなく、渡されたコマンド自体がちゃんと実行されること
        (su-execを挟んだことで元のexec "$@" の効果が失われていないこと)。"""
        self._make_next_present()
        marker = os.path.join(self.tmp, "ran")
        r = self._run(["sh", "-c", f"touch {marker}"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertTrue(os.path.exists(marker))


class NpmCiWhenNextMissing(DockerEntrypointTestCase):
    """#1050 の既存挙動(node_modules/.bin/next が無ければ npm ci する)を、
    su-exec 導入後も壊していないことの回帰確認。"""

    def test_npm_ci_runs_and_chowns_before_dropping_privileges(self):
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = self._fake_log_lines()
        self.assertTrue(
            any(l.startswith("npm ci") for l in log),
            f"node_modules/.bin/next が無いのに npm ci が呼ばれていない: {log}",
        )
        # npm ci の後、最終的にsu-exec経由で"$@"が実行されること
        self.assertTrue(
            any(l.startswith("su-exec") for l in log),
            f"npm ci の後にsu-execで権限を落としていない: {log}",
        )

    def test_npm_ci_skipped_when_next_already_present(self):
        # 判定用の記録(#1607)が無いと npm ci が走るため、まず1回起動して記録を作り、
        # 以後 next も記録も揃った状態で npm ci が呼ばれないことを確認する。
        self._make_next_present()
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        os.remove(self.fake_log)
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = self._fake_log_lines()
        self.assertFalse(
            any(l.startswith("npm ci") for l in log),
            f"next 実行ファイルも記録もあるのに npm ci が呼ばれている: {log}",
        )


class NpmCiWhenLockfileChanged(DockerEntrypointTestCase):
    """#1607: node_modules があっても package-lock.json が変わっていれば入れ直す。"""

    def test_ac1_lock_changed_runs_npm_ci_then_su_exec(self):
        self._run(["true"])  # 初回インストールで記録を作る
        os.remove(self.fake_log)
        self._write_lock("lock-v2")
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = self._fake_log_lines()
        ci = [i for i, l in enumerate(log) if l.startswith("npm ci")]
        su = [i for i, l in enumerate(log) if l.startswith("su-exec")]
        self.assertTrue(ci, f"lock が変わったのに npm ci が呼ばれていない: {log}")
        self.assertTrue(su and su[0] > ci[0], f"npm ci の後に su-exec で実行されていない: {log}")

    def test_ac2_lock_unchanged_skips_npm_ci(self):
        self._run(["true"])
        os.remove(self.fake_log)
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(0, self._npm_ci_count(), self._fake_log_lines())

    def test_ac3_no_stamp_runs_npm_ci_once(self):
        self._make_next_present()  # 修正前から使っている環境: next はあるが記録が無い
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(1, self._npm_ci_count(), self._fake_log_lines())
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(1, self._npm_ci_count(), "2回目の起動でも npm ci が走った")

    def test_ac4_npm_ci_failure_exits_nonzero_and_retries_next_time(self):
        self._run(["true"])
        os.remove(self.fake_log)
        self._write_lock("lock-v2")
        marker = os.path.join(self.tmp, "ran")
        r = self._run(["sh", "-c", f"touch {marker}"], {"FAKE_NPM_CI_FAIL": "1"})
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(marker), '失敗したのに "$@" が実行された')
        self.assertFalse(any(l.startswith("su-exec") for l in self._fake_log_lines()))
        os.remove(self.fake_log)
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(1, self._npm_ci_count(), "失敗後の次の起動で npm ci が再実行されていない")


class NextCacheClearedOnNpmCi(DockerEntrypointTestCase):
    """#1609: npm ci を実行した起動では、"$@" の実行前に .next(Turbopack 永続キャッシュ)を
    消す。実行しない起動では .next をそのまま残す。"""

    OBSERVER = 'if [ -e "$APP_DIR/.next" ]; then echo present > "$MARKER"; else echo absent > "$MARKER"; fi'

    def _make_cache(self):
        d = os.path.join(self.app_dir, ".next", "dev", "cache")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "x"), "w", encoding="utf-8") as f:
            f.write("stale")

    def _observe(self):
        self.marker = os.path.join(self.tmp, "marker")
        return self._run(["sh", "-c", self.OBSERVER], {"MARKER": self.marker})

    def _marker(self):
        with open(self.marker, encoding="utf-8") as f:
            return f.read().strip()

    def test_ac1_lock_changed_removes_next_before_command(self):
        self._run(["true"])  # 記録を作る
        self._write_lock("lock-v2")
        self._make_cache()
        r = self._observe()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(2, self._npm_ci_count())
        self.assertEqual("absent", self._marker())

    def test_ac2_no_stamp_removes_next_before_command(self):
        self._make_next_present()  # next はあるが記録が無い
        self._make_cache()
        r = self._observe()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual("absent", self._marker())

    def test_ac2_next_missing_removes_next_before_command(self):
        self._make_cache()
        r = self._observe()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual("absent", self._marker())

    def test_ac2_no_next_dir_is_not_an_error(self):
        r = self._observe()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual("absent", self._marker())

    def test_ac3_stamp_matches_keeps_next(self):
        self._run(["true"])
        os.remove(self.fake_log)
        self._make_cache()
        r = self._observe()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(0, self._npm_ci_count())
        self.assertEqual("present", self._marker())
        self.assertTrue(os.path.exists(os.path.join(self.app_dir, ".next", "dev", "cache", "x")))

    def test_npm_ci_failure_keeps_next(self):
        self._run(["true"])
        self._write_lock("lock-v2")
        self._make_cache()
        r = self._run(["true"], {"FAKE_NPM_CI_FAIL": "1"})
        self.assertNotEqual(0, r.returncode)
        self.assertTrue(os.path.exists(os.path.join(self.app_dir, ".next", "dev", "cache", "x")))


if __name__ == "__main__":
    unittest.main()
