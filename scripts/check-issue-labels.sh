#!/bin/bash
# issue #1023: open な Issue のステータス/優先度ラベルが、それぞれちょうど1つであることを検査する。
#
# なぜ必要か:
#
# GitHub Projects の Status は単一選択フィールドで、1つの Issue が2つのステータスを
# 持つことは構造的に不可能だった。GitLab Community Edition にその保証は無い。
# ステータスは通常ラベルであり、スコープ付きラベル(排他性を持つもの)は Premium 以上である。
#
# `.claude/hooks/guard.py` の `check_status_label_integrity` が、エージェントと CLI 経由の
# 壊し方を予防する。しかしフックは **Web UI からの手操作を見られない**。破れたことに
# 気づく手段が別に要る。それがこのスクリプトである。
#
# とくに **0個** が危険である。ステータスの無い Issue はボードのどの列にも現れず、
# `work-next` からも `triage-backlog` からも見えない。誰も困らないまま忘れられる。
# 2個は「どの段にいるか決まらない」という別種の壊れ方で、原因も対処も違うため、
# 出力では区別して報告する。
#
# 使い方:
#   scripts/check-issue-labels.sh
#
# 違反があれば非0で終了する。

set -uo pipefail

if ! command -v glab >/dev/null 2>&1; then
    echo "エラー: glabコマンドが見つかりません。" >&2
    exit 2
fi

if ! ISSUES="$(glab api "projects/:id/issues?per_page=100&state=opened" 2>&1)"; then
    echo "エラー: Issue の取得に失敗しました。検査せずに中断します。" >&2
    echo "$ISSUES" | head -3 >&2
    exit 2
fi

TOTAL="$(echo "$ISSUES" | jq 'length')"

# 各 Issue の status:: / priority:: の個数を数える。
REPORT="$(echo "$ISSUES" | jq -r '
  .[]
  | . as $i
  | ([$i.labels[] | select(startswith("status::"))]) as $s
  | ([$i.labels[] | select(startswith("priority::"))]) as $p
  | select(($s | length) != 1 or ($p | length) != 1)
  | "\($i.iid)\t\($s | length)\t\($p | length)\t\($s | join(", "))\t\($p | join(", "))\t\($i.title[0:56])"
')"

echo "検査対象: open な Issue ${TOTAL} 件"

if [ -z "$REPORT" ]; then
    echo "違反なし。全ての Issue が status:: と priority:: をちょうど1つずつ持っている。"
    exit 0
fi

echo
echo "=== 違反 ==="
# 0個と2個以上は原因も対処も違うので、行ごとに何が起きているかを書く。
while IFS=$'\t' read -r iid ns np slabels plabels title; do
    [ -z "$iid" ] && continue
    echo "#${iid} ${title}"
    if [ "$ns" -eq 0 ]; then
        echo "  ステータス: **無し**。ボードのどの列にも現れず、work-next からも triage からも見えない。"
        echo "    対処: 妥当な段を判断して1つ付ける。どの段か不明なら status::Inbox に戻す。"
    elif [ "$ns" -gt 1 ]; then
        echo "  ステータス: **${ns}個** (${slabels})。どの段にいるか決まらない。"
        echo "    対処: 正しい1つを残し、他を同じ呼び出しで remove_labels する。"
    fi
    if [ "$np" -eq 0 ]; then
        echo "  優先度: **無し**。ready-issue は Priority で選ぶため、選択順の最下位に沈む。"
        echo "    対処: P0 / P1 / P2 のいずれかを付ける。"
    elif [ "$np" -gt 1 ]; then
        echo "  優先度: **${np}個** (${plabels})。選択順が決まらない。"
        echo "    対処: 正しい1つを残す。"
    fi
done <<< "$REPORT"

VIOLATIONS="$(echo "$REPORT" | grep -c . || true)"
echo
echo "違反 ${VIOLATIONS} 件。ラベルの付け替えは CLAUDE.md → How to change status に従うこと"
echo "(remove_labels と add_labels を同一の呼び出しで行う。labels= で上書きしない)。"
exit 1
