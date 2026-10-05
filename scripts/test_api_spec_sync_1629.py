#!/usr/bin/env python3
"""#1564 で削除した旧プレビュー API が、`openapi/*.json` と `packages/api-client/src/**`
(生成物と手書きの `index.ts`)の双方から消えていることを固定する(#1629)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_api_spec_sync_1629.py'

## なぜ Gherkin ではないのか

対象は OpenAPI spec と orval の生成物自体で、ブラウザから観測できる振る舞いを持たない。
`scripts/test_api_spec_sync_1497.py` と同じ理由で、スクリプトレベルのテストで表す。
"""

import glob
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
OPENAPI_DIR = os.path.join(REPO_ROOT, "openapi")
CLIENT_SRC = os.path.join(REPO_ROOT, "packages", "api-client", "src")

REMOVED_IDENTIFIERS = [
    "theme-css",
    "skeleton",
    "preview-post",
    "ThemeSkeletonResponse",
    "ThemeCssResponse",
    "RenderSkeletonRequest",
    "FetchAndSpliceRequest",
    "FetchRealPostRequest",
]


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


class RemovedPreviewApiAbsent(unittest.TestCase):
    def test_openapi_specs_do_not_mention_removed_identifiers(self):
        for path in sorted(glob.glob(os.path.join(OPENAPI_DIR, "*.json"))):
            text = _read(path)
            json.loads(text)
            for ident in REMOVED_IDENTIFIERS:
                with self.subTest(file=os.path.basename(path), ident=ident):
                    self.assertNotIn(ident, text)

    def test_api_client_sources_do_not_mention_removed_identifiers(self):
        paths = sorted(
            p
            for p in glob.glob(os.path.join(CLIENT_SRC, "**", "*"), recursive=True)
            if os.path.isfile(p)
        )
        self.assertTrue(paths)
        for path in paths:
            text = _read(path)
            rel = os.path.relpath(path, CLIENT_SRC)
            for ident in REMOVED_IDENTIFIERS:
                with self.subTest(file=rel, ident=ident):
                    self.assertNotIn(ident, text)

    def test_removed_controller_modules_are_gone(self):
        self.assertFalse(
            os.path.exists(
                os.path.join(
                    CLIENT_SRC,
                    "generated",
                    "content",
                    "internal-preview-skeleton-controller",
                )
            )
        )


if __name__ == "__main__":
    unittest.main()
