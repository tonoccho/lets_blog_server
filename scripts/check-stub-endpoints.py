#!/usr/bin/env python3
"""DB の LLM / ComfyUI の接続先がスタブを向いているかを確認する(#1703)。

## 背景

#1567 以降、Ollama / ComfyUI の接続先は DB(`lbs_platform.system_settings` の
`llm_ollama_base_url` / `comfyui_base_url`)だけで決まる。ゼロ構築後は `ConnectionDefaultsSeeder` が
本番用の既定値(`http://ollama:11434/v1` / `http://comfyui:8188`)を書くため、
`scripts/e2e-clear-llm-db-overrides.sh --yes` でスタブの URL へ入れ替えないと、AI 系シナリオは
起動していない実サービスを呼んで落ちる(2026-10-09 のリリース検証)。

## 判定

2 キーの値(`CredentialCipher` 形式で暗号化されている)を `.env` の `APP_ENCRYPTION_KEY` で復号し、
`e2e-clear-llm-db-overrides.sh` が投入するスタブの URL と**厳密一致**するか。行が無い・復号できない・
一致しないキーが 1 つでもあれば非0で中断し、原因と対処のコマンドを表示する。
`AT_STUB_ENDPOINT_CHECK_BYPASS=1` で、迂回と該当キーを標準出力に記録したうえで続行できる
(#1683 の `AT_STUB_OVERLAY_CHECK_BYPASS` と同型)。DB に問い合わせられないときは省略する(#1653 と同じ)。

## 使い方(`apps/web/e2e/global-setup.ts` から、healthy 待ちの後に呼ばれる)

    python3 scripts/check-stub-endpoints.py

単体テスト: `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`
"""

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import e2e_encrypt_setting  # noqa: E402

BYPASS_ENV = "AT_STUB_ENDPOINT_CHECK_BYPASS"
ENV_FILE = os.path.join(HERE, "..", ".env")
MYSQL_CONTAINER = "lbs-mysql"
#: `e2e-clear-llm-db-overrides.sh` の STUB_SEEDS と同じ値。食い違いは単体テストの往復で検出する。
STUB_ENDPOINTS = (
    ("llm_ollama_base_url", "http://llm-stub:8080"),
    ("comfyui_base_url", "http://comfyui-stub:8080"),
)
UNDECRYPTABLE = "<復号できません>"
FIX_COMMAND = "./scripts/e2e-clear-llm-db-overrides.sh --yes"


class DatabaseUnavailable(Exception):
    """DB に問い合わせられない(コンテナが無い・資格情報が無い等)。"""


def _docker(args):
    """`docker <args>` を実行し `(returncode, stdout, stderr)` を返す。単体テストはこれを差し替える。"""
    try:
        r = subprocess.run(["docker"] + list(args), capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


def _env_value(name):
    """`.env` の `NAME=value`(最初の一致)。無ければ空文字。"""
    try:
        with open(ENV_FILE, encoding="utf-8") as f:
            for line in f:
                if line.startswith(name + "="):
                    return line[len(name) + 1 :].rstrip("\n")
    except OSError:
        pass
    return ""


def _app_encryption_key():
    key = _env_value("APP_ENCRYPTION_KEY")
    if not key:
        raise DatabaseUnavailable(".env の APP_ENCRYPTION_KEY が未設定です")
    return key


def _query_settings():
    """2 キーの `{setting_key: 暗号化値の16進}`。行が無いキーは含まれない。"""
    password = _env_value("MYSQL_ROOT_PASSWORD")
    if not password:
        raise DatabaseUnavailable(".env の MYSQL_ROOT_PASSWORD が未設定です")
    keys = ",".join("'%s'" % k for k, _ in STUB_ENDPOINTS)
    sql = (
        "SELECT setting_key, HEX(setting_value_encrypted) FROM lbs_platform.system_settings "
        "WHERE setting_key IN (%s);" % keys
    )
    code, out, err = _docker(
        ["exec", "-i", MYSQL_CONTAINER, "mysql", "-uroot", "-p" + password, "-N", "-B", "-e", sql]
    )
    if code != 0:
        raise DatabaseUnavailable(err.strip() or "mysql への問い合わせに失敗しました")
    found = {}
    for line in out.splitlines():
        key, _, value = line.partition("\t")
        if key.strip() and value.strip():
            found[key.strip()] = value.strip()
    return found


def find_mismatches():
    """スタブを向いていないキーの `(キー, 実際の値 / None=行なし / UNDECRYPTABLE, 期待する値)`。"""
    settings = _query_settings()
    key = _app_encryption_key()
    mismatches = []
    for name, expected in STUB_ENDPOINTS:
        if name not in settings:
            mismatches.append((name, None, expected))
            continue
        try:
            actual = e2e_encrypt_setting.decrypt_hex(key, settings[name])
        except Exception:  # 鍵違い・壊れた値。どれも「スタブを向いていない」として扱う。
            actual = UNDECRYPTABLE
        if actual != expected:
            mismatches.append((name, actual, expected))
    return mismatches


def check():
    """(ok, message) を返す。`ok=False` は受け入れテストを開始してはならないことを意味する。"""
    bypass = os.environ.get(BYPASS_ENV) == "1"
    try:
        mismatches = find_mismatches()
    except DatabaseUnavailable as e:
        return True, "DB に問い合わせられないため、スタブ接続先の確認を省略します(%s)" % e

    if not mismatches:
        note = (
            "(%s=1 が指定されていますが、外れているキーはありません)" % BYPASS_ENV if bypass else ""
        )
        return True, "LLM / ComfyUI の接続先はスタブを向いています%s" % note

    detail = "\n".join(
        "  - %s = %s (期待: %s)" % (name, "(行なし)" if actual is None else actual, expected)
        for name, actual, expected in mismatches
    )
    if bypass:
        return True, (
            "%s=1 のため、スタブを向いていない接続先のまま続行します(迂回)。該当キー:\n%s"
            % (BYPASS_ENV, detail)
        )
    return False, (
        "エラー: DB の LLM / ComfyUI の接続先がスタブを向いていません(#1703)。\n"
        "ゼロ構築直後は ConnectionDefaultsSeeder が本番の既定値を書くため、AI 系シナリオが実サービスへ出て落ちます。\n"
        "%s\n"
        "接続先をスタブへ向けてください(platform が再起動されます):\n"
        "  %s\n"
        "意図してこのまま実行するには %s=1 を指定してください"
        "(該当キーが標準出力に記録されます)。" % (detail, FIX_COMMAND, BYPASS_ENV)
    )


def main(argv):
    if len(argv) != 1:
        print("使い方: check-stub-endpoints.py", file=sys.stderr)
        return 2
    ok, message = check()
    if ok:
        print(message)
        return 0
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
