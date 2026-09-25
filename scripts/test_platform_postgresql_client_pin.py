#!/usr/bin/env python3
"""platform-service の postgresql-client が、リストア先サーバーと同じメジャー版であることを検証する(#1141)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

`services/platform/Dockerfile` が入れる `pg_dump` / `pg_restore` は、
`docker-compose.yml` の `keycloak-postgres` に対して実行される。この2つは**別々のファイルで
別々に編集され、両者が同期している保証が無い**。

#1141 がまさにそれだった。ベースイメージ(`eclipse-temurin:21-jre`、Ubuntu 26.04)の標準
リポジトリには `postgresql-client` v18 しか無く、バージョン無指定で入れると v18 が入る。
`pg_restore` v18 はリストア時に無条件で `SET transaction_timeout = 0;` を発行するが、これは
PostgreSQL 17 で追加された GUC であり、`postgres:15` に固定された `keycloak-postgres` は
これを認識せずエラーになる。結果として `POST /api/backup/restore` が 500 を返していた。

**症状はビルド時にも起動時にも出ない。** イメージは正常にビルドされ、コンテナは healthy に
なり、バックアップの**作成**は成功する。壊れているのは復元だけで、それが分かるのは
実際に復元を試した瞬間である。だからここで、クライアントとサーバーのメジャー版の一致を
宣言のレベルで検査する。

この検査が守るのは「pin されていること」ではなく「**pin 先がサーバーと一致していること**」で
ある。将来 `keycloak-postgres` を上げるときは、この検査が Dockerfile 側の追随漏れを指摘する。

実際に復元が通ることまではここでは見ない。それは受け入れテスト(#1157、AT-14-6)の担当で、
稼働中のスタックに対して実際に復元を実行して確かめる。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
DOCKERFILE = os.path.join(REPO_ROOT, "services/platform/Dockerfile")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def keycloak_postgres_major():
    """docker-compose.yml の keycloak-postgres が使う postgres のメジャー版を返す。"""
    compose = read(COMPOSE)
    m = re.search(
        r"^  keycloak-postgres:\s*$(.*?)(?=^  \S|\Z)", compose, re.M | re.S
    )
    if not m:
        return None
    image = re.search(r"^\s+image:\s*postgres:(\d+)", m.group(1), re.M)
    return image.group(1) if image else None


def installed_packages():
    """Dockerfile の apt-get install が入れるパッケージ名を集める。

    複数行に折り返された RUN 行をつなげてから拾う。**1つの RUN の中に
    `apt-get install` が複数回現れる**ことがあるので(リポジトリを足してから
    2回目を実行する形)、最初の1つだけを見てはいけない。

    そこで先に `&&` で区切ってから、`apt-get install` で始まる区間だけを見る。
    行全体に対する `finditer` では、1件目のマッチが行末まで消費してしまい
    同じ行の2件目に進めない。"""
    text = read(DOCKERFILE).replace("\\\n", " ")
    names = []
    for segment in re.split(r"&&|;|\n", text):
        m = re.match(r"\s*apt-get\s+install\b(.*)", segment)
        if not m:
            continue
        for token in m.group(1).split():
            if token.startswith("-"):
                continue
            names.append(token)
    return names


class PostgresqlClientPin(unittest.TestCase):
    def test_compose_pins_keycloak_postgres_major(self):
        """前提: keycloak-postgres のメジャー版が compose から読み取れる。"""
        self.assertIsNotNone(
            keycloak_postgres_major(),
            "docker-compose.yml の keycloak-postgres から postgres のメジャー版を読み取れない。"
            "image を postgres:<major> の形にすること。",
        )

    def test_client_major_matches_server(self):
        """pg_restore のメジャー版が、リストア先サーバーのメジャー版と一致する。"""
        major = keycloak_postgres_major()
        self.assertIn(
            "postgresql-client-%s" % major,
            installed_packages(),
            "services/platform/Dockerfile が postgresql-client-%s を入れていない。\n"
            "docker-compose.yml の keycloak-postgres は postgres:%s なので、"
            "pg_dump/pg_restore も同じメジャー版に揃えること(#1141)。\n"
            "入っているパッケージ: %s" % (major, major, installed_packages()),
        )

    def test_unversioned_client_is_not_installed(self):
        """バージョン無指定の postgresql-client を入れない。

        ベースイメージの標準リポジトリから v18 が入り、pin が無意味になるため。"""
        self.assertNotIn(
            "postgresql-client",
            installed_packages(),
            "services/platform/Dockerfile がバージョン無指定の postgresql-client を入れている。\n"
            "ベースイメージ(Ubuntu 26.04)の標準リポジトリからは v18 が入るため、"
            "keycloak-postgres(postgres:15)へのリストアが失敗する(#1141)。",
        )


if __name__ == "__main__":
    unittest.main()
