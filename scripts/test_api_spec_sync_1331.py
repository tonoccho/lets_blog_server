#!/usr/bin/env python3
"""identity の `openapi/identity.json` と `packages/api-client/src/generated/identity/**` が、
#1259 で任意(null 許容)になった `timezone` を反映していることを固定する(#1331)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_api_spec_sync_1331.py'

## なぜ Gherkin ではないのか

`@lets-blog/api-client` は `apps/web` からまだ実際に呼ばれておらず、`openapi/identity.json`
(仕様)と orval の生成物はブラウザから観測できる振る舞いを持たない。Web UI からの到達経路が
構造的に存在しないため、`scripts/test_api_spec_sync_1497.py` と同じ理由づけにより、
スクリプトレベルのテストで受け入れ基準を表現する。

再生成の実行自体(`./scripts/generate-api-client.sh`)は範囲外で、手動で行う。
仕様→生成物の整合は `scripts/test_api_client_sync.py`(#1041)が検査する。
"""

import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SPEC = os.path.join(REPO_ROOT, "openapi", "identity.json")
SCHEMAS = os.path.join(
    REPO_ROOT, "packages", "api-client", "src", "generated", "identity",
    "openAPIDefinition.schemas.ts",
)


def _interface_body(text, name):
    m = re.search(r"export interface %s \{(.*?)\n\}" % re.escape(name), text, re.S)
    assert m, "%s が生成物に無い" % name
    return m.group(1)


class IdentityTimezoneNullableTest(unittest.TestCase):
    def setUp(self):
        with open(SPEC, encoding="utf-8") as f:
            self.spec = json.load(f)
        with open(SCHEMAS, encoding="utf-8") as f:
            self.schemas = f.read()

    def test_spec_update_request_timezone_is_optional(self):
        schema = self.spec["components"]["schemas"]["UpdateUserPreferencesRequest"]
        self.assertNotIn("timezone", schema.get("required", []))
        self.assertNotIn("minLength", schema["properties"]["timezone"])
        self.assertIn("locale", schema.get("required", []))

    def test_generated_update_request_timezone_is_optional(self):
        body = _interface_body(self.schemas, "UpdateUserPreferencesRequest")
        self.assertRegex(body, r"\n\s*timezone\?: string;")
        self.assertNotRegex(body, r"@minLength 1 \*/\s*\n\s*timezone")

    def test_generated_profile_response_timezone_is_optional(self):
        body = _interface_body(self.schemas, "UserProfileResponse")
        self.assertRegex(body, r"\n\s*timezone\?: string;")


if __name__ == "__main__":
    unittest.main()
