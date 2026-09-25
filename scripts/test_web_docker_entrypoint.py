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
        self._make_next_present()
        r = self._run(["true"])
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        log = self._fake_log_lines()
        self.assertFalse(
            any(l.startswith("npm ci") for l in log),
            f"next 実行ファイルが既にあるのに npm ci が呼ばれている: {log}",
        )


if __name__ == "__main__":
    unittest.main()
