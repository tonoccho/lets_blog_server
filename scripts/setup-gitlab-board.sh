#!/bin/bash
# GitLab CE 上に、CLAUDE.md のワークフロー
#   Inbox → Backlog → Ready → In Progress → Review → QA → Done
# に対応する Issue ボードとラベルを作る。
#
# 冪等: 既にあるラベル/ボード/リストは作り直さずスキップする。何度実行しても同じ状態になる。
#
# 前提: glab に PAT が設定済みであること(glab auth status で確認)。
#
# 使い方: bash setup-gitlab-board.sh

set -euo pipefail

# GitLab がプロジェクト作成時に自動で作る既定ボードの名前。新しく2枚目を作ると
# 利用者が最初に開くボード(既定)が空のままになるので、既定ボードにそのまま列を足す。
BOARD_NAME="Development"

# ステータスラベル。ボードの列になる。順序がそのままボードの左→右になる。
#
# `status::` という接頭辞は CE では単なる名前で、排他性は効かない(スコープ付きラベルは Premium)。
# それでもこの命名にしておくのは、
#   - ラベル一覧でグルーピングされて読みやすい
#   - 将来 EE/Premium に移った時点で、リネーム無しに排他性が自動で効く
#   - 排他性を強制するガードフックが、接頭辞で対象を特定できる
# ため。CE の間の排他性は、ガードフック側で担保する。
STATUS_LABELS=(
  "status::Inbox|#8C8C8C|未整理。discover-issues / plan-issue が最初に置く場所"
  "status::Backlog|#1F75CB|やる価値があると判断済み。着手可能かはまだ未判定"
  "status::Ready|#217645|要件・受入基準・依存が揃い、実装に着手してよい"
  "status::In Progress|#C17D10|implement-issue が作業中"
  "status::Review|#6E49CB|reviewer による独立レビュー中"
  "status::QA|#A63C6E|qa による受入基準の検証中"
  "status::Done|#2DA160|MR がマージされ、完了条件を満たした"
)

# 優先度ラベル。GitHub Projects の Priority フィールドの代替。
PRIORITY_LABELS=(
  "priority::P0|#DD2B0E|最優先。他を止めてでもやる"
  "priority::P1|#C17D10|通常の優先度"
  "priority::P2|#666666|後回し可"
)

# hotfix ラベル。選択順の第0キー(CLAUDE.md → Issue Provenance → hotfix)。
# 付与・削除はユーザーのみが行い、Claude は読むだけ。open な Issue で最大3件
# (上限超過の検出は scripts/check-issue-labels.sh、付け外し自体の拒否は guard.py)。
HOTFIX_LABEL=(
  "hotfix|#d9534f|緊急バグ。選択順の第0キー。付与・削除はユーザーのみ(Claudeは読むだけ)。openなIssueで最大3件"
)

api() { glab api "$@"; }

echo "=== 認証確認 ==="
if ! ME="$(api user 2>/dev/null)"; then
  echo "エラー: API を呼べません。先に glab auth login を済ませてください。" >&2
  exit 1
fi
echo "  ユーザー: $(echo "$ME" | jq -r '.username')"

echo
echo "=== プロジェクト確認 ==="
PROJ="$(api "projects/:id")"
echo "  $(echo "$PROJ" | jq -r '.path_with_namespace') (id=$(echo "$PROJ" | jq -r .id))"
echo "  Issue 有効: $(echo "$PROJ" | jq -r '.issues_access_level')"

echo
echo "=== 既存ラベル ==="
EXISTING_LABELS="$(api "projects/:id/labels?per_page=100" | jq -r '.[].name')"
if [ -z "$EXISTING_LABELS" ]; then
  echo "  (なし)"
else
  echo "$EXISTING_LABELS" | sed 's/^/  /'
fi

ensure_label() {
  local spec="$1"
  local name color desc
  name="${spec%%|*}"
  spec="${spec#*|}"
  color="${spec%%|*}"
  desc="${spec#*|}"

  if echo "$EXISTING_LABELS" | grep -Fxq "$name"; then
    echo "  スキップ(既存): $name"
    return
  fi
  api "projects/:id/labels" --method POST \
    --field "name=${name}" \
    --field "color=${color}" \
    --field "description=${desc}" >/dev/null
  echo "  作成: $name"
}

echo
echo "=== ステータスラベルの作成 ==="
for spec in "${STATUS_LABELS[@]}"; do ensure_label "$spec"; done

echo
echo "=== 優先度ラベルの作成 ==="
for spec in "${PRIORITY_LABELS[@]}"; do ensure_label "$spec"; done

echo
echo "=== hotfix ラベルの作成 ==="
for spec in "${HOTFIX_LABEL[@]}"; do ensure_label "$spec"; done

echo
echo "=== ボードの作成 ==="
BOARDS="$(api "projects/:id/boards")"
BOARD_ID="$(echo "$BOARDS" | jq -r --arg n "$BOARD_NAME" '.[] | select(.name == $n) | .id' | head -1)"
if [ -n "$BOARD_ID" ] && [ "$BOARD_ID" != "null" ]; then
  echo "  スキップ(既存): ${BOARD_NAME} (id=${BOARD_ID})"
else
  BOARD_ID="$(api "projects/:id/boards" --method POST \
    --field "name=${BOARD_NAME}" | jq -r '.id')"
  echo "  作成: ${BOARD_NAME} (id=${BOARD_ID})"
fi

echo
echo "=== ボード列(リスト)の作成 ==="
# ラベル名 → ラベルID を引き直す(作成直後の分も含める)。
LABEL_MAP="$(api "projects/:id/labels?per_page=100" | jq -c '[.[] | {name, id}]')"
EXISTING_LIST_LABEL_IDS="$(api "projects/:id/boards/${BOARD_ID}/lists" \
  | jq -r '.[] | .label.id // empty')"

for spec in "${STATUS_LABELS[@]}"; do
  name="${spec%%|*}"
  label_id="$(echo "$LABEL_MAP" | jq -r --arg n "$name" '.[] | select(.name == $n) | .id')"
  if [ -z "$label_id" ]; then
    echo "  警告: ラベルが見つかりません: $name" >&2
    continue
  fi
  if echo "$EXISTING_LIST_LABEL_IDS" | grep -Fxq "$label_id"; then
    echo "  スキップ(既存): $name"
    continue
  fi
  api "projects/:id/boards/${BOARD_ID}/lists" --method POST \
    --field "label_id=${label_id}" >/dev/null
  echo "  作成: $name"
done

echo
echo "=== 完成したボード ==="
api "projects/:id/boards/${BOARD_ID}" \
  | jq -r '"ボード: \(.name)\n列: " + ([.lists[] | .label.name] | join(" → "))'

echo
echo "ボード URL: $(echo "$PROJ" | jq -r '.web_url')/-/boards/${BOARD_ID}"
