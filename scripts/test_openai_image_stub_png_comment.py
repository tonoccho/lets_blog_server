#!/usr/bin/env python3
"""`openai-image` スタブのPNG定数の説明が実測と一致することの検証(#1119)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象はスタブ内のコメントの正確さであり、Web UIから観測できる振る舞いではない。
`CLAUDE.md` → Test-First Implementation が認める「サービス/スクリプトレベルのテスト」で表す。
"""
import base64
import re
import struct
import unittest
import zlib
from pathlib import Path

SERVER_JS = Path(__file__).resolve().parent.parent / "infra/e2e-stubs/openai-image/server.js"

# 応答バイト列は変更しない(決定性・既存シナリオへの影響回避。#1119 受入基準2)。
EXPECTED_PNG_BASE64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)


def decode_single_pixel(png: bytes):
    """1x1 の 8bit RGBA PNG の (R, G, B, A) を返す。"""
    pos = 8
    idat = b""
    color_type = None
    while pos < len(png):
        length, ctype = struct.unpack(">I4s", png[pos : pos + 8])
        body = png[pos + 8 : pos + 8 + length]
        if ctype == b"IHDR":
            width, height, depth, color_type = struct.unpack(">IIBB", body[:10])
            assert (width, height, depth) == (1, 1, 8)
        elif ctype == b"IDAT":
            idat += body
        pos += 12 + length
    assert color_type == 6, "RGBA(8bit)ではない"
    raw = zlib.decompress(idat)
    # 1x1 では左・上の画素が無く、どのフィルタ種別でも先頭画素の復元値は生バイトに等しい。
    return tuple(raw[1:5])


class OpenAiImageStubPngCommentTest(unittest.TestCase):
    def setUp(self):
        self.source = SERVER_JS.read_text(encoding="utf-8")
        match = re.search(r"PNG_BASE64\s*=\s*'([^']+)'", self.source)
        self.assertIsNotNone(match)
        self.png_base64 = match.group(1)

    def test_response_bytes_are_unchanged(self):
        self.assertEqual(self.png_base64, EXPECTED_PNG_BASE64)

    def test_actual_pixel_is_translucent_green(self):
        self.assertEqual(decode_single_pixel(base64.b64decode(self.png_base64)), (0, 255, 0, 127))

    def test_comment_does_not_claim_opaque_blue(self):
        self.assertNotIn("不透明な青", self.source)
        self.assertNotIn("青いPNG", self.source)

    def test_comment_states_actual_pixel(self):
        self.assertIn("RGBA(0, 255, 0, 127)", self.source)


if __name__ == "__main__":
    unittest.main()
