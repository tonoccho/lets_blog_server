#!/usr/bin/env python3
"""受け入れテストの実行区間を、起動経路によらず排他するロックの検証(#1187)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin でも、フル Playwright 実行でもないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルのテストで
表現する」ことを明示的な例外として認めている。

本Issueの受入基準は「2つの受け入れテスト実行が同一ホスト上で重ならない」ことであり、
検証には受け入れテストの実行そのものを**2つ同時に**走らせる必要がある。実際に2つの
スタックを構築して検証するのは(#1187 Out of Scope 3 が退けた分散スタックと同種の)
過大なコストであり、排他制御自体は `apps/web/e2e/at-lock.ts` という1モジュールの
振る舞いに閉じている。そこでロックモジュール単体を対象にする
(`scripts/test_shared_host_proxy.py` / `scripts/test_rebuild_acceptance_env.py` と
同じ、文書化された例外)。

要件2(`npx playwright test` を直接起動してもロックが効く)は、`playwright.config.ts` の
`globalSetup` / `globalTeardown` がPlaywrightのどの起動経路でも必ず呼ばれるという
既存の仕組みによって**構造的に**満たされる。npm script をラップする案ではこれを
取りこぼす、というのが#1187 Implementation Notesの理由づけである。本ファイルは
その配線(global-setup.ts / global-teardown.ts がロック獲得・解放を呼んでいること、
playwright.config.ts が両ファイルを常に読み込むこと)をソース検査で確認する。

## どう検証するか

Node は `.ts` ファイルをそのまま実行できる(型ストリッピング、Node 22.6+)。
このテストは、`apps/web/e2e/at-lock.ts` を `import()` する小さな使い捨てNodeスクリプトを
一時ディレクトリへ書き出し、複数プロセスとして起動して排他の実測を行う。

ロックファイルの場所は環境変数 `AT_LOCK_FILE` で上書きできるようにし(本番は
`${XDG_RUNTIME_DIR:-/tmp}` 配下の固定パス)、テストごとに個別の一時ファイルを使うことで
本物の受け入れテスト実行と衝突しないようにする。
"""

import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

MODULE = os.path.join(REPO_ROOT, "apps/web/e2e/at-lock.ts")
GLOBAL_SETUP = os.path.join(REPO_ROOT, "apps/web/e2e/global-setup.ts")
GLOBAL_TEARDOWN = os.path.join(REPO_ROOT, "apps/web/e2e/global-teardown.ts")
PLAYWRIGHT_CONFIG = os.path.join(REPO_ROOT, "apps/web/playwright.config.ts")
DOC = os.path.join(REPO_ROOT, "docs/ACCEPTANCE_TESTING.md")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def exists(path):
    return os.path.exists(path)


# 使い捨てのNodeスクリプト本体。%s へ at-lock.ts の絶対パスを埋め込む。
HARNESS = r"""
(async () => {
  const mod = await import(%r);
  const holdMs = Number(process.env.TEST_HOLD_MS || '0');
  try {
    await mod.acquireAcceptanceTestLock();
    console.log('ACQUIRED pid=' + process.pid);
    if (holdMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, holdMs));
    }
    if (process.env.TEST_NO_RELEASE === '1') {
      // SIGKILL検証用: 明示的な解放をせず、プロセスを生かしたままにする。
      setInterval(() => {}, 1000);
    } else {
      mod.releaseAcceptanceTestLock();
      console.log('RELEASED pid=' + process.pid);
    }
  } catch (e) {
    console.error('FAILED ' + e.message);
    process.exitCode = 1;
  }
})();
""" % (MODULE,)


class LockHarness(unittest.TestCase):
    def setUp(self):
        self.assertTrue(exists(MODULE), "%s が無い" % MODULE)
        self.tmp = tempfile.mkdtemp(prefix="at-lock-test-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.lock_file = os.path.join(self.tmp, "at.lock")
        self.script = os.path.join(self.tmp, "harness.mjs")
        with open(self.script, "w", encoding="utf-8") as f:
            f.write(HARNESS)
        self.procs = []
        self.addCleanup(self._kill_all)

    def _kill_all(self):
        for p in self.procs:
            try:
                p.kill()
            except Exception:
                pass
            try:
                p.wait(timeout=5)
            except Exception:
                pass
            if p.stdout:
                p.stdout.close()

    def env(self, **overrides):
        env = dict(os.environ)
        env["AT_LOCK_FILE"] = self.lock_file
        env.setdefault("AT_LOCK_POLL_SECONDS", "0.2")
        env.setdefault("AT_LOCK_TIMEOUT_SECONDS", "30")
        env.update({k: str(v) for k, v in overrides.items()})
        return env

    def start(self, **env_overrides):
        """バックグラウンドで起動し、Popenを返す(stdout/stderrをキャプチャ)。"""
        p = subprocess.Popen(
            ["node", self.script],
            cwd=REPO_ROOT,
            env=self.env(**env_overrides),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.procs.append(p)
        return p

    def run_sync(self, timeout=15, **env_overrides):
        r = subprocess.run(
            ["node", self.script],
            cwd=REPO_ROOT,
            env=self.env(**env_overrides),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
        )
        return r

    def wait_for_line(self, proc, pattern, timeout=10):
        """procの標準出力(既にキャプチャ中)からpatternに一致する行を待って返す。"""
        deadline = time.time() + timeout
        buf = ""
        while time.time() < deadline:
            line = proc.stdout.readline()
            if line:
                buf += line
                if re.search(pattern, line):
                    return line
            else:
                time.sleep(0.05)
        self.fail("%s 秒待っても %r に一致する行が出なかった:\n%s" % (timeout, pattern, buf))


class SerializesTwoOverlappingRuns(LockHarness):
    """受入基準1・2: 2つ目は1つ目の完了を待ってから開始し、待機中はPID・経過を出す。"""

    def test_second_waits_for_the_first_then_acquires(self):
        holder = self.start(TEST_HOLD_MS="1500")
        self.wait_for_line(holder, r"ACQUIRED")

        started = time.time()
        waiter = self.run_sync(timeout=10, AT_LOCK_POLL_SECONDS="0.2")
        elapsed = time.time() - started

        self.assertEqual(0, waiter.returncode, waiter.stdout)
        self.assertIn("ACQUIRED", waiter.stdout)
        self.assertGreaterEqual(
            elapsed, 1.0, "2つ目がほぼ即座に獲得できてしまった(排他になっていない):\n" + waiter.stdout
        )

    def test_waiter_prints_holder_pid_and_elapsed_time(self):
        holder = self.start(TEST_HOLD_MS="1500")
        line = self.wait_for_line(holder, r"ACQUIRED pid=(\d+)")
        holder_pid = re.search(r"ACQUIRED pid=(\d+)", line).group(1)

        waiter = self.run_sync(timeout=10, AT_LOCK_POLL_SECONDS="0.2")
        self.assertEqual(0, waiter.returncode, waiter.stdout)
        self.assertIn(
            holder_pid,
            waiter.stdout,
            "待機中の出力に保持者のPIDが含まれていない:\n" + waiter.stdout,
        )
        self.assertRegex(
            waiter.stdout,
            r"経過\s*\d+\s*秒",
            "待機中の出力に経過時間が含まれていない:\n" + waiter.stdout,
        )

    def test_both_runs_complete_without_skipping_or_failing(self):
        holder = self.start(TEST_HOLD_MS="1000")
        waiter = self.run_sync(timeout=10)
        holder.wait(timeout=10)
        holder_out = holder.stdout.read()

        self.assertEqual(0, waiter.returncode, waiter.stdout)
        self.assertEqual(0, holder.returncode, holder_out)
        self.assertIn("ACQUIRED", holder_out)
        self.assertIn("RELEASED", holder_out)
        self.assertIn("ACQUIRED", waiter.stdout)
        self.assertIn("RELEASED", waiter.stdout)


class SigkillReleasesTheLockImmediately(LockHarness):
    """受入基準4: SIGKILL後、次の実行はロック待ちにならず即座に獲得できる。"""

    def test_next_run_acquires_immediately_after_sigkill(self):
        holder = self.start(TEST_NO_RELEASE="1")
        line = self.wait_for_line(holder, r"ACQUIRED pid=(\d+)")
        holder_pid = int(re.search(r"ACQUIRED pid=(\d+)", line).group(1))

        os.kill(holder_pid, signal.SIGKILL)
        # OSがプロセスを回収してfdを閉じるまでのわずかな猶予。
        deadline = time.time() + 5
        while time.time() < deadline:
            try:
                os.kill(holder_pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.05)

        started = time.time()
        waiter = self.run_sync(timeout=10, AT_LOCK_TIMEOUT_SECONDS="2")
        elapsed = time.time() - started

        self.assertEqual(0, waiter.returncode, waiter.stdout)
        self.assertIn("ACQUIRED", waiter.stdout)
        self.assertLess(
            elapsed,
            1.5,
            "SIGKILL後なのにロック待ちが発生している(自動解放されていない):\n" + waiter.stdout,
        )


class TimeoutIsConfigurableAndFailsClearly(LockHarness):
    """受入基準5: 待機上限(環境変数)を超えたら、明確なメッセージで非0終了する。"""

    def test_exceeding_the_configured_timeout_is_a_clear_non_zero_failure(self):
        holder = self.start(TEST_HOLD_MS="8000")
        self.wait_for_line(holder, r"ACQUIRED")

        waiter = self.run_sync(timeout=10, AT_LOCK_TIMEOUT_SECONDS="1", AT_LOCK_POLL_SECONDS="0.2")

        self.assertNotEqual(0, waiter.returncode, "上限を超えたのに成功した:\n" + waiter.stdout)
        self.assertIn("FAILED", waiter.stdout)
        self.assertIn(
            self.lock_file,
            waiter.stdout,
            "どのロックファイルで待っていたかが分からない:\n" + waiter.stdout,
        )
        self.assertRegex(
            waiter.stdout,
            r"AT_LOCK_TIMEOUT_SECONDS",
            "待機上限を調整する環境変数名が案内されていない:\n" + waiter.stdout,
        )


class SameProcessAcquireIsIdempotent(LockHarness):
    """自プロセスが既に保持している場合、再度の獲得要求はブロックしない(自己デッドロック防止)。"""

    def test_acquiring_twice_in_the_same_process_does_not_block(self):
        script = os.path.join(self.tmp, "idempotent.mjs")
        with open(script, "w", encoding="utf-8") as f:
            f.write(
                r"""
(async () => {
  const mod = await import(%r);
  await mod.acquireAcceptanceTestLock();
  await mod.acquireAcceptanceTestLock();
  console.log('ACQUIRED_TWICE');
  mod.releaseAcceptanceTestLock();
})();
"""
                % (MODULE,)
            )
        r = subprocess.run(
            ["node", script],
            cwd=REPO_ROOT,
            env=self.env(),
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("ACQUIRED_TWICE", r.stdout)


class FlockBinaryMissingFailsFast(LockHarness):
    """`flock` コマンド自体が起動できない場合、ロック競合と区別して即座に失敗する。

    レビュー指摘(#1187): `tryAcquireNonBlocking()` は `flock -n` の非0終了(競合)と
    `flock` コマンドを spawn できないこと(ENOENT等)を区別せず、どちらも「まだ獲得できて
    いない」として扱っていた。後者は `AT_LOCK_TIMEOUT_SECONDS`(既定7200秒 = 2時間)
    いっぱいまで無言でリトライしてしまい、原因が「competing」ではなく「flockが無い」
    ことが分かりにくい。`flock` が存在しないPATHで実行した場合、即座に(タイムアウトを
    待たず)`flock` という語を含むメッセージで失敗することを検証する。
    """

    def setUp(self):
        super().setUp()
        # "flock" を含まない最小のPATHを用意する(nodeだけをシンボリックリンクで置く)。
        self.no_flock_bin = os.path.join(self.tmp, "no-flock-bin")
        os.makedirs(self.no_flock_bin, exist_ok=True)
        node_path = shutil.which("node")
        self.assertIsNotNone(node_path, "テスト環境に node が見つからない")
        os.symlink(node_path, os.path.join(self.no_flock_bin, "node"))

    def test_fails_fast_with_a_clear_message_when_flock_binary_is_missing(self):
        started = time.time()
        waiter = self.run_sync(
            timeout=10,
            PATH=self.no_flock_bin,
            AT_LOCK_TIMEOUT_SECONDS="30",
            AT_LOCK_POLL_SECONDS="0.2",
        )
        elapsed = time.time() - started

        self.assertNotEqual(
            0, waiter.returncode, "flockが無いのに成功してしまった:\n" + waiter.stdout
        )
        self.assertIn("FAILED", waiter.stdout)
        self.assertIn(
            "flock",
            waiter.stdout,
            "エラーメッセージにflockという語が含まれていない:\n" + waiter.stdout,
        )
        self.assertLess(
            elapsed,
            5.0,
            "flockが無いのにタイムアウト上限まで待ってしまっている(リトライしている):\n"
            + waiter.stdout,
        )


class WiredIntoGlobalSetupAndTeardown(unittest.TestCase):
    """受入基準2: 起動経路によらず効く配線になっていること(ソース検査)。"""

    def setUp(self):
        self.assertTrue(exists(GLOBAL_SETUP), "%s が無い" % GLOBAL_SETUP)
        self.assertTrue(exists(GLOBAL_TEARDOWN), "%s が無い" % GLOBAL_TEARDOWN)
        self.assertTrue(exists(PLAYWRIGHT_CONFIG), "%s が無い" % PLAYWRIGHT_CONFIG)
        self.setup_text = read(GLOBAL_SETUP)
        self.teardown_text = read(GLOBAL_TEARDOWN)
        self.config_text = read(PLAYWRIGHT_CONFIG)

    def test_global_setup_acquires_the_lock(self):
        self.assertIn("acquireAcceptanceTestLock", self.setup_text)

    def test_global_teardown_releases_the_lock(self):
        self.assertIn("releaseAcceptanceTestLock", self.teardown_text)

    def test_lock_is_acquired_after_the_browser_check_and_before_the_reset(self):
        """#1045と同じ理由: ブラウザ未導入のホストを無駄に待たせない。

        import文にも同じ識別子が現れるため、`.index()` をファイル全体へ使うと
        importの並び順を見てしまう。実際の**呼び出し箇所**だけを見る。
        """
        browser_check_at = self.setup_text.index("await checkBrowsersLaunchable(")
        lock_at = self.setup_text.index("await acquireAcceptanceTestLock(")
        reset_at = self.setup_text.index("process.env.ACCEPTANCE_RESET === '1'")
        self.assertLess(
            browser_check_at, lock_at, "ロック獲得がブラウザ起動確認より先に呼ばれている"
        )
        self.assertLess(
            lock_at, reset_at, "ロック獲得が ACCEPTANCE_RESET のゼロ構築より後に呼ばれている"
            "(ゼロ構築中の排他が効かない)"
        )

    def test_playwright_config_always_wires_both_hooks(self):
        """globalSetup/globalTeardownはPlaywrightのconfigレベルの設定であり、
        `npm run test:at` 経由でも `npx playwright test` の直接起動でも、
        このconfigを読み込む限り必ず呼ばれる。npm scriptのラップでは
        直接起動を取りこぼす、というのが#1187の設計根拠。"""
        self.assertRegex(self.config_text, r"globalSetup:\s*['\"]\./e2e/global-setup\.ts['\"]")
        self.assertRegex(
            self.config_text, r"globalTeardown:\s*['\"]\./e2e/global-teardown\.ts['\"]"
        )


class Documentation(unittest.TestCase):
    """Scope: docs/ACCEPTANCE_TESTING.md へロックの節を追加する。"""

    def setUp(self):
        self.assertTrue(exists(DOC), "%s が無い" % DOC)
        self.doc = read(DOC)

    def test_has_a_section_about_the_lock(self):
        headings = [l for l in self.doc.splitlines() if l.startswith("## ")]
        self.assertTrue(
            any("排他" in h or "ロック" in h for h in headings),
            "ロックの節が無い。見出し一覧:\n  %s" % "\n  ".join(headings),
        )

    def test_documents_the_timeout_variable(self):
        self.assertIn("AT_LOCK_TIMEOUT_SECONDS", self.doc)

    def test_documents_the_lock_file_location(self):
        self.assertIn("XDG_RUNTIME_DIR", self.doc)

    def test_explains_why_global_setup_rather_than_wrapping_npm_scripts(self):
        self.assertIn("npx playwright test", self.doc)


if __name__ == "__main__":
    unittest.main()
