#!/usr/bin/env python3
"""`scripts/e2e-clear-llm-db-overrides.sh` の `KEYS` に取りこぼしが無いことを検証する(#1106)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

`AppSettingService.resolve()` は **DB 優先**である。

    return repository.findById(key)
            .map(setting -> credentialCipher.decrypt(setting.getSettingValueEncrypted()))
            .filter(value -> !value.isBlank())
            .orElseGet(() -> envDefaults.getOrDefault(key, ""));

したがって `docker-compose.e2e-stubs.yml` が環境変数で向き先をスタブへ差し替えても、
同じ設定キーの行が `lbs_platform.system_settings` にあると **overlay は黙って無視され、
実サービスへ出ていく**。実キーが入っていれば課金が発生し、入っていなければ受け入れテストが
不可解に落ちる。

この失敗モードは #843 が `llm_base_url` / `image_llm_base_url` で一度支払った代価であり、
その対策として `scripts/e2e-clear-llm-db-overrides.sh` が作られた。しかし対策は
**キーの列挙**であって、キーが増えたときに自動では追随しない。スクリプト自身のコメントが

    # 追加したキーはここにも足すこと(足し忘れると、そのキーだけDB値が残り実サービスへ出ていく)

と警告しているとおりで、#1106 の `comfyui_base_url` が実際に取りこぼされた。
「足し忘れないこと」を人間の注意力ではなくテストで担保する。

## 何を検査するか

overlay が環境変数で差し替えるもののうち、`AppSettingService` が管理する設定キーと
同名(小文字化)のものは、すべて `KEYS` に載っていなければならない。この対応は偶然ではなく、
`application.yml` の `app.<key をハイフンにしたもの>: ${<KEY 大文字>:...}` という
命名規約そのものである。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
CLEAR_SCRIPT = os.path.join(HERE, "e2e-clear-llm-db-overrides.sh")
STUB_COMPOSE = os.path.join(REPO_ROOT, "docker-compose.e2e-stubs.yml")
APP_SETTING_SERVICE = os.path.join(
    REPO_ROOT,
    "services/platform/src/main/java/com/letsblog/platform/service/AppSettingService.java",
)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def cleared_keys():
    """スクリプトが DELETE 対象にしている設定キー。"""
    text = read(CLEAR_SCRIPT)
    match = re.search(r'^KEYS="(.*?)"', text, re.S | re.M)
    assert match, "e2e-clear-llm-db-overrides.sh に KEYS の定義が見つからない"
    return set(re.findall(r"'([a-z0-9_]+)'", match.group(1)))


def script_header():
    """`set -euo pipefail` より前のコメントブロックを1つの文字列にして返す。

    1つの文が複数行に折り返されているため、行単位ではなく連結して扱う。
    """
    lines = []
    for line in read(CLEAR_SCRIPT).splitlines():
        if line.startswith("set "):
            break
        if line.startswith("#"):
            lines.append(line.lstrip("#").strip())
    return " ".join(lines)


def app_setting_keys():
    """AppSettingService が定義する設定キー(定数名 -> キー文字列)。"""
    text = read(APP_SETTING_SERVICE)
    return dict(re.findall(r'static final String ([A-Z0-9_]+) = "([a-z0-9_]+)";', text))


def overlay_env_vars():
    """docker-compose.e2e-stubs.yml が設定する環境変数名。"""
    return set(re.findall(r"^\s+([A-Z][A-Z0-9_]*):\s*\S", read(STUB_COMPOSE), re.M))


class ClearScriptKeysTest(unittest.TestCase):
    def test_keys_exist_in_app_setting_service(self):
        """KEYS に書かれたキーはすべて実在する(綴り間違いは黙って no-op になる)。"""
        defined = set(app_setting_keys().values())
        unknown = sorted(cleared_keys() - defined)
        self.assertEqual(
            [],
            unknown,
            "AppSettingService に存在しないキーが KEYS にある: " + str(unknown),
        )

    def test_stub_overlay_env_overrides_are_all_cleared(self):
        """overlay が差し替える設定は、すべて DB から消す対象になっていること。

        消し忘れたキーは overlay が効かず、スタブではなく実サービスへ出ていく。
        """
        defined = set(app_setting_keys().values())
        overridden = {v.lower() for v in overlay_env_vars()} & defined
        missing = sorted(overridden - cleared_keys())
        self.assertEqual(
            [],
            missing,
            "docker-compose.e2e-stubs.yml が差し替えているのに "
            "e2e-clear-llm-db-overrides.sh の KEYS に無い設定キー: " + str(missing),
        )

    def test_comfyui_base_url_is_cleared(self):
        """#1106 の回帰テスト。comfyui_base_url は管理APIから保存できるキーである。"""
        self.assertIn("comfyui_base_url", app_setting_keys().values())
        self.assertIn(
            "comfyui_base_url",
            cleared_keys(),
            "comfyui_base_url の DB 行は comfyui-stub の向き先を無効化する",
        )

    def test_header_does_not_claim_comfyui_base_url_is_untouched(self):
        """ヘッダコメントが「comfyui_base_url には触れない」と書いたままにしない。"""
        sentences = script_header().split("。")
        contradictions = [
            s for s in sentences if "comfyui_base_url" in s and "触れない" in s
        ]
        self.assertEqual(
            [],
            contradictions,
            "KEYS の実態と矛盾するコメントが残っている: " + str(contradictions),
        )


if __name__ == "__main__":
    unittest.main()
