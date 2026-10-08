#!/usr/bin/env python3
"""稼働中コンテナが `docker-compose.e2e-stubs.yml` を重ねて作られたかを確認する(#1683)。

## 背景

ai / media が外部サービスのスタブへ向くのは、overlay の `SPRING_PROFILES_ACTIVE: e2e-stubs` と
`BRAVE_SEARCH_BASE_URL` による。overlay を重ねずに `docker compose up -d <service>` で作り直した
コンテナだけが実サービスへ向き、AT は認証エラー(#1515 の 422、#1648 の 401)で散発的に落ちる。

## 判定

compose プロジェクト `lets_blog_server` の各コンテナの `com.docker.compose.project.config_files`
ラベル(カンマ区切り、実機では絶対パス)のファイル名に `docker-compose.e2e-stubs.yml` が含まれるか。含まないコンテナが1つでもあれば非0で中断し、
サービス名と作り直しのコマンドを表示する。`AT_STUB_OVERLAY_CHECK_BYPASS=1` で、迂回と該当サービス名を
標準出力に記録したうえで続行できる。docker に問い合わせられないときは省略する(#1653 と同じ)。

## 使い方(`apps/web/e2e/global-setup.ts` から、イメージ鮮度確認の後に呼ばれる)

    python3 scripts/check-stub-overlay.py

単体テスト: `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`
"""

import os
import subprocess
import sys

BYPASS_ENV = "AT_STUB_OVERLAY_CHECK_BYPASS"
COMPOSE_PROJECT_NAME = "lets_blog_server"
OVERLAY_FILE = "docker-compose.e2e-stubs.yml"
CONFIG_FILES_LABEL = "com.docker.compose.project.config_files"
SERVICE_LABEL = "com.docker.compose.service"


class DockerUnavailable(Exception):
    """docker に問い合わせられない(デーモンが無い等)。"""


def _docker(args):
    """`docker <args>` を実行し `(returncode, stdout, stderr)` を返す。単体テストはこれを差し替える。"""
    try:
        r = subprocess.run(
            ["docker"] + list(args), capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


def find_missing():
    """overlay を重ねずに作られたコンテナのサービス名(重複なし、出現順)。"""
    code, out, err = _docker(
        [
            "ps",
            "--filter",
            "label=com.docker.compose.project=%s" % COMPOSE_PROJECT_NAME,
            "--format",
            '{{.Label "%s"}}\t{{.Label "%s"}}' % (SERVICE_LABEL, CONFIG_FILES_LABEL),
        ]
    )
    if code != 0:
        raise DockerUnavailable(err.strip() or "docker ps に失敗しました")
    missing = []
    for line in out.splitlines():
        if not line.strip():
            continue
        service, _, files = line.partition("\t")
        if OVERLAY_FILE not in [os.path.basename(f) for f in files.split(",")] and service not in missing:
            missing.append(service)
    return missing


def check():
    """(ok, message) を返す。`ok=False` は受け入れテストを開始してはならないことを意味する。"""
    bypass = os.environ.get(BYPASS_ENV) == "1"
    try:
        missing = find_missing()
    except DockerUnavailable as e:
        return True, "docker に問い合わせられないため、スタブ overlay の確認を省略します(%s)" % e

    if not missing:
        note = (
            "(%s=1 が指定されていますが、外れているコンテナはありません)" % BYPASS_ENV
            if bypass
            else ""
        )
        return True, "全コンテナが %s を重ねて作られています%s" % (OVERLAY_FILE, note)

    names = " ".join(missing)
    if bypass:
        return True, (
            "%s=1 のため、overlay の外れたコンテナのまま続行します(迂回)。該当サービス: %s"
            % (BYPASS_ENV, names)
        )
    return False, (
        "エラー: %s を重ねずに作られたコンテナがあります(#1683)。\n"
        "外部サービスの実体へ向き、認証エラーで散発的に落ちます。該当サービス: %s\n"
        "overlay を重ねて作り直してください(スタックを起動したときと同じ -f を付けること):\n"
        "  docker compose -f docker-compose.yml -f %s [-f docker-compose.shared-host.yml] "
        "up -d --force-recreate %s\n"
        "意図してこのまま実行するには %s=1 を指定してください"
        "(該当サービス名が標準出力に記録されます)。"
        % (OVERLAY_FILE, names, OVERLAY_FILE, names, BYPASS_ENV)
    )


def main(argv):
    if len(argv) != 1:
        print("使い方: check-stub-overlay.py", file=sys.stderr)
        return 2
    ok, message = check()
    if ok:
        print(message)
        return 0
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
