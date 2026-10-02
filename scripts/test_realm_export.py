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
import re
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


class RealmExportClientSecretsComeFromEnvironmentTest(unittest.TestCase):
    """#1551: クライアントシークレットは import 時に環境変数から受け取る。

    setup.sh が .env に書いたランダム値と realm の値を一致させるための経路。
    既定値(.env.example と同じ dev-only-*)は未設定時のフォールバックとして残す
    (単独起動の verify-clean-realm-import.sh と、.env をコピーしただけの環境のため)。
    Web UI から到達できない設定なので Gherkin ではなくここで表現する。
    """

    CLIENTS = {
        "letsblog-services": "KEYCLOAK_SERVICES_CLIENT_SECRET",
        "letsblog-web": "KEYCLOAK_WEB_CLIENT_SECRET",
    }

    def setUp(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            self.realm = json.load(f)
        self.example = {}
        with open(os.path.join(REPO_ROOT, ".env.example"), encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    self.example[k] = v

    def test_secrets_are_env_placeholders_defaulting_to_the_example_values(self):
        by_id = {c["clientId"]: c for c in self.realm["clients"]}
        for client_id, var in self.CLIENTS.items():
            with self.subTest(client=client_id):
                self.assertEqual(
                    "${%s:%s}" % (var, self.example[var]), by_id[client_id]["secret"]
                )

    def test_compose_passes_both_secrets_to_the_keycloak_service(self):
        with open(os.path.join(REPO_ROOT, "docker-compose.yml"), encoding="utf-8") as f:
            text = f.read()
        start = text.index("\n  keycloak:\n")
        m = re.search(r"\n  [a-z][a-z0-9-]*:\n", text[start + 1 :])
        block = text[start : start + 1 + m.start()] if m else text[start:]
        for var in self.CLIENTS.values():
            with self.subTest(var=var):
                self.assertIn("%s: ${%s}" % (var, var), block)


class RealmExportOfflineSessionPolicyTest(unittest.TestCase):
    """offline session の寿命方針(#1100)。Web UI から到達できない realm 設定のため、
    Gherkin ではなくここで表現する(上記 docstring の文書化された例外)。
    """

    OFFLINE_MAX_LIFESPAN = 14 * 24 * 60 * 60  # 14日

    def setUp(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            self.realm = json.load(f)

    def test_offline_session_has_a_14_day_cap(self):
        self.assertIs(self.realm["offlineSessionMaxLifespanEnabled"], True)
        self.assertEqual(self.realm["offlineSessionMaxLifespan"], self.OFFLINE_MAX_LIFESPAN)

    def test_idle_timeout_does_not_exceed_the_cap(self):
        # 上限より長い idle は効かない値であり、設定の意図を読み違えさせる。
        self.assertLessEqual(self.realm["offlineSessionIdleTimeout"], self.OFFLINE_MAX_LIFESPAN)

    def test_idle_timeout_still_outlasts_a_working_session(self):
        # #1098: 30分の無操作で切れない利用感を再発させない。
        self.assertGreater(self.realm["offlineSessionIdleTimeout"], self.realm["ssoSessionIdleTimeout"])

    def test_refresh_token_rotation_stays_disabled(self):
        # 複数ウィンドウからの同時リフレッシュで正規端末が弾かれうるため無効のまま(ユーザー判断、#1100)。
        self.assertIs(self.realm["revokeRefreshToken"], False)


if __name__ == "__main__":
    unittest.main()
