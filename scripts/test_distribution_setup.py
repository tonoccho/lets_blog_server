#!/usr/bin/env python3
"""配布物の `setup.sh`(apps/penpot-plugin / apps/mcp-server)が実際にビルドを走らせることの検証(#1491)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_distribution_setup.py'

## なぜ Gherkin ではないのか

受入基準3「Zip を展開して setup.sh を実行するとビルドが成功し、README の手順で利用を開始できる」
は、利用者の手元のシェルで走るスクリプトの振る舞いであり、製品の画面からは到達できない
(Playwright の受け入れシナリオはブラウザ操作しか表現できない)。CLAUDE.md → Test-First
Implementation の「Web UI から到達できない場合はサービス/スクリプトレベルのテストで表現する」
例外に当たるため、ここで表現する。Zip の中身(node_modules を含まない等)と取得経路は
`apps/web/e2e/features/platform/source-distribution.feature` が検証する。

展開結果と同じ状態を作るため、リポジトリ追跡ファイル(.gitignore 済みの node_modules・
ビルド生成物を除く)だけを一時ディレクトリへコピーして `bash setup.sh` を実行する。
`npm ci` が走るためネットワーク(または npm のキャッシュ)が要る。
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def tracked_files(app: str) -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", f"apps/{app}"],
        cwd=REPO, check=True, capture_output=True, text=True,
    ).stdout.split("\n")
    return [p for p in out if p and (REPO / p).is_file()]


def extract(app: str, dest: Path) -> Path:
    """Zip を展開した状態(ソース一式のみ)を再現する。"""
    root = dest / f"letsblog-{app}"
    for rel in tracked_files(app):
        target = root / Path(rel).relative_to(f"apps/{app}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / rel, target)
    return root


def run_setup(root: Path, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", "setup.sh"], cwd=root, capture_output=True, text=True,
        env=env or os.environ.copy(), timeout=600,
    )


class DistributionSetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="lbs-dist-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def test_penpot_plugin_setup_builds_and_prints_registration_steps(self):
        root = extract("penpot-plugin", self.tmp)
        self.assertFalse((root / "plugin.js").exists())
        self.assertFalse((root / "node_modules").exists())

        result = run_setup(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((root / "plugin.js").is_file(), "plugin.js がビルドされていません")
        self.assertTrue((root / "ui.js").is_file(), "ui.js がビルドされていません")
        # README の手順(Plugins → Add Plugin → manifest.json)を画面に出す
        self.assertIn("Plugins", result.stdout)
        self.assertIn("Add Plugin", result.stdout)
        self.assertIn(str(root / "manifest.json"), result.stdout)

    def test_mcp_server_setup_installs_and_prints_start_steps(self):
        root = extract("mcp-server", self.tmp)
        self.assertFalse((root / "node_modules").exists())

        result = run_setup(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((root / "node_modules" / "express").is_dir(), "依存が取得されていません")
        self.assertTrue((root / ".env").is_file(), ".env が .env.example から作られていません")
        self.assertIn("npm start", result.stdout)
        self.assertIn("OLLAMA_BASE_URL", result.stdout)

    def test_mcp_server_setup_keeps_an_existing_env(self):
        root = extract("mcp-server", self.tmp)
        (root / ".env").write_text("KEEP=me\n")

        result = run_setup(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((root / ".env").read_text(), "KEEP=me\n")

    def test_setup_stops_with_a_message_when_node_is_too_old(self):
        fake_bin = self.tmp / "fakebin"
        fake_bin.mkdir()
        fake_node = fake_bin / "node"
        fake_node.write_text('#!/bin/sh\necho v18.19.0\n')
        fake_node.chmod(fake_node.stat().st_mode | stat.S_IEXEC)
        env = os.environ.copy()
        env["PATH"] = f"{fake_bin}:{env['PATH']}"
        for app in ("penpot-plugin", "mcp-server"):
            with self.subTest(app=app):
                root = extract(app, self.tmp / app)
                result = run_setup(root, env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Node.js", result.stderr)
                self.assertIn("20", result.stderr)
                self.assertFalse((root / "node_modules").exists())


if __name__ == "__main__":
    unittest.main()
