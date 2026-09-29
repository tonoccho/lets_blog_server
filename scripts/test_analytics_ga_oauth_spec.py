#!/usr/bin/env python3
"""#1231でGA連携がOAuth化された仕様に、`openapi/analytics.json` と
`packages/api-client/src/generated/analytics/**` が追随していることを固定する(#1451)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_analytics_ga_oauth_spec.py'

## なぜ Gherkin ではないのか

`@lets-blog/api-client` は `apps/web` からまだ実際に呼ばれておらず(`apps/web/src/lib/apiClient.ts`
が手書き fetch を使っている)、本Issueの対象である `openapi/analytics.json`(仕様)と
`packages/api-client/src/generated/analytics/**`(orvalの生成物)自体はブラウザから観測できる
振る舞いを持たない。Web UI からの到達経路が構造的に存在しないため、`scripts/test_api_client_sync.py`
(#1041)・`scripts/test_api_client_generation_job_reexports.py`(#1299)と同じ理由づけにより、
サービス/スクリプトレベルのテストで受け入れ基準を表現する。

## 何を検知するか

このテストは `services/analytics/.../ProjectAnalyticsApiKeyController.java` が#1231で
公開した新エンドポイント(`google-analytics/client` PUT・`google-analytics/oauth-callback` POST・
`google-analytics/properties` GET・`google-analytics/property` PUT)が、コミット済みの
`openapi/analytics.json` と生成済み `packages/api-client/src/generated/analytics/**` の双方に
反映されていること、および旧仕様(`PUT /api/projects/{projectId}/api-keys/google-analytics` と
`SetProjectGoogleAnalyticsCredentialsRequest`)が両方から消えていることを固定する。
再生成の実行自体(`./scripts/generate-api-client.sh`)はこのテストの範囲外で、
`docs/API_CLIENT_GENERATION.md` の手順に従って手動で行う。
"""

import glob
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
OPENAPI_ANALYTICS_JSON = os.path.join(REPO_ROOT, "openapi", "analytics.json")
GENERATED_ANALYTICS_DIR = os.path.join(
    REPO_ROOT, "packages", "api-client", "src", "generated", "analytics"
)

OLD_PATH = "/api/projects/{projectId}/api-keys/google-analytics"
OLD_SCHEMA = "SetProjectGoogleAnalyticsCredentialsRequest"

NEW_PATHS = {
    "/api/projects/{projectId}/api-keys/google-analytics/client": "put",
    "/api/projects/{projectId}/api-keys/google-analytics/oauth-callback": "post",
    "/api/projects/{projectId}/api-keys/google-analytics/properties": "get",
    "/api/projects/{projectId}/api-keys/google-analytics/property": "put",
}

# orval(operationId起点)が新エンドポイントに対して生成するはずの関数名
# (稼働中の lbs-analytics の /v3/api-docs から採取したoperationIdと一致させている)。
NEW_FUNCTION_NAMES = [
    "setGoogleAnalyticsClient",
    "completeGoogleAnalyticsOAuth",
    "listGoogleAnalyticsProperties",
    "selectGoogleAnalyticsProperty",
]

OLD_FUNCTION_NAME = "setGoogleAnalyticsCredentials"


def _generated_analytics_text():
    """`packages/api-client/src/generated/analytics/**` 配下の全ファイルを連結したテキスト。"""
    text = []
    for path in sorted(glob.glob(os.path.join(GENERATED_ANALYTICS_DIR, "**", "*"), recursive=True)):
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                text.append(f.read())
    return "\n".join(text)


class OpenApiAnalyticsSpecReflectsOAuthMigration(unittest.TestCase):
    """受入基準1: `openapi/analytics.json` に旧service account JSON仕様が無く、
    新OAuthエンドポイントが含まれること。
    """

    @classmethod
    def setUpClass(cls):
        with open(OPENAPI_ANALYTICS_JSON, encoding="utf-8") as f:
            cls.spec = json.load(f)

    def test_old_set_credentials_schema_is_absent(self):
        schemas = self.spec.get("components", {}).get("schemas", {})
        self.assertNotIn(
            OLD_SCHEMA,
            schemas,
            "openapi/analytics.json に旧スキーマ %s が残っている(#1231の再生成漏れ)" % OLD_SCHEMA,
        )

    def test_old_put_google_analytics_is_absent(self):
        old_path_item = self.spec.get("paths", {}).get(OLD_PATH, {})
        self.assertNotIn(
            "put",
            old_path_item,
            "openapi/analytics.json に旧 PUT %s が残っている(#1231の再生成漏れ)" % OLD_PATH,
        )

    def test_new_oauth_endpoints_are_present(self):
        paths = self.spec.get("paths", {})
        for path, method in NEW_PATHS.items():
            with self.subTest(path=path, method=method):
                self.assertIn(
                    path,
                    paths,
                    "openapi/analytics.json に新エンドポイント %s が無い" % path,
                )
                self.assertIn(
                    method,
                    paths.get(path, {}),
                    "openapi/analytics.json の %s に %s が無い" % (path, method.upper()),
                )


class GeneratedAnalyticsClientReflectsOAuthMigration(unittest.TestCase):
    """受入基準2: `packages/api-client/src/generated/analytics/` に旧関数/型が無く、
    新OAuthエンドポイントに対応する関数が存在すること。
    """

    @classmethod
    def setUpClass(cls):
        cls.text = _generated_analytics_text()

    def test_old_set_credentials_function_is_absent(self):
        self.assertNotIn(
            OLD_FUNCTION_NAME,
            self.text,
            "packages/api-client/src/generated/analytics/ に旧関数 %s が残っている" % OLD_FUNCTION_NAME,
        )

    def test_old_set_credentials_request_type_is_absent(self):
        self.assertNotIn(
            OLD_SCHEMA,
            self.text,
            "packages/api-client/src/generated/analytics/ に旧型 %s が残っている" % OLD_SCHEMA,
        )

    def test_new_oauth_functions_are_present(self):
        for name in NEW_FUNCTION_NAMES:
            with self.subTest(name=name):
                self.assertIn(
                    "export const %s " % name,
                    self.text,
                    "packages/api-client/src/generated/analytics/ に新関数 %s が無い" % name,
                )


if __name__ == "__main__":
    unittest.main()
