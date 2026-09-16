#!/usr/bin/env bash
# issue #1295 Requirement 3: 新規のログイン送信経路が、共通ロック(機構1対策)にも
# 使い捨てアカウントへの隔離(機構2対策)にも乗らずに素通りするのを防ぐ。
#
# 検査対象は `apps/web/e2e/` 配下(`CHECK_E2E_LOGIN_ROUTES_DIR` で上書き可、単体テスト用)で、
# 実際にKeycloakへ資格情報を送る2つのパターン:
#
#   - `.locator('#kc-login').click()` / `.focus()` / 短縮形の `.click('#kc-login')`
#     ブラウザUIログインの送信ボタン。引用符はシングル・ダブルどちらでもよい。
#     単一セレクタの `#kc-login` だけに一致させる。デバイス認可の同意ボタン
#     (`'#kc-login, input[name="accept"]...'` のような複数セレクタの一部としての
#     `#kc-login`)はログインフォームの送信ではなく承認操作であり対象外——
#     このパターンには一致しないことで自然に除外される。
#   - `grant_type: 'password'`                       Resource Owner Password Credentialsグラント。
#     キー・値の引用符とコロン前後の空白の揺れを吸収する。
#
# それぞれの一致箇所の直前5行以内に `e2e-login-guard:<category>` という注釈コメントが
# 無ければ検査は失敗する。category は次のいずれか(#1295 Requirement 2 の3分類と対応):
#
#   - locked              : 共通のアカウント単位ロック(withAccountLock)を経由する
#   - disposable           : このシナリオ専用の使い捨てアカウントへ隔離済み
#   - already-disposable   : 元から使い捨てアカウントのみを使っており処置不要
#
# 新規のログイン経路がこの3つのいずれの注釈も付けずに追加されると、この検査が失敗する。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${CHECK_E2E_LOGIN_ROUTES_DIR:-$REPO_ROOT/apps/web/e2e}"

if [ ! -d "$TARGET_DIR" ]; then
  echo "検査対象ディレクトリが見つかりません: $TARGET_DIR" >&2
  exit 1
fi

ANNOTATION_RE='e2e-login-guard:(locked|disposable|already-disposable)'
fail=0

check_pattern() {
  local grep_pattern="$1"
  local match
  while IFS= read -r match; do
    [ -z "$match" ] && continue
    local file="${match%%:*}"
    local rest="${match#*:}"
    local line="${rest%%:*}"
    local start=$((line - 5))
    if [ "$start" -lt 1 ]; then
      start=1
    fi
    if ! sed -n "${start},${line}p" "$file" | grep -qE "$ANNOTATION_RE"; then
      echo "UNGUARDED: ${file#"$REPO_ROOT"/}:${line} — 直前5行以内に e2e-login-guard: 注釈が見つからない" >&2
      fail=1
    fi
  done < <(grep -rnE "$grep_pattern" "$TARGET_DIR" --include='*.ts' --include='*.tsx' || true)
}

# クォートの種類・コロン前後の空白・Playwrightの短縮形(page.click('#kc-login'))といった
# 書き方の揺れで素通りしないよう、リテラル一致ではなく揺れを吸収する形にしてある
# (issue #1295 レビュー指摘1)。`#kc-login` は閉じ引用符が直後に来る単一セレクタだけに
# 一致するので、同意ボタンの複数セレクタ('#kc-login, input[name="accept"]')は
# 従来どおり対象外のままである。
check_pattern "(locator|click|focus)\\((['\"])#kc-login\\2\\)"
check_pattern "['\"]?grant_type['\"]?[[:space:]]*:[[:space:]]*['\"]password['\"]"

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "新規/未処置のKeycloakログイン送信経路が見つかりました。docs/ACCEPTANCE_TESTING.md の" >&2
  echo "『E2Eログイン経路の列挙』を参照し、既存の withAccountLock 経由にするか使い捨て" >&2
  echo "アカウントへ隔離したうえで e2e-login-guard: 注釈を付けること(issue #1295)。" >&2
  exit 1
fi

echo "OK: すべてのKeycloakログイン送信経路がガード注釈済み ($TARGET_DIR)"
