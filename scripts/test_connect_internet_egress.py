#!/usr/bin/env python3
"""`connect-internet-egress.sh` が分割後の現行サービス構成を対象にすることの検証(#1205)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、同時に
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルの
テストで表現する」ことを明示的な例外として認めている。

本Issueの対象は `docker network connect` を叩く開発者向けスクリプトそのものであり、
検証したい振る舞い(「どのコンテナ名を対象にするか」「対象コンテナが1つも無いときに
サイレントに exit 0 しないこと」)は実際の Docker デーモンの有無・実行中のコンテナ構成
というホスト環境に依存する。E2E環境のPlaywrightからは`docker network connect`という
インフラ操作そのものへ到達できず、Web UIから観測できる振る舞いでもない。
`scripts/test_rebuild_acceptance_env.py`(#1038-系)・`scripts/test_comfyui_gpu_profile.py`
(#1066)と同じ、文書化された例外としてここで表現する。

## なぜ `docker` をスタブで差し替えるのか

本物の `docker` を叩くわけにはいかない(このテストは開発機の実スタックを操作しうる)。
`scripts/test_rebuild_acceptance_env.py` が確立した手法にならい、`docker` を **PATH で
差し替えた偽物**に向け、`FAKE_DOCKER_EXISTING`(実在することにするコンテナ名、
スペース区切り)で存在有無を模し、呼び出しをログファイルに記録して検査する。
"""

import os
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(REPO_ROOT, "scripts", "connect-internet-egress.sh")

# #575 でモノリスが分割された後もインターネットへの外部HTTP呼び出しを行っている
# コンテナ(RestClient/Playwrightでの外部URL到達・外部LLM/画像API・外部ホスト向けSSH等)。
# 各サービスの根拠は本Issue(#1205)の調査結果:
#   - lbs-ai: api.anthropic.com / api.github.com / api.search.brave.com
#   - lbs-analytics: googleapis.com 系(Google Analytics Data API / AdSense API)
#   - lbs-content: Playwright経由の外部ページ取得([blogcard]/[amazon]スクレイピング)
#   - lbs-media: ChatGptImageClient(画像生成API)
#   - lbs-platform: api.anthropic.com / api.openai.com
#   - lbs-publishing: 記事プレビュー取得・WordPress SSH操作(管理対象サイトが外部ホストの場合)
#   - lbs-wordpress / lbs-ollama: 既存(wordpress.org / registry.ollama.ai)
EXPECTED_TARGET_CONTAINERS = {
    "lbs-ai",
    "lbs-analytics",
    "lbs-content",
    "lbs-media",
    "lbs-platform",
    "lbs-publishing",
    "lbs-wordpress",
    "lbs-ollama",
}

FAKE_DOCKER = """#!/bin/bash
# テスト用の偽docker(#1205)。FAKE_DOCKER_EXISTINGに列挙されたコンテナ名だけ実在する
# ことにし、呼び出しをFAKE_DOCKER_LOGへ記録する。
set -uo pipefail

echo "$*" >> "$FAKE_DOCKER_LOG"

existing=" ${FAKE_DOCKER_EXISTING:-} "

case "$1" in
  inspect)
    name="$2"
    if [[ "$existing" == *" $name "* ]]; then
      exit 0
    else
      exit 1
    fi
    ;;
  network)
    if [[ "$2" == "connect" ]]; then
      name="$4"
      already=" ${FAKE_DOCKER_ALREADY_CONNECTED:-} "
      if [[ "$already" == *" $name "* ]]; then
        echo "already connected" >&2
        exit 1
      fi
      exit 0
    fi
    exit 1
    ;;
  *)
    exit 1
    ;;
esac
"""


class ConnectInternetEgressTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="connect-internet-egress-test-")
        self.addCleanup(self._rmtree)

        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        docker_path = os.path.join(self.bin, "docker")
        with open(docker_path, "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(docker_path, os.stat(docker_path).st_mode | stat.S_IEXEC)

        self.log = os.path.join(self.tmp, "docker.log")
        with open(self.log, "w", encoding="utf-8"):
            pass

    def _rmtree(self):
        import shutil

        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, existing="", already_connected=""):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_DOCKER_LOG"] = self.log
        env["FAKE_DOCKER_EXISTING"] = existing
        env["FAKE_DOCKER_ALREADY_CONNECTED"] = already_connected
        return subprocess.run(
            ["bash", SCRIPT],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
        )

    def docker_calls(self):
        with open(self.log, encoding="utf-8") as f:
            return [line.strip() for line in f if line.strip()]

    def test_targets_current_service_containers_not_legacy_lbs_api(self):
        """分割後の現行コンテナ群を対象にし、消滅した lbs-api は対象にしない。"""
        result = self.run_script(existing=" ".join(EXPECTED_TARGET_CONTAINERS))

        self.assertEqual(0, result.returncode, msg=result.stderr)

        connect_calls = [
            call for call in self.docker_calls() if call.startswith("network connect")
        ]
        connected_names = {call.split()[-1] for call in connect_calls}

        self.assertEqual(EXPECTED_TARGET_CONTAINERS, connected_names)
        self.assertNotIn("lbs-api", " ".join(self.docker_calls()))

    def test_publishing_and_content_are_targeted(self):
        """#1205 が明示する publishing / content が対象に含まれる(最低条件)。"""
        result = self.run_script(existing="lbs-publishing lbs-content")

        self.assertEqual(0, result.returncode, msg=result.stderr)
        connect_calls = [
            call for call in self.docker_calls() if call.startswith("network connect")
        ]
        connected_names = {call.split()[-1] for call in connect_calls}
        self.assertEqual({"lbs-publishing", "lbs-content"}, connected_names)

    def test_exits_non_zero_when_no_target_container_exists(self):
        """対象コンテナが1つも見つからない場合、サイレントにexit 0してはならない。"""
        result = self.run_script(existing="")

        self.assertNotEqual(
            0,
            result.returncode,
            msg="対象コンテナが0件でもexit 0していた(サイレント成功): "
            + result.stdout
            + result.stderr,
        )

    def test_idempotent_when_run_twice(self):
        """既に接続済みのコンテナがあっても、2回目の実行は失敗として扱わない。"""
        result = self.run_script(
            existing="lbs-publishing",
            already_connected="lbs-publishing",
        )

        self.assertEqual(0, result.returncode, msg=result.stderr)

    def test_header_comment_no_longer_mentions_lbs_api(self):
        """ヘッダーコメントが消滅したlbs-apiではなく現行構成を説明している。"""
        with open(SCRIPT, encoding="utf-8") as f:
            header = f.read()

        self.assertNotIn("lbs-api", header)


if __name__ == "__main__":
    unittest.main()
