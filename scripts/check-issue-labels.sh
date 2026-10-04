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

# issue #1031 (レビュー再検討): per_page=100 の1ページ目だけでは、それを超える件数の
# Issue が検査対象から静かに漏れる。空ページに達するまでページを辿る。
fetch_all_pages() {
    local state="$1"
    local page=1
    local all="[]"
    local resp n
    while :; do
        if ! resp="$(glab api "projects/:id/issues?per_page=100&state=${state}&page=${page}" 2>&1)"; then
            echo "エラー: Issue(state=${state})の取得に失敗しました。検査せずに中断します。" >&2
            echo "$resp" | head -3 >&2
            return 2
        fi
        n="$(echo "$resp" | jq 'length' 2>/dev/null)"
        if [ -z "$n" ] || [ "$n" -eq 0 ]; then
            break
        fi
        all="$(jq -s 'add' <(echo "$all") <(echo "$resp"))"
        page=$((page + 1))
        # 安全弁: 5000件を超える運用は想定していない。無限ループを避ける。
        if [ "$page" -gt 50 ]; then
            break
        fi
    done
    echo "$all"
}

if ! ISSUES="$(fetch_all_pages opened)"; then
    exit 2
fi

if ! CLOSED_ISSUES="$(fetch_all_pages closed)"; then
    exit 2
fi

TOTAL="$(echo "$ISSUES" | jq 'length')"
CLOSED_TOTAL="$(echo "$CLOSED_ISSUES" | jq 'length')"

# 各 Issue の status:: / priority:: の個数を数える。
REPORT="$(echo "$ISSUES" | jq -r '
  .[]
  | . as $i
  | ([$i.labels[] | select(startswith("status::"))]) as $s
  | ([$i.labels[] | select(startswith("priority::"))]) as $p
  | select(($s | length) != 1 or ($p | length) != 1)
  | "\($i.iid)\t\($s | length)\t\($p | length)\t\($s | join(", "))\t\($p | join(", "))\t\($i.title[0:56])"
')"

# issue #1031: マージ前提の事後検出(方式C、レビュー再検討版)。
#
# `merge-request` が作る MR の説明文は常に `Closes #<issue-number>` を含む
# (`.claude/skills/merge-request/SKILL.md`)。GitLab はマージ時にこれを見て、
# ラベルとは無関係に Issue を自動的に `state: closed` にする。したがって
# 「マージされて閉じたのに status::Done への付け替えを忘れた」(#959 の壊れ方)は、
# **closed だが status::Done を持っていない Issue** として検出できる。
#
# ただし単純な「state=closed かつ status::Done でない」だけでは、
# `status::` 運用が始まる前の過去 Issue や、重複・却下など merge と無関係な理由で
# closed になった Issue まで大量に拾ってしまう(#1031 の独立レビューで実測47件)。
# それは Out of Scope #4(既存 Issue の遷移履歴は問わない)にも反する。
# 二重に絞る:
#
#   1. カットオフ: closed_at が CUTOFF(#1023 がマージされた日時)以降の Issue だけを見る。
#      それより前は「過去の遷移履歴」であり、検査の対象外。
#   2. MR 突き合わせ: `issues/<iid>/closed_by` で実際に **merged な** MR が
#      閉じたことを確認する。手動 close は #959 パターンではない。
CUTOFF="2026-09-03T02:44:25Z"

CLOSED_CANDIDATES="$(echo "$CLOSED_ISSUES" | jq -r --arg cutoff "$CUTOFF" '
  .[]
  | . as $i
  | ([$i.labels[] | select(startswith("status::"))]) as $s
  | select(($s | join(",")) != "status::Done")
  | select(($s | length) == 1)
  | select(($i.closed_at // "") >= $cutoff)
  | "\($i.iid)\t\($s | join(", "))\t\($i.title[0:56])"
')"

# issue #1548: closed の status:: 個数は、閉じ方(MR か手動か)に関係なく検査する。
# 手動で閉じて Web UI でラベルを付け替え損ねた Issue(#1350: 0個)は closed_by に
# merged な MR が無く、上の方式Cでは拾えない。個数が1でないものはここで報告し、
# 方式Cの対象(ちょうど1個で Done でないもの)とは重複させない。
CLOSED_COUNT_REPORT="$(echo "$CLOSED_ISSUES" | jq -r --arg cutoff "$CUTOFF" '
  .[]
  | . as $i
  | ([$i.labels[] | select(startswith("status::"))]) as $s
  | select(($s | length) != 1)
  | select(($i.closed_at // "") >= $cutoff)
  | "\($i.iid)\t\($s | length)\t\($s | join(", "))\t\($i.title[0:56])"
')"

CLOSED_REPORT=""
if [ -n "$CLOSED_CANDIDATES" ]; then
    while IFS=$'\t' read -r iid slabels title; do
        [ -z "$iid" ] && continue
        if ! CLOSED_BY="$(glab api "projects/:id/issues/${iid}/closed_by" 2>&1)"; then
            echo "エラー: Issue #${iid} の closed_by 取得に失敗しました。検査せずに中断します。" >&2
            echo "$CLOSED_BY" | head -3 >&2
            exit 2
        fi
        HAS_MERGED_MR="$(echo "$CLOSED_BY" | jq '[.[] | select(.state == "merged")] | length')"
        if [ "$HAS_MERGED_MR" -gt 0 ]; then
            CLOSED_REPORT="${CLOSED_REPORT}${iid}"$'\t'"${slabels}"$'\t'"${title}"$'\n'
        fi
    done <<< "$CLOSED_CANDIDATES"
    CLOSED_REPORT="${CLOSED_REPORT%$'\n'}"
fi

# issue #1433: CLAUDE.md → Issue Provenance → hotfix。open な Issue で最大3件。
# guard.py は既存 Issue への hotfix の付与・削除そのものを拒否するが、件数の上限は
# 判定しない(現在の件数を知るには API 問い合わせが要り、guard.py はネットワークを
# 使わない方針)。ここが上限超過の事後検出を担う。closed な hotfix は数えない —
# `complete-issue` が Done に付け替えても hotfix ラベル自体を外す必要が無いようにする。
HOTFIX_CAP=3
HOTFIX_ISSUES="$(echo "$ISSUES" | jq -r '
  .[]
  | select(.labels | index("hotfix") != null)
  | "\(.iid)\t\(.title[0:56])"
')"
HOTFIX_COUNT=0
if [ -n "$HOTFIX_ISSUES" ]; then
    HOTFIX_COUNT="$(echo "$HOTFIX_ISSUES" | grep -c . || true)"
fi

echo "検査対象: open な Issue ${TOTAL} 件、closed な Issue ${CLOSED_TOTAL} 件"

VIOLATIONS=0

if [ -n "$REPORT" ]; then
    echo
    echo "=== 違反(status:: / priority:: の個数)==="
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
    VIOLATIONS=$(( VIOLATIONS + $(echo "$REPORT" | grep -c . || true) ))
fi

if [ -n "$CLOSED_REPORT" ]; then
    echo
    echo "=== 違反(マージ前提, #1031)==="
    while IFS=$'\t' read -r iid slabels title; do
        [ -z "$iid" ] && continue
        echo "#${iid} ${title}"
        echo "  ステータス: closed だが status::Done ではない(${slabels:-無し})。"
        echo "    Review/QA を経ずにマージされた可能性がある(#959)。"
        echo "    対処: 実際に Review/QA を経ていたなら status::Done に付け替える。"
        echo "          経ていなければ、何が省略されたかを調査すること。"
    done <<< "$CLOSED_REPORT"
    VIOLATIONS=$(( VIOLATIONS + $(echo "$CLOSED_REPORT" | grep -c . || true) ))
fi

if [ -n "$CLOSED_COUNT_REPORT" ]; then
    echo
    echo "=== 違反(closed の status:: の個数, #1548)==="
    while IFS=$'\t' read -r iid ns slabels title; do
        [ -z "$iid" ] && continue
        echo "#${iid} ${title}"
        if [ "$ns" -eq 0 ]; then
            echo "  ステータス: closed だが **無し**。閉じ方(MR か手動か)を問わず検出する。"
            echo "    対処: 実態に合う段(通常は status::Done)を1つ付ける。"
        else
            echo "  ステータス: closed で **${ns}個** (${slabels})。どの段か決まらない。"
            echo "    対処: 正しい1つを残し、他を同じ呼び出しで remove_labels する。"
        fi
    done <<< "$CLOSED_COUNT_REPORT"
    VIOLATIONS=$(( VIOLATIONS + $(echo "$CLOSED_COUNT_REPORT" | grep -c . || true) ))
fi

if [ "$HOTFIX_COUNT" -gt "$HOTFIX_CAP" ]; then
    echo
    echo "=== 違反(hotfix の上限超過, CLAUDE.md → Issue Provenance)==="
    echo "open な hotfix Issue が ${HOTFIX_COUNT} 件(上限 ${HOTFIX_CAP})。付与・削除はユーザーのみ:"
    while IFS=$'\t' read -r iid title; do
        [ -z "$iid" ] && continue
        echo "  #${iid} ${title}"
    done <<< "$HOTFIX_ISSUES"
    VIOLATIONS=$(( VIOLATIONS + 1 ))
fi

if [ "$VIOLATIONS" -eq 0 ]; then
    echo
    echo "違反なし。全ての Issue が status:: と priority:: をちょうど1つずつ持ち、"
    echo "closed な Issue はすべて status::Done になっている。"
    exit 0
fi

echo
echo "違反 ${VIOLATIONS} 件。ラベルの付け替えは CLAUDE.md → How to change status に従うこと"
echo "(remove_labels と add_labels を同一の呼び出しで行う。labels= で上書きしない)。"
exit 1
