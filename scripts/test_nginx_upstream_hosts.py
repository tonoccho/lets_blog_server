#!/usr/bin/env python3
"""reverse-proxy の中継先が実在することを検証する(#1012)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

`infra/nginx/conf.d/default.conf` は中継先を `set $upstream_<name> <host>:<port>;` という
**変数**に入れてから `proxy_pass` している。nginx は変数を使った宛先を設定読み込み時では
なく**リクエストごとに**名前解決するため、宛先が存在しないホスト名でも `nginx -t` は通り、
コンテナも healthy のまま起動する。

#1012 がまさにそれだった。`location /penpot` の宛先は `penpot:80` だったが、
`docker-compose.yml` に `penpot` というサービスは無く(実在するのは `penpot-frontend`)、
`aliases:` の定義も1つも無い。誤りが表に出るのは利用者がアクセスした瞬間だけで、
症状は 502 という「上流が落ちている」ようにしか見えない形で現れる。

`default.conf` と `docker-compose.yml` は別々に編集され、**両者が同期している保証は無い**。
そこで「中継先のホスト名が compose のサービス名かネットワークエイリアスとして実在すること」
を検査する。

ポートの誤り(`penpot-frontend:80`。実際の listen は 8080)まではここでは見ない。
compose の宣言だけからは、ポートを公開していないサービスが何番で listen しているか
決められないためである。そちらは受け入れテスト
(`apps/web/e2e/features/cross-cutting/penpot-routing.feature` の
「reverse-proxy の全ての中継先へ到達できる」)が稼働中のコンテナへ実際に TCP 接続して見る。

## SECURITY.md との対応

`SECURITY.md` の「Paths the reverse proxy forwards outside that gate」表は、アプリの
認証ゲートを通らない経路の一覧である。同文書自身が「reverse proxy に追加した経路で
`web` / `gateway` で終わらないものはこの表に載る」と定めているので、その対応も検査する。
表の行が指す経路が**実在しない中継先**を指していれば、その行は実態と一致していない。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
NGINX = os.path.join(REPO_ROOT, "infra/nginx/conf.d/default.conf")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
SECURITY = os.path.join(REPO_ROOT, "SECURITY.md")

# `set $upstream_<name> <host>:<port>;`
UPSTREAM = re.compile(r"set\s+\$upstream_[a-z0-9_]+\s+([a-z0-9._-]+):(\d+)\s*;", re.I)

# `location [= ][~ ^]<path> {`
LOCATION = re.compile(r"^\s*location\s+(=\s*)?(~\s*\^?)?([^\s{]+)", re.M)

# `services:` 配下のインデント2のキー。test_compose_env_contract.py と同じ切り方。
COMPOSE_SERVICE = re.compile(r"^  ([a-z][a-z0-9_-]*):\s*$", re.M)

# ネットワークエイリアス。`aliases:` の直後に続く `- name` の並び。
COMPOSE_ALIAS_BLOCK = re.compile(r"^(\s*)aliases:\s*$((?:\n\1\s+-\s*\S+)*)", re.M)

# SECURITY.md の表の行。`| `/penpot` | Penpot | ... |`
SECURITY_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*([^|]+?)\s*\|", re.M)

# `location = <path> { ... return 301 <target>; ... }` — 完全一致ロケーションの301リダイレクト。
EXACT_REDIRECT = re.compile(
    r"location\s*=\s*(?P<path>\S+)\s*\{\s*return\s+301\s+(?P<target>\S+?);\s*\}", re.M
)

# 中継先がアプリ自身(認証ゲートの内側)であるもの。SECURITY.md の表の対象外。
APP_UPSTREAMS = {"web", "gateway"}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def normalize(path):
    """`/penpot/` と `/penpot` を同じものとして扱う。"""
    return path.rstrip("/") or "/"


def upstream_targets():
    """`default.conf` に現れる中継先 (host, port) の一覧(出現順、重複除去)。"""
    seen, targets = set(), []
    for host, port in UPSTREAM.findall(read(NGINX)):
        if (host, port) not in seen:
            seen.add((host, port))
            targets.append((host, port))
    return targets


def location_upstreams():
    """`location` のパス -> その中の中継先ホスト名。中継しない location は現れない。

    location ブロックの入れ子は無いので、次の `location` までを1ブロックとみなす。
    """
    text = read(NGINX)
    starts = [(m.start(), m.group(3)) for m in LOCATION.finditer(text)]
    found = {}
    for i, (pos, raw) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        # 正規表現 location はプレフィックスだけ取る(`^/sites/[0-9]+/edit$` → `/sites/`)
        prefix = re.split(r"[\[(\\]", raw)[0].rstrip("^$")
        if not prefix.startswith("/"):
            continue
        hosts = {host for host, _ in UPSTREAM.findall(text[pos:end])}
        if hosts:
            found.setdefault(normalize(prefix), set()).update(hosts)
    return found


def compose_names():
    """compose のサービス名とネットワークエイリアスの和集合。"""
    text = read(COMPOSE)
    names = set(COMPOSE_SERVICE.findall(text))
    for _, block in COMPOSE_ALIAS_BLOCK.findall(text):
        names.update(re.findall(r"-\s*(\S+)", block))
    return names


def security_table_paths():
    """SECURITY.md の表の1列目に現れる経路(正規化済み)。

    表は経路をパターンで書く(`/sites/<slug>/**`)。`location` 側はプレフィックスなので、
    可変部分(`<...>` や `*`)より手前で切って突き合わせる。
    """
    paths = set()
    for path, _ in SECURITY_ROW.findall(read(SECURITY)):
        if not path.startswith("/"):
            continue
        paths.add(normalize(re.split(r"[<*]", path)[0]))
    return paths


class UpstreamHostsExist(unittest.TestCase):
    def test_every_upstream_host_is_a_real_service_or_alias(self):
        """全ての `set $upstream_*` の宛先が compose に実在すること(#1012 の受入基準)。"""
        known = compose_names()
        offenders = [
            "%s:%s" % (host, port)
            for host, port in upstream_targets()
            if host not in known
        ]
        self.assertEqual(
            [],
            offenders,
            "docker-compose.yml に存在しないホスト名を中継先にしている(リクエスト時まで"
            "検出されず 502 になる):\n  " + "\n  ".join(offenders),
        )


class ExactRedirectsPreserveQueryString(unittest.TestCase):
    def test_bare_path_redirect_keeps_query_string(self):
        """`location = <path>` の301が、末尾スラッシュ付きへ寄せる際にクエリ文字列を保つこと。

        `return 301 /penpot/;` は元のリクエストの `?...` を一切引き継がない
        (`https://.../penpot?foo=bar` は `https://.../penpot/` に落ちて `foo=bar` が消える)。
        `$is_args$args` を含む形でなければ、ディープリンクのクエリパラメータが
        リダイレクト1回で失われる。
        """
        offenders = [
            "%s -> %s" % (path, target)
            for path, target in EXACT_REDIRECT.findall(read(NGINX))
            if "$is_args$args" not in target and "$args" not in target
        ]
        self.assertEqual(
            [],
            offenders,
            "完全一致ロケーションの301リダイレクトがクエリ文字列を落としている"
            "($is_args$args が無い):\n  " + "\n  ".join(offenders),
        )


class SecurityDocMatchesNginx(unittest.TestCase):
    def test_third_party_upstreams_point_at_real_services(self):
        """表に載っている経路の中継先が実在すること。

        表は「この経路の先には何があり、何がそれを守るか」を読者に伝える。中継先が
        存在しないホスト名なら、その行が説明している経路は成立していない。
        """
        known = compose_names()
        documented = security_table_paths()
        offenders = []
        for path, hosts in location_upstreams().items():
            if path not in documented:
                continue
            for host in sorted(hosts):
                if host not in known:
                    offenders.append("%s → %s" % (path, host))
        self.assertEqual(
            [],
            offenders,
            "SECURITY.md が載せている経路が実在しない中継先を指している:\n  "
            + "\n  ".join(offenders),
        )

    def test_every_third_party_path_is_documented(self):
        """`web` / `gateway` で終わらない中継経路が表に載っていること。

        SECURITY.md 自身が「A path added to the reverse proxy that does not terminate in
        `web` or `gateway` belongs in this table」と定めている。
        """
        documented = security_table_paths()
        offenders = [
            "%s → %s" % (path, ", ".join(sorted(hosts)))
            for path, hosts in sorted(location_upstreams().items())
            if not hosts <= APP_UPSTREAMS and path not in documented
        ]
        self.assertEqual(
            [],
            offenders,
            "認証ゲートを通らない中継経路が SECURITY.md の表に無い:\n  "
            + "\n  ".join(offenders),
        )


class ParserSanity(unittest.TestCase):
    """検査そのものが空振りしていないこと。"""

    def test_upstreams_are_found(self):
        hosts = {host for host, _ in upstream_targets()}
        for expected in ("web", "gateway", "keycloak"):
            with self.subTest(host=expected):
                self.assertIn(expected, hosts)

    def test_compose_names_are_found(self):
        names = compose_names()
        for expected in ("web", "gateway", "penpot-frontend"):
            with self.subTest(service=expected):
                self.assertIn(expected, names)

    def test_location_upstreams_are_found(self):
        found = location_upstreams()
        self.assertIn("/penpot", found)
        self.assertIn("/phpmyadmin", found)
        self.assertEqual({"drawio"}, found.get("/drawio"))

    def test_security_table_paths_are_found(self):
        paths = security_table_paths()
        for expected in ("/penpot", "/phpmyadmin", "/drawio"):
            with self.subTest(path=expected):
                self.assertIn(expected, paths)


if __name__ == "__main__":
    unittest.main()
