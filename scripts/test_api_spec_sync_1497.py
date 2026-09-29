#!/usr/bin/env python3
"""content・log-writer・media・platform の4サービスについて、develop にマージ済みの
実装(#1131, #1138, #1405, #1079)に `openapi/<svc>.json` と
`packages/api-client/src/generated/<svc>/**` が追随していることを固定する(#1497)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_api_spec_sync_1497.py'

## なぜ Gherkin ではないのか

`@lets-blog/api-client` は `apps/web` からまだ実際に呼ばれておらず(`apps/web/src/lib/apiClient.ts`
が手書き fetch を使っている)、本Issueの対象である `openapi/<svc>.json`(仕様)と
`packages/api-client/src/generated/<svc>/**`(orvalの生成物)自体はブラウザから観測できる
振る舞いを持たない。Web UI からの到達経路が構造的に存在しないため、`scripts/test_api_client_sync.py`
(#1041)・`scripts/test_analytics_ga_oauth_spec.py`(#1451)と同じ理由づけにより、
サービス/スクリプトレベルのテストで受け入れ基準を表現する。

## 何を検知するか

- content: `POST /api/custom-tag-templates/{id}/apply` と `ApplyCustomTagTemplateRequest`(#1131, 9c5bbf6a)
- log-writer: `GET /api/audit-logs`・`GET /api/logs/errors`・`GET /api/operation-logs/unified` の
  `startDate`/`endDate` クエリパラメータ(#1138, 1c855f25)
- media: `POST /api/ai/image/jobs`(#1405, 444ac1de)
- platform: `GET /api/system-settings/site-admin-path` と `SiteAdminPathResponse`(#1079, b4a6fa5a)

がコミット済みの `openapi/<svc>.json` と生成済み `packages/api-client/src/generated/<svc>/**` の
双方に反映されていることを固定する。再生成の実行自体(`./scripts/generate-api-client.sh`)は
このテストの範囲外で、`docs/API_CLIENT_GENERATION.md` の手順に従って手動で行う。
"""

import glob
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
OPENAPI_DIR = os.path.join(REPO_ROOT, "openapi")
GENERATED_DIR = os.path.join(REPO_ROOT, "packages", "api-client", "src", "generated")


def _load_spec(service):
    with open(os.path.join(OPENAPI_DIR, "%s.json" % service), encoding="utf-8") as f:
        return json.load(f)


def _generated_text(service):
    """`packages/api-client/src/generated/<service>/**` 配下の全ファイルを連結したテキスト。"""
    text = []
    for path in sorted(
        glob.glob(os.path.join(GENERATED_DIR, service, "**", "*"), recursive=True)
    ):
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                text.append(f.read())
    return "\n".join(text)


class ContentApplyCustomTagTemplateSpecSync(unittest.TestCase):
    """受入基準1: content の `POST /api/custom-tag-templates/{id}/apply` と
    `ApplyCustomTagTemplateRequest` が仕様・生成物の双方に反映されていること(#1131)。
    """

    PATH = "/api/custom-tag-templates/{id}/apply"
    SCHEMA = "ApplyCustomTagTemplateRequest"

    @classmethod
    def setUpClass(cls):
        cls.spec = _load_spec("content")
        cls.generated_text = _generated_text("content")

    def test_openapi_has_apply_endpoint(self):
        path_item = self.spec.get("paths", {}).get(self.PATH, {})
        self.assertIn(
            "post",
            path_item,
            "openapi/content.json に POST %s が無い(#1131の再生成漏れ)" % self.PATH,
        )

    def test_openapi_has_apply_request_schema(self):
        schemas = self.spec.get("components", {}).get("schemas", {})
        self.assertIn(
            self.SCHEMA,
            schemas,
            "openapi/content.json にスキーマ %s が無い(#1131の再生成漏れ)" % self.SCHEMA,
        )

    def test_generated_client_has_apply_url(self):
        self.assertIn(
            "/api/custom-tag-templates/${id}/apply",
            self.generated_text,
            "packages/api-client/src/generated/content/ に %s のURLが無い" % self.PATH,
        )

    def test_generated_client_has_apply_request_type(self):
        self.assertIn(
            self.SCHEMA,
            self.generated_text,
            "packages/api-client/src/generated/content/ に型 %s が無い" % self.SCHEMA,
        )


class LogWriterStartEndDateSpecSync(unittest.TestCase):
    """受入基準2: log-writer の3エンドポイントに `startDate`/`endDate` クエリパラメータが
    仕様・生成物の双方に反映されていること(#1138)。
    """

    ENDPOINTS = {
        "/api/audit-logs": "get",
        "/api/logs/errors": "get",
        "/api/operation-logs/unified": "get",
    }

    @classmethod
    def setUpClass(cls):
        cls.spec = _load_spec("log-writer")
        cls.generated_text = _generated_text("log-writer")

    def test_openapi_endpoints_have_start_and_end_date(self):
        for path, method in self.ENDPOINTS.items():
            with self.subTest(path=path, method=method):
                operation = self.spec.get("paths", {}).get(path, {}).get(method, {})
                param_names = {p.get("name") for p in operation.get("parameters", [])}
                self.assertIn(
                    "startDate",
                    param_names,
                    "openapi/log-writer.json の %s %s に startDate が無い(#1138の再生成漏れ)"
                    % (method.upper(), path),
                )
                self.assertIn(
                    "endDate",
                    param_names,
                    "openapi/log-writer.json の %s %s に endDate が無い(#1138の再生成漏れ)"
                    % (method.upper(), path),
                )

    # orval は operationId から Params 型名を作る(例: listUnified -> ListUnifiedParams)が、
    # `list_1` のようなアンダースコア+数字は `List1Params` のように詰められるなど機械的な
    # 変換では再現しにくいため、稼働中の生成物で実際に使われている型名を固定で対応させる。
    PARAM_TYPE_NAMES = {
        "/api/audit-logs": "List1Params",
        "/api/logs/errors": "GetErrorsParams",
        "/api/operation-logs/unified": "ListUnifiedParams",
    }

    def test_generated_params_types_have_start_and_end_date(self):
        import re

        for path, method in self.ENDPOINTS.items():
            type_name = self.PARAM_TYPE_NAMES[path]
            with self.subTest(path=path, type_name=type_name):
                match = re.search(
                    r"export type %s = \{([^}]*)\}" % re.escape(type_name),
                    self.generated_text,
                )
                self.assertIsNotNone(
                    match,
                    "packages/api-client/src/generated/log-writer/ に型 %s が無い"
                    % type_name,
                )
                body = match.group(1)
                self.assertIn(
                    "startDate",
                    body,
                    "packages/api-client/src/generated/log-writer/ の %s に startDate が無い"
                    % type_name,
                )
                self.assertIn(
                    "endDate",
                    body,
                    "packages/api-client/src/generated/log-writer/ の %s に endDate が無い"
                    % type_name,
                )


class MediaImageJobSpecSync(unittest.TestCase):
    """受入基準3: media の `POST /api/ai/image/jobs` が仕様・生成物の双方に
    反映されていること(#1405)。
    """

    PATH = "/api/ai/image/jobs"

    @classmethod
    def setUpClass(cls):
        cls.spec = _load_spec("media")
        cls.generated_text = _generated_text("media")

    def test_openapi_has_image_jobs_endpoint(self):
        path_item = self.spec.get("paths", {}).get(self.PATH, {})
        self.assertIn(
            "post",
            path_item,
            "openapi/media.json に POST %s が無い(#1405の再生成漏れ)" % self.PATH,
        )

    def test_generated_client_has_image_jobs_url(self):
        self.assertIn(
            "/api/ai/image/jobs",
            self.generated_text,
            "packages/api-client/src/generated/media/ に %s のURLが無い" % self.PATH,
        )


class PlatformSiteAdminPathSpecSync(unittest.TestCase):
    """受入基準4: platform の `GET /api/system-settings/site-admin-path` と
    `SiteAdminPathResponse` が仕様・生成物の双方に反映されていること(#1079)。
    """

    PATH = "/api/system-settings/site-admin-path"
    SCHEMA = "SiteAdminPathResponse"

    @classmethod
    def setUpClass(cls):
        cls.spec = _load_spec("platform")
        cls.generated_text = _generated_text("platform")

    def test_openapi_has_site_admin_path_endpoint(self):
        path_item = self.spec.get("paths", {}).get(self.PATH, {})
        self.assertIn(
            "get",
            path_item,
            "openapi/platform.json に GET %s が無い(#1079の再生成漏れ)" % self.PATH,
        )

    def test_openapi_has_site_admin_path_response_schema(self):
        schemas = self.spec.get("components", {}).get("schemas", {})
        self.assertIn(
            self.SCHEMA,
            schemas,
            "openapi/platform.json にスキーマ %s が無い(#1079の再生成漏れ)" % self.SCHEMA,
        )

    def test_generated_client_has_site_admin_path_url(self):
        self.assertIn(
            "/api/system-settings/site-admin-path",
            self.generated_text,
            "packages/api-client/src/generated/platform/ に %s のURLが無い" % self.PATH,
        )

    def test_generated_client_has_site_admin_path_response_type(self):
        self.assertIn(
            self.SCHEMA,
            self.generated_text,
            "packages/api-client/src/generated/platform/ に型 %s が無い" % self.SCHEMA,
        )


if __name__ == "__main__":
    unittest.main()
