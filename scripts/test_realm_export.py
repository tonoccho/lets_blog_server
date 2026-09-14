#!/usr/bin/env python3
"""`realm-export.json` の `description` が255文字を超えないことの検証(#1278)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

Keycloak(PostgreSQL バックエンド)は `description` を `character varying(255)` の列に
格納する。`infra/keycloak/realm-export.json` にこれを超える `description` が混入すると、
ボリュームが空の状態からの初回インポート(`--import-realm`)が
`ERROR: value too long for type character varying(255)` でクラッシュループする。

一度ボリュームが出来上がった環境ではこの `description` は初回インポート時にしか
評価されないため、`ACCEPTANCE_RESET=1` によるゼロからの再構築や新規clone後の
初回セットアップを実際に踏むまで症状が出ない。#1278 はこれを静的に先回りする。

## なぜ Gherkin ではないのか

これは Keycloak の realm 定義という設定ファイルの制約であり、Web UI から到達できる
振る舞いではない。`CLAUDE.md` → **Test-First Implementation** が明示的に認める
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルのテストで
表現する」という文書化された例外として、ここで表現する。

## 何を検査するか

`realm-export.json` 全体を再帰的に走査し、キー名が `description` である全ての文字列値
(realm 直下・clients・clientScopes・roles など、ネストの深さを問わない)が255文字以下
であることを検査する。超過があれば、その JSON パス(例: `clients[4].description`)を
テスト失敗メッセージに含める。
"""

import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
REALM_EXPORT = os.path.join(REPO_ROOT, "infra", "keycloak", "realm-export.json")

MAX_DESCRIPTION_LENGTH = 255


def find_long_descriptions(obj, path="root", max_length=MAX_DESCRIPTION_LENGTH):
    """`description` キーを持つ文字列値のうち、max_length を超えるものを
    (json_path, length) のリストで返す。ネストの深さを問わず再帰的に走査する。
    """
    violations = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            child_path = f"{path}.{key}"
            if key == "description" and isinstance(value, str) and len(value) > max_length:
                violations.append((child_path, len(value)))
            violations.extend(find_long_descriptions(value, child_path, max_length))
    elif isinstance(obj, list):
        for index, item in enumerate(obj):
            violations.extend(find_long_descriptions(item, f"{path}[{index}]", max_length))
    return violations


class RealmExportDescriptionLengthTest(unittest.TestCase):
    def test_all_descriptions_are_at_most_255_chars(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            data = json.load(f)

        violations = find_long_descriptions(data)

        self.assertEqual(
            violations,
            [],
            "255文字を超える description がある(JSONパス, 長さ): "
            f"{violations}",
        )


if __name__ == "__main__":
    unittest.main()
