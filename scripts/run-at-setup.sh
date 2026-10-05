#!/bin/bash
# 受け入れテストの at-setup 段階を単体で流し、終了時に合成アカウントを必ず揃える(issue #1634)。
#
# ■ なぜ入口が要るか
#
#   リセット(reset-acceptance-env.sh)は letsblog レルムの *@letsblog.local を全部消す。
#   at-setup(@stage:setup)は最初の管理者 e2e-admin@ しか作らず、e2e-test@ を作り直すのは
#   at-seed(seed-acceptance-env.sh)である。リセットと `--project=at-setup` を手で組み合わせて
#   終わると、共有スタックには e2e-test@ が無いまま残り、後続の AT がすべて落ちる(2026-10-04 #1553)。
#   `--project=at-seed` でシードを呼ぶのも誤り: 依存先の at-setup が再実行され、ユーザーが
#   既に存在するため必ず落ち、at-seed がスキップされる。
#
# ■ 何をするか
#
#   1. データ層リセット + at-setup を1回の Playwright 実行で行う。
#      リセットは `ACCEPTANCE_RESET=data` として globalSetup(apps/web/e2e/global-setup.ts)に
#      任せる。globalSetup は AT の排他ロック(#1187)の内側で動くため、リセットが他の実行の
#      最中に共有スタックを壊すことが無い。この入口がリセットをロックの外で行うことはない。
#   2. at-setup の成否にかかわらず、同じロックを取り直して seed-acceptance-env.sh を直接実行する。
#   3. 終了コードは at-setup の結果を反映し、at-setup が通ってもシードが失敗したら非ゼロ。
#
# ■ 使い方
#
#   source ~/.config/lets-blog-e2e.env
#   scripts/run-at-setup.sh [playwright の追加引数...]     # または cd apps/web && npm run test:at:setup
#
# 破壊的(データ層を消す)。共有スタックの合成アカウントを消してから作り直す。
#
# 環境変数: AT_LOCK_FILE / AT_LOCK_TIMEOUT_SECONDS は apps/web/e2e/at-lock.ts と同じ意味。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
WEB_DIR="$REPO_ROOT/apps/web"

# at-lock.ts の lockFilePath() と同じ規則。
LOCK_FILE="${AT_LOCK_FILE:-${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/lets-blog-server-acceptance-test.lock}"
LOCK_TIMEOUT="${AT_LOCK_TIMEOUT_SECONDS:-7200}"

echo "=== 1/2 データ層リセット + at-setup ==="
( cd "$WEB_DIR" && rm -rf .features-gen && npx bddgen && \
  ACCEPTANCE_RESET=data npx playwright test --project=at-setup "$@" )
SETUP_RC=$?
echo "at-setup の終了コード: $SETUP_RC"

echo "=== 2/2 合成アカウントを復元します(at-setup の成否によらず実行) ==="
SEED_RC=0
(
  exec 9>>"$LOCK_FILE" || exit 1
  if ! flock -w "$LOCK_TIMEOUT" 9; then
    echo "エラー: 排他ロック($LOCK_FILE)を ${LOCK_TIMEOUT}秒 待っても獲得できませんでした。シードを実行していません。" >&2
    echo "       合成アカウントが欠けたままの可能性があります。ロックが空いたら scripts/seed-acceptance-env.sh を実行してください。" >&2
    exit 1
  fi
  "$SCRIPT_DIR/seed-acceptance-env.sh"
) || SEED_RC=$?
echo "シードの終了コード: $SEED_RC"

if [ "$SETUP_RC" -ne 0 ]; then
  exit "$SETUP_RC"
fi
exit "$SEED_RC"
