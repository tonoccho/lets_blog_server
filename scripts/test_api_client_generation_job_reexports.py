#!/usr/bin/env python3
"""`packages/api-client/src/index.ts` の GenerationJobController 再エクスポートの検証(#1299)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求めている。

本Issueの対象は `packages/api-client`(openapi-generatorが出力した型・関数を
`index.ts` が再エクスポートする内部の型契約)であり、`packages/api-client` 自体に
テストランナーが無く、かつ Web UI から到達する経路も無い。加えて壊れ方も
TypeScript の型チェック(`tsc --noEmit`)でしか検出できない — `index.ts` が
存在しない名前(`create`/`update`)を再エクスポートしていても、Next.js の
jest 変換(next/jest、SWC)は型を見ないため、jest 単体テストでは実行時に
`undefined` を返すだけで失敗せず、この欠陥を検出できない。
同節が認める「Web UI から到達できない基準は、その旨を明示してサービス/スクリプト
レベルのテストで表現する」に当たる。黙って省略しているのではない。

## 何を固定するか

`index.ts` は `./generated/ai/generation-job-controller/generation-job-controller`
から `create`/`update`/`getCreateUrl`/`getUpdateUrl`/`createResponse`/`updateResponse`
を再エクスポートしていたが、これらは認可欠如のため
`/api/internal/ai/generation-jobs`(`InternalGenerationJobController`)へ移管され、
生成元のファイルはもう `list`/`get` しかエクスポートしない。存在しない名前を
再エクスポートし続けると `tsc --noEmit` が失敗し、develop にタグが付かない
(#1299)。このテストは、生成元ファイルが実際にエクスポートする名前と、
`index.ts` が GenerationJobController について再エクスポートする名前が一致する
ことを固定する。
"""

import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
API_CLIENT_DIR = os.path.join(REPO_ROOT, "packages", "api-client")
INDEX_TS = os.path.join(API_CLIENT_DIR, "src", "index.ts")
GENERATED_TS = os.path.join(
    API_CLIENT_DIR,
    "src",
    "generated",
    "ai",
    "generation-job-controller",
    "generation-job-controller.ts",
)

EXPORT_NAME = re.compile(r"^export (?:const|type) ([A-Za-z0-9_]+)", re.MULTILINE)


def exported_names(path):
    with open(path, encoding="utf-8") as f:
        content = f.read()
    return {m.group(1) for m in EXPORT_NAME.finditer(content)}


class GenerationJobControllerReexportTest(unittest.TestCase):
    def test_generated_file_no_longer_exports_create_or_update(self):
        """生成元ファイルが create/update をエクスポートしないことの前提確認。"""
        names = exported_names(GENERATED_TS)
        self.assertNotIn("create", names)
        self.assertNotIn("update", names)
        self.assertNotIn("getCreateUrl", names)
        self.assertNotIn("getUpdateUrl", names)
        self.assertNotIn("createResponse", names)
        self.assertNotIn("updateResponse", names)

    def test_index_ts_does_not_reexport_removed_create_update_names(self):
        """index.ts が存在しない create/update 系の名前を再エクスポートしていない。"""
        with open(INDEX_TS, encoding="utf-8") as f:
            content = f.read()
        for removed_name in (
            "createGenerationJob",
            "updateGenerationJob",
            "getCreateGenerationJobUrl",
            "getUpdateGenerationJobUrl",
            "createGenerationJobResponse",
            "updateGenerationJobResponse",
        ):
            self.assertNotIn(
                removed_name,
                content,
                "index.ts が存在しない名前 %s をまだ再エクスポートしている"
                % removed_name,
            )

    def test_api_client_typecheck_passes(self):
        """`tsc --noEmit` が通ること(#1299が固定する本体)。"""
        node_modules = os.path.join(API_CLIENT_DIR, "node_modules")
        if not os.path.isdir(node_modules):
            self.skipTest(
                "packages/api-client/node_modules が無い"
                "(先に `npm ci --prefix packages/api-client` が必要)"
            )
        result = subprocess.run(
            ["npm", "run", "typecheck", "--prefix", API_CLIENT_DIR],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            result.returncode,
            0,
            "tsc --noEmit が失敗した:\nSTDOUT:\n%s\nSTDERR:\n%s"
            % (result.stdout, result.stderr),
        )


if __name__ == "__main__":
    unittest.main()
