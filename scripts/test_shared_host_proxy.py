#!/usr/bin/env python3
"""ホストの80/443を他スタックと共有したまま受け入れテストを実行できることの検証(#1038)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルのテストで
表現する」ことを明示的な例外として認めている。

本Issueで到達可能にしようとしているもの**そのもの**が受け入れテストの実行である。
着手時点では `.feature` を1本も実行できない(`apps/web/e2e/global-setup.ts:55` の疎通確認が
`https://localhost` へ到達できず、全 `@stage:` プロジェクトが global-setup で落ちる)。
つまり受入基準を Gherkin で表現しても、その Gherkin を走らせる手段が無い —
これは「書かなかった」のではなく、構造的に書けない。加えて対象は nginx の vhost、
compose のポート公開、ヘルスチェック、開発者向けスクリプトであり、製品の画面には現れない。

したがって `scripts/test_git_hooks_binding.py`(#1039)・`scripts/test_check_env.py` と同じ
**文書化された例外**として、ここで表現する。

## なぜ機械的に検査するのか

この Issue が直す壊れ方は、どれも「静かに壊れる」型である。

  - `lbs-reverse-proxy` はネットワークから完全に切り離されていても、コンテナ内の
    `127.0.0.1:80/nginx-health` を叩くヘルスチェックは通る。**healthy と報告され続ける**
  - `scripts/wait-for-stack-healthy.sh` はそれを見て `OK: 対象サービスは全てhealthyです` を返す
  - infra-proxy 側の vhost で上流名を**変数経由にせず**書くと、lets_blog_server スタックを
    落としている間に nginx が起動時の名前解決に失敗し、**GitLab ごと落ちる**。
    しかも lbs スタックが動いている間は何の症状も出ないため、レビューでは気づけない

最後の1点はとりわけ危険で、回帰したときの被害が本リポジトリの外(GitLab = このワークフロー
自身の基盤)に及ぶ。回帰させないことを検査で固定する。
"""

import os
import re
import shutil
import socket
import stat
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

VHOST = "infra/shared-host/20-localhost.conf"
OVERRIDE_COMPOSE = "docker-compose.shared-host.yml"
BASE_COMPOSE = "docker-compose.yml"
SETUP_SCRIPT = "scripts/setup-shared-host-proxy.sh"
WAIT_SCRIPT = "scripts/wait-for-stack-healthy.sh"
DOC = "docs/ACCEPTANCE_TESTING.md"


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


def exists(rel_path):
    return os.path.exists(os.path.join(REPO_ROOT, rel_path))


def strip_comments(text):
    """行頭コメント・行末コメントを落とす(nginx 設定の実効行だけを見るため)。"""
    out = []
    for line in text.splitlines():
        out.append(re.sub(r"#.*$", "", line))
    return "\n".join(out)


def service_block(compose_text, service):
    """docker-compose.yml から1サービスのブロック(インデント2の見出しから次の見出しまで)を返す。"""
    lines = compose_text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line == "  %s:" % service:
            start = i
            break
    if start is None:
        return None
    for j in range(start + 1, len(lines)):
        if re.match(r"^  \S", lines[j]):
            return "\n".join(lines[start:j])
    return "\n".join(lines[start:])


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class VhostIsSafeToLoadIntoTheSharedProxy(unittest.TestCase):
    """受入基準1の手段: infra-proxy へ載せる vhost が、GitLab を巻き込まずに localhost を中継する。"""

    def setUp(self):
        self.assertTrue(exists(VHOST), "%s が無い" % VHOST)
        self.conf = strip_comments(read(VHOST))

    def test_serves_localhost_on_80_and_443(self):
        self.assertRegex(self.conf, r"listen\s+80\s*;", "80番の server が無い")
        self.assertRegex(self.conf, r"listen\s+443\s+ssl", "443番の ssl server が無い")
        self.assertRegex(self.conf, r"server_name\s+localhost\s*;", "server_name localhost が無い")

    def test_does_not_steal_the_default_server(self):
        """`default_server` を名乗ると infra 側の `return 444` を奪い、未知ホストの扱いが変わる。"""
        self.assertNotIn("default_server", self.conf)

    def test_uses_the_lbs_self_signed_certificate(self):
        self.assertIn("/etc/nginx/certs/localhost.crt", self.conf)
        self.assertIn("/etc/nginx/certs/localhost.key", self.conf)

    def test_redirects_http_to_https(self):
        self.assertRegex(self.conf, r"return\s+30[18]\s+https://")

    def test_resolves_the_upstream_lazily(self):
        """**回帰させてはいけない検査。**

        上流名を `proxy_pass https://lbs-reverse-proxy:443;` と直接書くと、nginx は
        **起動時**に名前解決する。lets_blog_server スタックを停止している間に infra-proxy を
        再起動・reload すると解決に失敗して nginx が上がらず、**GitLab ごと落ちる**。
        変数経由にすると解決がリクエスト時になり、上流が居なければ 502 を返すだけで済む。
        """
        self.assertRegex(
            self.conf,
            r"resolver\s+127\.0\.0\.11\b",
            "Docker 組み込み DNS の resolver が無い。変数経由の proxy_pass は解決できない",
        )
        self.assertIn("ipv6=off", self.conf, "resolver に ipv6=off が無い")
        self.assertRegex(
            self.conf,
            r"proxy_pass\s+\$",
            "proxy_pass が変数経由でない(起動時解決に戻っている)",
        )
        self.assertNotRegex(
            self.conf,
            r"proxy_pass\s+https?://",
            "proxy_pass にホスト名を直書きしている。起動時解決に戻り GitLab を巻き込む",
        )

    def test_upstream_is_the_lbs_reverse_proxy_over_tls(self):
        self.assertRegex(self.conf, r"set\s+\$\w+\s+https://lbs-reverse-proxy:443\s*;")

    def test_accepts_the_self_signed_upstream_certificate(self):
        """lbs 側は自己署名 + `server_name localhost` なので、SNI を localhost で送り検証を切る。"""
        self.assertRegex(self.conf, r"proxy_ssl_verify\s+off\s*;")
        self.assertRegex(self.conf, r"proxy_ssl_server_name\s+on\s*;")
        self.assertRegex(self.conf, r"proxy_ssl_name\s+localhost\s*;")

    def test_forwards_the_original_host_and_scheme(self):
        for header in (
            r"proxy_set_header\s+Host\s+\$host\s*;",
            r"proxy_set_header\s+X-Real-IP\s+\$remote_addr\s*;",
            r"proxy_set_header\s+X-Forwarded-For\s+\$proxy_add_x_forwarded_for\s*;",
            r"proxy_set_header\s+X-Forwarded-Proto\s+https\s*;",
        ):
            with self.subTest(header=header):
                self.assertRegex(self.conf, header)

    def test_supports_websocket_upgrade(self):
        """Next.js の HMR と `/sites/{id}/edit` が WebSocket を使う。"""
        self.assertRegex(self.conf, r"proxy_http_version\s+1\.1\s*;")
        self.assertRegex(self.conf, r"proxy_set_header\s+Upgrade\s+\$http_upgrade\s*;")
        self.assertRegex(self.conf, r"proxy_set_header\s+Connection\s+\$connection_upgrade\s*;")

    def test_body_and_header_limits_match_the_lbs_proxy(self):
        """上限が下流より小さいと、手前で 413 / 502 になって原因が分かりにくい。"""
        self.assertRegex(self.conf, r"client_max_body_size\s+100M\s*;")
        self.assertRegex(
            self.conf,
            r"proxy_buffer_size\s+16k\s*;",
            "NextAuth のセッション Cookie は実測 5.5KB。既定の 4k では 502 になる",
        )

    def test_explains_why_it_is_shaped_this_way(self):
        """採用理由と危険性が設定ファイル自身に残っていること(受入基準4)。"""
        comments = read(VHOST)
        for keyword in ("#1038", "ポート", "起動時", "GitLab", "server.tonoccho.local"):
            with self.subTest(keyword=keyword):
                self.assertIn(keyword, comments)


class SharedHostComposeOverride(unittest.TestCase):
    """ホストを占有できない環境で、reverse-proxy のポート公開だけを取り消す。"""

    def test_override_file_exists(self):
        self.assertTrue(exists(OVERRIDE_COMPOSE), "%s が無い" % OVERRIDE_COMPOSE)

    def test_cancels_the_reverse_proxy_ports(self):
        block = service_block(read(OVERRIDE_COMPOSE), "reverse-proxy")
        self.assertIsNotNone(block, "%s に reverse-proxy サービスが無い" % OVERRIDE_COMPOSE)
        self.assertRegex(
            block,
            r"ports:\s*!override\s*\[\s*\]",
            "ports を `!override []` で打ち消していない。"
            "`ports: []` だけでは compose がリストを**マージ**するため公開が残る",
        )

    def test_the_committed_default_still_publishes_80_and_443(self):
        """既定(ホストを占有できる環境向け)は変えない。共有はオーバーライドの選択に留める。"""
        block = service_block(read(BASE_COMPOSE), "reverse-proxy")
        self.assertIsNotNone(block)
        self.assertIn('"80:80"', block)
        self.assertIn('"443:443"', block)

    def test_override_names_how_to_use_it(self):
        """既存の host-tests / e2e-stubs と同じく、`-f` で明示的に足す規約を書いておく。"""
        text = read(OVERRIDE_COMPOSE)
        self.assertIn("docker-compose.shared-host.yml", text)
        self.assertIn("#1038", text)


class ReverseProxyHealthcheckDetectsIsolation(unittest.TestCase):
    """受入基準3: コンテナ内 loopback だけを見るヘルスチェックでは孤立を検出できない。

    ポート競合で `docker network connect` が失敗すると、コンテナは**どのネットワークにも
    所属しない**まま running になる。それでも `127.0.0.1:80/nginx-health` は 200 を返すため、
    現状のヘルスチェックは healthy を報告し続ける(実測済み)。
    """

    def setUp(self):
        self.block = service_block(read(BASE_COMPOSE), "reverse-proxy")
        self.assertIsNotNone(self.block, "docker-compose.yml に reverse-proxy が無い")
        m = re.search(r"healthcheck:\n(?P<body>(?:\s{6}.*\n?)+)", self.block)
        self.assertIsNotNone(m, "reverse-proxy に healthcheck が無い")
        self.health = m.group("body")

    def test_probes_an_upstream_over_the_docker_network(self):
        """`web` か `gateway` への到達性を条件に含める。孤立時は `bad address` で落ちる。"""
        self.assertTrue(
            re.search(r"\bweb\s+3000\b|web:3000", self.health)
            or re.search(r"\bgateway\s+8080\b|gateway:8080", self.health),
            "ヘルスチェックが上流(web / gateway)への到達性を見ていない:\n%s" % self.health,
        )

    def test_still_checks_nginx_itself(self):
        """上流だけ見ると nginx 自身が死んでいる状態を取りこぼす。両方見ること。"""
        self.assertIn("nginx-health", self.health)

    def test_gives_the_stack_time_to_start(self):
        """厳しくした結果、起動順序の都合で恒常的に unhealthy になっては困る。"""
        m = re.search(r"start_period:\s*(\d+)s", self.health)
        self.assertIsNotNone(m, "start_period が無い。web/gateway の起動を待てない")
        self.assertGreaterEqual(
            int(m.group(1)), 30, "start_period が短すぎ、cold start で unhealthy に倒れる"
        )


class StaticHandler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


class WaitForStackHealthyChecksTheHost(unittest.TestCase):
    """受入基準3: スタックが「全て healthy」でも、ホストから baseURL へ届かなければ OK ではない。

    実測では、reverse-proxy がネットワークから切り離され `https://localhost` が 000 を返す
    状態で、このスクリプトが `OK: 対象サービスは全てhealthyです。exit=0` を返していた。
    """

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), StaticHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_http_only(self, base_url, *extra):
        env = dict(os.environ)
        env["E2E_BASE_URL"] = base_url
        return subprocess.run(
            ["bash", os.path.join(REPO_ROOT, WAIT_SCRIPT), "--http-only", "--http-timeout", "3"]
            + list(extra),
            capture_output=True,
            text=True,
            timeout=120,
            env=env,
            cwd=REPO_ROOT,
        )

    def test_passes_when_the_base_url_answers(self):
        r = self.run_http_only("http://127.0.0.1:%d" % self.port)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_fails_when_the_base_url_is_unreachable(self):
        """**終了コードだけを見てはいけない。**

        `--http-only` / `--http-timeout` が未実装の間、このスクリプトは
        `エラー: 不明な引数 '--http-only'` で exit 1 する。終了コードだけを見ると、
        到達不能を検出したのか引数を知らなかっただけなのかが区別できず、
        production を1行も書いていない時点でこのテストが緑になる
        (#1038 のレビューで実際に起きた。終了コードの偶然の一致だった)。

        そこで失敗の**理由**まで見る。引数解釈で落ちていないこと、かつ到達不能を示す
        メッセージが出ていることの両方を確認する。
        """
        url = "http://127.0.0.1:%d" % free_port()
        r = self.run_http_only(url)
        out = r.stdout + r.stderr
        self.assertNotRegex(
            out,
            r"不明な(引数|オプション)",
            "到達性ではなく引数解釈で落ちている(--http-only / --http-timeout が"
            "未実装の可能性がある):\n" + out,
        )
        self.assertNotEqual(0, r.returncode, "到達できないのに成功した: " + out)
        self.assertRegex(
            out,
            r"到達できません",
            "終了コードは非0だが、到達不能を示すメッセージが無い"
            "(別の理由で落ちている):\n" + out,
        )
        self.assertIn(
            "http_code=000",
            out,
            "実際に %s を叩いた形跡(http_code)が無い:\n%s" % (url, out),
        )

    def test_names_the_port_conflict_as_a_likely_cause(self):
        """原因の見当が付かないと、利用者は「テストが壊れている」と読んでしまう。"""
        r = self.run_http_only("http://127.0.0.1:%d" % free_port())
        out = r.stdout + r.stderr
        self.assertIn("80", out)
        self.assertIn("setup-shared-host-proxy.sh", out)

    def test_can_be_skipped_explicitly(self):
        """docker CLI もホストネットワークも無い環境向けの逃げ道は、明示的であること。"""
        env = dict(os.environ)
        env["E2E_BASE_URL"] = "http://127.0.0.1:%d" % free_port()
        env["E2E_SKIP_HTTP_CHECK"] = "1"
        r = subprocess.run(
            ["bash", os.path.join(REPO_ROOT, WAIT_SCRIPT), "--http-only"],
            capture_output=True,
            text=True,
            timeout=120,
            env=env,
            cwd=REPO_ROOT,
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_documents_the_new_flags(self):
        text = read(WAIT_SCRIPT)
        for flag in ("--http-only", "--skip-http-check", "E2E_BASE_URL"):
            with self.subTest(flag=flag):
                self.assertIn(flag, text)


FAKE_DOCKER = """#!/bin/bash
# テスト用の docker スタブ。呼ばれた引数を $FAKE_DOCKER_LOG へ追記する。
echo "$@" >> "$FAKE_DOCKER_LOG"
case "$*" in
  *"nginx -t"*)
    if [ "${FAKE_NGINX_T_FAILS:-0}" = "1" ]; then
      echo "nginx: [emerg] simulated failure" >&2
      exit 1
    fi
    echo "syntax is ok" >&2
    ;;
  *"nginx -s reload"*)
    if [ "${FAKE_RELOAD_FAILS:-0}" = "1" ]; then
      echo "nginx: [error] simulated reload failure" >&2
      exit 1
    fi
    ;;
  *"network inspect"*)
    if [ "${FAKE_NETWORK_CONNECTED:-0}" = "1" ]; then echo "infra-proxy"; else echo ""; fi
    ;;
esac
exit 0
"""


class SetupScriptBase(unittest.TestCase):
    def setUp(self):
        self.assertTrue(exists(SETUP_SCRIPT), "%s が無い" % SETUP_SCRIPT)
        self.tmp = tempfile.mkdtemp()
        self.infra = os.path.join(self.tmp, "infra")
        os.makedirs(os.path.join(self.infra, "proxy", "conf.d"))
        os.makedirs(os.path.join(self.infra, "proxy", "certs"))
        self.log = os.path.join(self.tmp, "docker.log")
        self.docker = os.path.join(self.tmp, "docker")
        with open(self.docker, "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(self.docker, 0o755)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_setup(self, *args, **env_overrides):
        env = dict(os.environ)
        env.update(
            {
                "INFRA_DIR": self.infra,
                "DOCKER_BIN": self.docker,
                "FAKE_DOCKER_LOG": self.log,
                # 実際の GitLab / localhost を叩かない。空文字は「その確認を省く」の意。
                "GITLAB_HEALTH_URL": "",
                "LBS_BASE_URL": "",
            }
        )
        env.update({k: str(v) for k, v in env_overrides.items()})
        return subprocess.run(
            ["bash", os.path.join(REPO_ROOT, SETUP_SCRIPT)] + list(args),
            capture_output=True,
            text=True,
            timeout=120,
            env=env,
            cwd=REPO_ROOT,
        )

    def docker_calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log, encoding="utf-8") as f:
            return [l.strip() for l in f if l.strip()]

    def placed_files(self):
        out = []
        for sub in ("proxy/conf.d", "proxy/certs"):
            d = os.path.join(self.infra, sub)
            out += [os.path.join(sub, n) for n in sorted(os.listdir(d))]
        return out


class SetupScriptUsage(SetupScriptBase):
    def test_is_executable(self):
        st = os.stat(os.path.join(REPO_ROOT, SETUP_SCRIPT))
        self.assertTrue(st.st_mode & stat.S_IXUSR, "%s に実行ビットが無い" % SETUP_SCRIPT)

    def test_syntax_is_valid(self):
        r = subprocess.run(
            ["bash", "-n", os.path.join(REPO_ROOT, SETUP_SCRIPT)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(0, r.returncode, r.stderr)

    def test_help_exits_zero(self):
        r = self.run_setup("--help")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("--check", r.stdout)

    def test_unknown_option_is_a_usage_error(self):
        r = self.run_setup("--wat")
        self.assertEqual(2, r.returncode, r.stdout + r.stderr)


class SetupScriptCheckMode(SetupScriptBase):
    """`--check` は点検だけを行い、決して書き換えない(#1039 の setup-git-hooks.sh と同じ思想)。

    点検が黙って直してしまうと、「未適用だった」という事実そのものが観測できなくなる。
    """

    def test_check_fails_when_not_applied(self):
        r = self.run_setup("--check")
        self.assertNotEqual(0, r.returncode, "未適用なのに成功した: " + r.stdout)

    def test_check_writes_nothing(self):
        self.run_setup("--check")
        self.assertEqual([], self.placed_files(), "--check が infra 側へ書き込んだ")

    def test_check_names_how_to_apply_it(self):
        r = self.run_setup("--check")
        self.assertIn("setup-shared-host-proxy.sh", r.stdout + r.stderr)

    def test_check_does_not_reload_nginx(self):
        self.run_setup("--check")
        for call in self.docker_calls():
            with self.subTest(call=call):
                self.assertNotIn("nginx -s reload", call)
                self.assertNotIn("network connect", call)

    def test_check_passes_after_applying(self):
        self.run_setup()
        r = self.run_setup("--check", FAKE_NETWORK_CONNECTED="1")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


class SetupScriptApply(SetupScriptBase):
    def test_places_certificate_and_vhost(self):
        r = self.run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        placed = self.placed_files()
        self.assertIn("proxy/conf.d/20-localhost.conf", placed)
        self.assertIn("proxy/certs/localhost.crt", placed)
        self.assertIn("proxy/certs/localhost.key", placed)

    def test_placed_vhost_matches_the_repository(self):
        self.run_setup()
        with open(
            os.path.join(self.infra, "proxy", "conf.d", "20-localhost.conf"), encoding="utf-8"
        ) as f:
            self.assertEqual(read(VHOST), f.read())

    def test_connects_the_proxy_to_the_lbs_network(self):
        self.run_setup()
        self.assertTrue(
            any("network connect" in c for c in self.docker_calls()),
            "lbs-net へ接続していない:\n%s" % "\n".join(self.docker_calls()),
        )

    def test_validates_before_reloading(self):
        """`nginx -t` を通さずに reload すると、設定ミスがそのまま GitLab の停止になる。"""
        calls = self.docker_calls()
        self.run_setup()
        calls = self.docker_calls()
        test_at = [i for i, c in enumerate(calls) if "nginx -t" in c]
        reload_at = [i for i, c in enumerate(calls) if "nginx -s reload" in c]
        self.assertTrue(test_at, "nginx -t を実行していない:\n%s" % "\n".join(calls))
        self.assertTrue(reload_at, "nginx -s reload を実行していない:\n%s" % "\n".join(calls))
        self.assertLess(min(test_at), min(reload_at), "reload が nginx -t より先に走っている")

    def test_is_idempotent(self):
        self.assertEqual(0, self.run_setup().returncode)
        r = self.run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("proxy/conf.d/20-localhost.conf", self.placed_files())


class SetupScriptRollsBackOnInvalidConfig(SetupScriptBase):
    """`nginx -t` が落ちたら reload せず、置いたファイルを撤去する。

    撤去しないと、infra-proxy が次に再起動した瞬間に壊れた設定を読み込み、**GitLab が上がらない**。
    その時点では誰も原因を覚えていない。
    """

    def test_exits_non_zero(self):
        r = self.run_setup(FAKE_NGINX_T_FAILS="1")
        self.assertNotEqual(0, r.returncode, "nginx -t が落ちたのに成功した: " + r.stdout)

    def test_does_not_reload(self):
        self.run_setup(FAKE_NGINX_T_FAILS="1")
        for call in self.docker_calls():
            with self.subTest(call=call):
                self.assertNotIn("nginx -s reload", call)

    def test_removes_the_placed_vhost(self):
        self.run_setup(FAKE_NGINX_T_FAILS="1")
        self.assertNotIn("proxy/conf.d/20-localhost.conf", self.placed_files())


class CountingGitlabHandler(BaseHTTPRequestHandler):
    """`remaining_ok` 回だけ 200 を返し、以降は 503 を返す GitLab の代役。

    「適用前は生きていたのに、復旧後には死んでいる」状態を作るために回数で切り替える。
    """

    remaining_ok = 99

    def do_GET(self):  # noqa: N802
        cls = type(self)
        if cls.remaining_ok > 0:
            cls.remaining_ok -= 1
            self.send_response(200)
        else:
            self.send_response(503)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


class SetupScriptVerifiesRecoveryWhenReloadFails(SetupScriptBase):
    """`nginx -s reload` 自体が落ちたとき、復旧の結果を黙って捨てない。

    `nginx -t` を通過済みなのでこの分岐に来る可能性は低い。しかし来たときこそ、
    infra-proxy は新しい設定を読み込みかけて失敗した直後であり、**GitLab が生きているか
    どうかが分からない**。撤去して古い設定で reload し直したあと、その成否と GitLab の
    生存を確認して記録しなければ、呼び出し元には「exit 1 だった」以上のことが分からない。
    """

    def start_gitlab_stub(self, ok_responses=99):
        handler = type("GitlabStub", (CountingGitlabHandler,), {"remaining_ok": ok_responses})
        server = HTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return "http://127.0.0.1:%d/" % server.server_address[1]

    def run_failing_reload(self, ok_responses=99):
        return self.run_setup(
            FAKE_RELOAD_FAILS="1", GITLAB_HEALTH_URL=self.start_gitlab_stub(ok_responses)
        )

    def test_exits_non_zero(self):
        r = self.run_failing_reload()
        self.assertNotEqual(0, r.returncode, "reload が落ちたのに成功した: " + r.stdout)

    def test_removes_the_placed_vhost(self):
        self.run_failing_reload()
        self.assertNotIn("proxy/conf.d/20-localhost.conf", self.placed_files())

    def test_reports_the_outcome_of_the_recovery_reload(self):
        """撤去したあとの reload の成否を報告する。黙って捨てると、元の設定が読み直された
        のかどうかが誰にも分からないまま exit 1 になる。"""
        r = self.run_failing_reload()
        out = r.stdout + r.stderr
        self.assertRegex(
            out,
            r"復旧の\s*reload",
            "復旧 reload の成否を報告していない:\n" + out,
        )
        self.assertIn(
            "docker restart",
            out,
            "復旧 reload にも失敗したのに、手動で確認する手順を示していない:\n" + out,
        )

    def test_checks_that_gitlab_is_alive_after_recovery(self):
        """書き込み先は GitLab を提供している nginx である。撤去して戻したあと、実際に
        生きているかを確認しないまま終わると、壊したかどうかを後から推測するしかなくなる。"""
        r = self.run_failing_reload()
        out = r.stdout + r.stderr
        self.assertIn("復旧後", out, "復旧後の GitLab 生存確認をしていない:\n" + out)
        self.assertRegex(
            out,
            r"✓ GitLab 生存確認 \(復旧後\)",
            "復旧後に GitLab へ到達できたことが記録されていない:\n" + out,
        )

    def test_says_so_when_gitlab_is_dead_after_recovery(self):
        """生存確認の結果が「死んでいる」ときにこそ、呼び出し元はそれを知る必要がある。"""
        r = self.run_failing_reload(ok_responses=1)
        out = r.stdout + r.stderr
        self.assertRegex(
            out,
            r"✗ GitLab へ到達できません \(復旧後\)",
            "復旧後に GitLab が死んでいることを報告していない:\n" + out,
        )
        self.assertNotEqual(0, r.returncode)


def section(text, heading):
    lines = text.splitlines()
    level = heading.split(" ")[0]
    try:
        start = lines.index(heading)
    except ValueError:
        return None
    for i in range(start + 1, len(lines)):
        if lines[i].startswith(level + " "):
            return "\n".join(lines[start:i])
    return "\n".join(lines[start:])


class Documentation(unittest.TestCase):
    """受入基準4・5: ホストポートの前提と、採らなかった案の理由が文書に残っている。"""

    def setUp(self):
        self.doc = read(DOC)

    def test_has_a_host_port_section(self):
        headings = [l for l in self.doc.splitlines() if l.startswith("## ")]
        self.assertTrue(
            any("ホスト" in h and ("ポート" in h or "80" in h) for h in headings),
            "ホストポート前提の節が無い。見出し一覧:\n  %s" % "\n  ".join(headings),
        )

    def test_names_the_diagnostic_command(self):
        self.assertIn("curl -sk -o /dev/null -w '%{http_code}' https://localhost/", self.doc)

    def test_names_the_setup_script(self):
        self.assertIn("scripts/setup-shared-host-proxy.sh", self.doc)
        self.assertIn("setup-shared-host-proxy.sh --check", self.doc)

    def test_records_why_option_b_was_rejected(self):
        """baseURL 可変化を採らない理由。トークンの `iss` まで変わることが要点。"""
        for keyword in ("NEXTAUTH_URL", "KC_HOSTNAME", "redirect_uri", "APP_WEB_BASE_URL"):
            with self.subTest(keyword=keyword):
                self.assertIn(keyword, self.doc, "案Bを採らない理由に %s の記述が無い" % keyword)

    def test_records_why_option_c_was_rejected(self):
        """infra-proxy 停止案。GitLab が止まり `glab` が使えなくなる。"""
        self.assertIn("glab", self.doc)
        self.assertRegex(self.doc, r"infra-proxy\s*(を)?停止")

    def test_mentions_the_compose_override(self):
        self.assertIn("docker-compose.shared-host.yml", self.doc)


if __name__ == "__main__":
    unittest.main()
