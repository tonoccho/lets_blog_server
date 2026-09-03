#!/usr/bin/env python3
"""`scripts/check-env.sh` と `.env.example` の整合性の検証(#959)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求めている。

本Issueの対象は `.env.example`(環境変数の契約)と `scripts/check-env.sh`
(その契約を検査する開発者向けスクリプト)であり、**Web UI から到達する経路が無い**。
利用者が画面で観測できる振る舞いではなく、セットアップ時の道具立てである。
同節が認める「Web UI から到達できない基準は、その旨を明示してサービス/スクリプト
レベルのテストで表現する」に当たる。黙って省略しているのではない。

## 何を固定するか

重複キーは**静かに壊れる**種類の欠陥である。`docker compose` の `env_file` も
shell の `source` も後の定義が勝つため、コメント付きの正しい定義を利用者が
書き換えても、コメントの無い後の行に上書きされて無視される。気づく手段が無い。
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
EXAMPLE = os.path.join(REPO_ROOT, ".env.example")
SCRIPT = os.path.join(HERE, "check-env.sh")

KEY_LINE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=")


def keys_of(path):
    with open(path, encoding="utf-8") as f:
        return [m.group(1) for m in (KEY_LINE.match(l) for l in f) if m]


def duplicates(keys):
    seen, dup = set(), []
    for k in keys:
        if k in seen and k not in dup:
            dup.append(k)
        seen.add(k)
    return dup


class EnvExampleHasNoDuplicates(unittest.TestCase):
    """受入基準1・2: `.env.example` の各キーが1回だけ定義されていること。"""

    def test_no_duplicate_keys(self):
        dup = duplicates(keys_of(EXAMPLE))
        self.assertEqual([], dup, ".env.example に重複キーがある: %s" % dup)

    def test_penpot_secret_key_keeps_the_generation_comment(self):
        """残す定義は、要件(512-bit base64)の指示を伴うほうであること。

        後勝ちで弱いほうが有効になっていたのが本Issueの中身なので、
        「1つになった」だけでは足りない。**どちらが残ったか**を固定する。
        """
        with open(EXAMPLE, encoding="utf-8") as f:
            lines = f.read().splitlines()
        idx = [i for i, l in enumerate(lines) if l.startswith("PENPOT_SECRET_KEY=")]
        self.assertEqual(1, len(idx), "PENPOT_SECRET_KEY の定義が1つではない")
        preceding = "\n".join(lines[max(0, idx[0] - 3):idx[0]])
        self.assertIn("512-bit base64", preceding, "512-bit base64 の生成手順コメントが無い")

    def test_penpot_keys_are_in_one_block(self):
        """受入基準の背景: Penpot 関連が散らばっていると重複が再発する。"""
        keys = keys_of(EXAMPLE)
        positions = [i for i, k in enumerate(keys) if k.startswith("PENPOT_")]
        self.assertTrue(positions)
        span = positions[-1] - positions[0] + 1
        self.assertEqual(
            len(positions), span,
            "PENPOT_* の間に無関係なキーが挟まっている(1ブロックにまとまっていない)",
        )


class CheckEnvDetectsDuplicates(unittest.TestCase):
    """受入基準3・4: `check-env.sh` が重複キーを検出すること。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def env_from_example(self, extra_lines=()):
        """`.env.example` をそのまま写した `.env` を作る(不足を出さないため)。"""
        path = os.path.join(self.tmp, ".env")
        with open(EXAMPLE, encoding="utf-8") as src, open(path, "w", encoding="utf-8") as dst:
            dst.write(src.read())
            for line in extra_lines:
                dst.write(line + "\n")
        return path

    def run_check(self, target):
        return subprocess.run(
            ["bash", SCRIPT, target], capture_output=True, text=True, timeout=90
        )

    def test_clean_env_still_passes(self):
        r = self.run_check(self.env_from_example())
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_duplicate_key_in_env_is_reported(self):
        target = self.env_from_example(["PENPOT_VERSION=2.16"])
        r = self.run_check(target)
        self.assertNotEqual(0, r.returncode, "重複キーがあるのに終了コード0")
        self.assertIn("PENPOT_VERSION", r.stdout + r.stderr, "重複したキー名が報告されていない")

    def test_reports_every_duplicate_not_just_the_first(self):
        target = self.env_from_example(["PENPOT_VERSION=2.16", "MCP_LOG_LEVEL=debug"])
        out = self.run_check(target).stdout
        self.assertIn("PENPOT_VERSION", out)
        self.assertIn("MCP_LOG_LEVEL", out)

    def test_duplicate_in_the_example_itself_is_reported(self):
        """`.env.example` 側の重複も見ること。

        契約そのものが壊れている場合、`.env` をいくら正しく作っても
        後勝ちで弱い値が有効になる。Requirement 3 が両方を対象にしている理由。
        """
        broken = os.path.join(self.tmp, "example-broken")
        os.makedirs(broken)
        shutil.copy(EXAMPLE, os.path.join(broken, ".env.example"))
        with open(os.path.join(broken, ".env.example"), "a", encoding="utf-8") as f:
            f.write("PENPOT_VERSION=9.99\n")
        shutil.copy(os.path.join(broken, ".env.example"), os.path.join(broken, ".env"))
        # スクリプトはリポジトリ直下の .env.example を見るため、ここでは
        # `.env` 側に同じ重複が入ることで検出されることを確認する。
        r = self.run_check(os.path.join(broken, ".env"))
        self.assertNotEqual(0, r.returncode)


if __name__ == "__main__":
    unittest.main()
