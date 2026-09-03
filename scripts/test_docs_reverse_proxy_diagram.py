#!/usr/bin/env python3
"""ドキュメントのサブパス構成図が nginx の実態と一致することを検証する(#1010)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

この図は「**どの経路が外部に開いているか**」を読者に伝えるものである。実態とずれると
公開面の理解を誤らせる。#979 は同じ種類の食い違い（ドキュメントが「非公開」と書いて
いる経路を nginx が無認証で公開していた）を是正した Issue であり、#1010 はその残りに
あたる。

図は手で書かれ、`nginx.conf` は別に編集される。**両者が同期している保証は無い**。
実際 `/ollama` は削除済みのコンテナを指し続け、`/comfyui` は #979 で location を
消した後も図に残り、`/api` の中継先は存在しないサービス名 `api` を指していた。

そこで「図に載っている経路が nginx に実在すること」を検査する。逆方向（nginx にある
のに図に無い）は**検査しない** — 図は要約であり、内部向けの `location`（`/nginx-health`
など）まで載せる必要は無いためである。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
NGINX = os.path.join(REPO_ROOT, "infra/nginx/conf.d/default.conf")
DOC = os.path.join(REPO_ROOT, "docs/penpot-infrastructure.md")

# 図の行から経路を拾う。`├─ /penpot  ← ...` のような行。
DIAGRAM_PATH = re.compile(r"^[│├└─\s]*(/[a-z0-9_{}/-]*)\s")


def nginx_locations():
    """`location` ディレクティブが受け持つパスの集合(正規表現のものは前方一致で近似)。"""
    with open(NGINX, encoding="utf-8") as f:
        text = f.read()
    paths = set()
    for m in re.finditer(r"^\s*location\s+(=\s*)?(~\s*\^?)?([^\s{]+)", text, re.M):
        raw = m.group(3)
        # 正規表現 location はプレフィックスだけ取る(`^/sites/[0-9]+/edit$` → `/sites/`)
        prefix = re.split(r"[\[(\\]", raw)[0].rstrip("^$")
        if prefix.startswith("/"):
            paths.add(prefix.rstrip("/") or "/")
    return paths


def diagram_paths():
    """構成図のブロック内に現れる経路。"""
    with open(DOC, encoding="utf-8") as f:
        lines = f.read().splitlines()
    found, inside = [], False
    for line in lines:
        if line.strip().startswith("```"):
            inside = not inside
            continue
        if not inside:
            continue
        if "←" not in line:
            continue
        m = DIAGRAM_PATH.match(line)
        if m:
            found.append((m.group(1).rstrip("/") or "/", line.strip()))
    return found


class DiagramMatchesNginx(unittest.TestCase):
    def test_every_diagrammed_path_exists_in_nginx(self):
        available = nginx_locations()
        offenders = [
            "%s   (%s)" % (path, line[:70])
            for path, line in diagram_paths()
            if path not in available
        ]
        self.assertEqual(
            [],
            offenders,
            "図にあるが nginx に存在しない経路:\n  " + "\n  ".join(offenders),
        )

    def test_removed_paths_are_not_presented_as_live(self):
        """削除済みの経路が、現行の中継先として載っていないこと。

        `/ollama` はコンテナごと存在しない。`/comfyui` は #979 で location を削除した。
        言及自体は禁じない（「#979 で削除済み」と書くのは正しい）が、
        **矢印付きの中継先として**載っていてはいけない。
        """
        removed = ("/ollama", "/comfyui")
        offenders = [
            "%s   (%s)" % (path, line[:70])
            for path, line in diagram_paths()
            if path in removed
        ]
        self.assertEqual(
            [], offenders, "削除済みの経路が現行として載っている:\n  " + "\n  ".join(offenders)
        )

    def test_api_points_at_the_gateway(self):
        """`/api` の中継先が `gateway` であること。

        `api` という名前のサービスは docker-compose.yml に存在しない。
        """
        for path, line in diagram_paths():
            if path == "/api":
                self.assertIn("gateway", line, "/api の中継先が gateway でない: %s" % line)
                self.assertNotRegex(
                    line, r"→\s*api[:\s]", "/api の中継先に存在しないサービス名 api がある"
                )
                return
        self.fail("構成図に /api の行が見つからない")


class DocumentPositioning(unittest.TestCase):
    def test_the_document_states_whether_it_is_current_or_historical(self):
        """設計記録か現行構成かが冒頭で分かること。

        分からないと、読者は図の食い違いを「古い記録だから」と流すか、
        「現行のはずなのに違う」と混乱するかのどちらかになる。
        """
        with open(DOC, encoding="utf-8") as f:
            head = "\n".join(f.read().splitlines()[:30])
        self.assertTrue(
            any(w in head for w in ("現行", "設計記録", "当時", "この文書は")),
            "文書の位置づけ(設計記録か現行構成か)が冒頭で分からない",
        )


class ParserSanity(unittest.TestCase):
    """検査そのものが空振りしていないこと。"""

    def test_nginx_locations_are_found(self):
        got = nginx_locations()
        for expected in ("/api", "/penpot", "/drawio", "/phpmyadmin"):
            with self.subTest(path=expected):
                self.assertIn(expected, got)

    def test_diagram_paths_are_found(self):
        self.assertTrue(diagram_paths(), "構成図から経路を1つも抽出できていない")


if __name__ == "__main__":
    unittest.main()
