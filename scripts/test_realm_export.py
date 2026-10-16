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


class RealmExportExtensionsClientsTest(unittest.TestCase):
    """#1805: extensions(GitLab・Penpot・proxy)の OIDC クライアント。

    リダイレクト URI は extensions の BASE_DOMAIN / HTTPS_PORT に当たる環境変数から import 時に作り、
    secret は `${VAR:既定値}`。Web UI から到達できない realm 設定なのでここで表現する。
    """

    CLIENTS = {
        "lbx-gitlab": ("KEYCLOAK_GITLAB_CLIENT_SECRET", ["gitlab"]),
        "lbx-penpot": ("KEYCLOAK_PENPOT_CLIENT_SECRET", ["penpot"]),
        "lbx-proxy": ("KEYCLOAK_PROXY_CLIENT_SECRET", ["portal", "comfyui"]),
    }

    def setUp(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            self.realm = json.load(f)
        self.by_id = {c["clientId"]: c for c in self.realm["clients"]}
        self.example = {}
        with open(os.path.join(REPO_ROOT, ".env.example"), encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    self.example[k] = v

    def test_three_confidential_standard_flow_clients_exist(self):
        for client_id in self.CLIENTS:
            with self.subTest(client=client_id):
                c = self.by_id[client_id]
                self.assertIs(c["publicClient"], False)
                self.assertIs(c["standardFlowEnabled"], True)
                self.assertIs(c["serviceAccountsEnabled"], False)

    def test_secrets_are_env_placeholders_defaulting_to_the_example_values(self):
        for client_id, (var, _hosts) in self.CLIENTS.items():
            with self.subTest(client=client_id):
                self.assertEqual("${%s:%s}" % (var, self.example[var]), self.by_id[client_id]["secret"])

    def test_redirect_uris_come_in_with_port_and_without_port_forms(self):
        """既定ポート(443)ではブラウザが `:443` を付けないため、両形式を登録する。"""
        for client_id, (_var, hosts) in self.CLIENTS.items():
            with self.subTest(client=client_id):
                uris = self.by_id[client_id]["redirectUris"]
                self.assertEqual(2 * len(hosts), len(uris))
                self.assertEqual(len(uris), len(set(uris)))
                for host in hosts:
                    base = r"https://%s\.\$\{LBX_BASE_DOMAIN:[^}]+\}" % host
                    with_port = [u for u in uris if re.match(base + r":\$\{LBX_HTTPS_PORT:[^}]+\}/", u)]
                    without_port = [u for u in uris if re.match(base + r"/", u)]
                    self.assertEqual(1, len(with_port), uris)
                    self.assertEqual(1, len(without_port), uris)
                    # 同じパスであること
                    self.assertEqual(
                        with_port[0].split("}/", 1)[1], without_port[0].split("}/", 1)[1]
                    )

    def test_env_example_documents_the_redirect_uri_variables(self):
        with open(os.path.join(REPO_ROOT, ".env.example"), encoding="utf-8") as f:
            text = f.read()
        self.assertRegex(text, r"(?m)^# LBX_BASE_DOMAIN=localhost$")
        self.assertRegex(text, r"(?m)^# LBX_HTTPS_PORT=443$")

    def test_readme_states_the_first_import_without_overlay_uses_public_secrets(self):
        with open(os.path.join(REPO_ROOT, "infra", "keycloak", "README.md"), encoding="utf-8") as f:
            text = f.read()
        self.assertIn("重ねない構成で初回 import", text)
        self.assertIn("dev-only", text)

    def test_proxy_client_puts_realm_roles_in_a_roles_claim_for_the_admin_check(self):
        mappers = self.by_id["lbx-proxy"].get("protocolMappers") or []
        role_mappers = [m for m in mappers if m["protocolMapper"] == "oidc-usermodel-realm-role-mapper"]
        self.assertEqual(1, len(role_mappers))
        cfg = role_mappers[0]["config"]
        self.assertEqual("roles", cfg["claim.name"])
        self.assertEqual("true", cfg["multivalued"])
        self.assertEqual("true", cfg["access.token.claim"])
        self.assertEqual("true", cfg["id.token.claim"])
        self.assertIn("admin", [r["name"] for r in self.realm["roles"]["realm"]])

    def test_gitlab_and_penpot_are_not_role_restricted(self):
        for client_id in ("lbx-gitlab", "lbx-penpot"):
            with self.subTest(client=client_id):
                mappers = self.by_id[client_id].get("protocolMappers") or []
                self.assertEqual([], [m for m in mappers if "role" in m["protocolMapper"]])

    def test_existing_clients_are_untouched(self):
        for client_id in ("letsblog-web", "letsblog-services"):
            self.assertIn(client_id, self.by_id)
        self.assertEqual(
            ["https://localhost/api/auth/callback/keycloak"], self.by_id["letsblog-web"]["redirectUris"]
        )


class RealmExportToolsClientTest(unittest.TestCase):
    """#1804: lets_blog から extensions のツール(Ollama・ComfyUI・PlantUML・ポータル)を呼ぶための専用クライアント。

    `letsblog-services` のサービスアカウントは `realm-management` の管理ロールを持つので、
    外部へ送るトークンには使えない。専用クライアントはロールを一切持たず、audience を固定する。
    Web UI から到達できない realm / compose の設定なのでここで表現する
    (ロールが無いことの実測は受け入れテスト `@api` の `auth/tools-client-token.feature`)。
    """

    CLIENT_ID = "letsblog-tools-client"
    VAR = "KEYCLOAK_TOOLS_CLIENT_SECRET"
    AUDIENCE = "lbx-tools"

    def setUp(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            self.realm = json.load(f)
        self.by_id = {c["clientId"]: c for c in self.realm["clients"]}
        self.example = {}
        with open(os.path.join(REPO_ROOT, ".env.example"), encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    self.example[k] = v

    def _client(self):
        self.assertIn(self.CLIENT_ID, self.by_id)
        return self.by_id[self.CLIENT_ID]

    def test_client_is_a_confidential_service_account_only_client(self):
        c = self._client()
        self.assertIs(c["enabled"], True)
        self.assertIs(c["publicClient"], False)
        self.assertEqual("client-secret", c["clientAuthenticatorType"])
        self.assertIs(c["serviceAccountsEnabled"], True)
        self.assertIs(c["standardFlowEnabled"], False)
        self.assertIs(c["directAccessGrantsEnabled"], False)
        self.assertIs(c["implicitFlowEnabled"], False)
        self.assertEqual([], c["redirectUris"])

    def test_secret_is_an_env_placeholder_defaulting_to_the_example_value(self):
        self.assertEqual("${%s:%s}" % (self.VAR, self.example[self.VAR]), self._client()["secret"])
        self.assertTrue(self.example[self.VAR].startswith("dev-only-"))

    def test_access_token_carries_the_fixed_audience(self):
        mappers = [
            m for m in self._client().get("protocolMappers") or []
            if m["protocolMapper"] == "oidc-audience-mapper"
        ]
        self.assertEqual(1, len(mappers))
        cfg = mappers[0]["config"]
        self.assertEqual(self.AUDIENCE, cfg["included.custom.audience"])
        self.assertEqual("true", cfg["access.token.claim"])

    def test_client_cannot_receive_any_realm_or_realm_management_role(self):
        c = self._client()
        # fullScope を切ると、ユーザー(サービスアカウント)の持つロールはスコープに入らない限りトークンに載らない。
        self.assertIs(c["fullScopeAllowed"], False)
        # `roles` スコープ(realm_access / resource_access のマッパー)を付けない。
        self.assertNotIn("roles", c["defaultClientScopes"])
        self.assertNotIn("roles", c["optionalClientScopes"])
        # このクライアントへのロールのスコープ割り当てが無い。
        mappings = self.realm.get("clientScopeMappings", {})
        for client, entries in mappings.items():
            for entry in entries:
                self.assertNotEqual(self.CLIENT_ID, entry.get("client"), client)
        # サービスアカウントのユーザーを realm に明示して、ロールを持たせない。
        users = [u for u in self.realm.get("users", []) if u.get("serviceAccountClientId") == self.CLIENT_ID]
        for u in users:
            self.assertFalse(u.get("realmRoles"))
            self.assertFalse(u.get("clientRoles"))

    def test_realm_default_role_does_not_include_the_application_roles(self):
        default = next(r for r in self.realm["roles"]["realm"] if r["name"] == "default-roles-letsblog")
        for role in ("admin", "editor", "viewer"):
            self.assertNotIn(role, default["composites"]["realm"])
        self.assertNotIn("realm-management", default["composites"].get("client", {}))

    def test_existing_services_client_is_untouched(self):
        c = self.by_id["letsblog-services"]
        self.assertEqual(
            "${KEYCLOAK_SERVICES_CLIENT_SECRET:%s}" % self.example["KEYCLOAK_SERVICES_CLIENT_SECRET"], c["secret"]
        )

    def _block(self, service):
        with open(os.path.join(REPO_ROOT, "docker-compose.yml"), encoding="utf-8") as f:
            text = f.read()
        start = text.index("\n  %s:\n" % service)
        m = re.search(r"\n  [a-z][a-z0-9-]*:\n", text[start + 1 :])
        return text[start : start + 1 + m.start()] if m else text[start:]

    def test_compose_passes_the_secret_to_keycloak_and_to_ai_media_and_platform(self):
        for service in ("keycloak", "ai", "media", "platform"):
            with self.subTest(service=service):
                self.assertIn("%s: ${%s}" % (self.VAR, self.VAR), self._block(service))

    def test_compose_does_not_pass_the_secret_to_services_that_do_not_call_the_tools(self):
        for service in ("content", "project", "publishing", "identity", "analytics", "web", "gateway"):
            with self.subTest(service=service):
                self.assertNotIn(self.VAR, self._block(service))

    def test_documents_the_kcadm_procedure_without_running_it(self):
        for rel in ("infra/keycloak/README.md", "docs/KEYCLOAK_CLIENT_SECRET_ROTATION.md"):
            with self.subTest(doc=rel):
                with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
                    text = f.read()
                self.assertIn(self.CLIENT_ID, text)
                self.assertIn("kcadm.sh create clients", text)
                self.assertIn(self.AUDIENCE, text)
                self.assertIn("利用者が", text)


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


class RealmExportEditUsernameAllowedTest(unittest.TestCase):
    """#1592: メール更新は #1192 の方針で username も PUT するため、Keycloak 側で
    username の編集を許可しておく必要がある(false だと error-user-attribute-read-only で 502)。
    Web UI から到達できない realm 設定なのでここで表現する。
    """

    def test_edit_username_is_allowed(self):
        with open(REALM_EXPORT, encoding="utf-8") as f:
            realm = json.load(f)
        self.assertIs(realm["editUsernameAllowed"], True)


if __name__ == "__main__":
    unittest.main()
