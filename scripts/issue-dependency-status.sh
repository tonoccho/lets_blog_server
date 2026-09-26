#!/bin/bash
# issue #751: Ready/Backlog判定の入力を実行ごとにぶれさせないための、依存状態のライブ取得。
#
# 背景(#751): #584に対して2026-08-29 01:27Zと01:32Zの5分間で正反対のReadiness判定が
# 投稿された。調べた結果、どちらのコメントも事実としては正しく、食い違っていたのは
# 「依存が解決済み」の定義だった。
#
#   - 01:27の判定: 依存が届けるはずだった実体(コード)を見た。C6の分割子#707-712と
#     C10の分割子#693-696はいずれもその時点でCLOSED、gatewayのapplication.ymlにも
#     publishing/platformのルートが既にあった → 着手可能
#   - 01:32の判定: 依存Issueそのものの状態を見た。#583も#575もOPEN → 着手不可
#     (#575がCLOSEDになったのは02:12:19Z。差し戻しコメントの40分後なので、
#      この事実主張も当時は正しかった)
#
# 親トラッキングIssue(#575)は分割子が全てDoneでも自身はOPENのまま残るため、
# 2つの判定が同時に成立してしまう。
#
# このスクリプトは判定そのものは行わない。判定の「入力」を、誰がいつ実行しても
# 同じ形で得られるように固定する。判定基準は .claude/CLAUDE.md の
# 「Dependency Resolution」節を参照すること。
#
# GitLab移行について(#1024):
#
# GitHubの `dependencies/blocked_by` は方向を持つ正式な依存リンクで、旧版の
# CLAUDE.md ルール1はこれを「唯一の状態ベースのブロッカー」と定めていた。
# **GitLab Community Edition にこれは無い。** `blocks` / `is_blocked_by` の
# リンク種別は Premium 以上で、CEで使えるのは方向を持たない `relates_to` だけである。
# したがって本スクリプトは「正式な依存リンク」ではなく「関連リンク」を出力し、
# それが状態ベースのブロッカーではないことを出力自身に明記する。
# ルール1の改訂については CLAUDE.md → Dependency Resolution を参照。
#
# 親子関係(旧 sub_issues)は REST には無い(404)。GraphQL の work item 階層なら
# CEでも取得できるので、そちらを使う。
#
# 使い方:
#   scripts/issue-dependency-status.sh <issue-number>
#
# 例:
#   scripts/issue-dependency-status.sh 584

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "使い方: $0 <issue-number>" >&2
    exit 2
fi

ISSUE="$1"

if ! [[ "$ISSUE" =~ ^[0-9]+$ ]]; then
    echo "エラー: Issue番号は数字で指定してください(指定値: $ISSUE)" >&2
    exit 2
fi

if ! command -v glab >/dev/null 2>&1; then
    echo "エラー: glabコマンドが見つかりません。" >&2
    exit 2
fi

# 取得に失敗したら必ず落とす。失敗を黙って空配列にすると「依存なし」と区別できず、
# 一時的なネットワーク/レート制限エラーが「ブロッカー無し」に化ける。それは #751 と
# 同じ種類の事故である。(set -euo pipefail の下では代入の失敗が無言終了になるため、
# ここで握って理由を出す)
fetch() {
    local path="$1"
    local label="$2"
    local out
    if ! out="$(glab api "$path" 2>&1)"; then
        echo "エラー: ${label} の取得に失敗しました。判定を出さずに中断します。" >&2
        echo "$out" | head -3 >&2
        exit 1
    fi
    echo "$out"
}

# Issue本体。存在しなければここで落ちる。
SELF="$(fetch "projects/:id/issues/${ISSUE}" "Issue #${ISSUE}")"

# 1行サマリ。状態はライブ取得した値のみを使う。
print_issue_line() {
    local n="$1"
    local prefix="$2"
    local json state title closed

    if ! json="$(glab api "projects/:id/issues/${n}" 2>/dev/null)"; then
        echo "${prefix}#${n} <取得失敗: このプロジェクトに存在しない可能性>"
        return
    fi

    state="$(echo "$json" | jq -r .state)"
    title="$(echo "$json" | jq -r .title)"
    # closed_at は GitHub から移行した Issue では null になっている(移行時に
    # 引き継がれなかった)。GitLab 上で閉じた Issue には入る。`-` は「閉じていない」
    # ではなく「不明」であり、state と混同しないこと。
    closed="$(echo "$json" | jq -r '.closed_at // "不明(移行前に閉じた可能性)"')"
    if [ "$state" = "opened" ]; then closed="-"; fi
    echo "${prefix}#${n} ${state} closed=${closed} ${title}"
}

echo "=== 対象 Issue ==="
print_issue_line "$ISSUE" ""

# 親トラッキングIssueの見落としを防ぐため、子アイテムの内訳を出す。
# 分割子が全てCLOSEDなのに親がOPEN、という#575のケースがここで可視化される。
#
# REST の sub_issues / children は GitLab CE には無い(404)。GraphQL の
# work item 階層ウィジェットならCEでも取れる。取得に失敗したときは「子は無い」と
# 区別できるよう明示する。黙って空扱いにすると、分割子が全てCLOSEDという事実を
# 見落としたまま判定が進む。
echo
echo "=== 子アイテム(親トラッキングIssueの見落とし防止) ==="
GQL='{ project(fullPath: "'"${GITLAB_PROJECT_PATH:-seiji/lets_blog_server}"'") { workItems(iid: "'"$ISSUE"'") { nodes { widgets { ... on WorkItemWidgetHierarchy { hasChildren children { nodes { iid state } } } } } } } }'
if CHILDREN_RAW="$(glab api graphql -f query="$GQL" 2>/dev/null)"; then
    CHILDREN="$(echo "$CHILDREN_RAW" \
        | jq -c '[.data.project.workItems.nodes[]?.widgets[]? | select(.children != null) | .children.nodes[]?]' 2>/dev/null || echo '[]')"
    CHILD_TOTAL="$(echo "$CHILDREN" | jq 'length')"
    if [ "$CHILD_TOTAL" -eq 0 ]; then
        echo "(子アイテムなし)"
    else
        CHILD_OPEN="$(echo "$CHILDREN" | jq '[.[] | select(.state == "OPEN")] | length')"
        echo "子アイテム ${CHILD_TOTAL}件(open ${CHILD_OPEN}件)"
        for n in $(echo "$CHILDREN" | jq -r '.[].iid'); do
            print_issue_line "$n" "  "
        done
        SELF_STATE="$(echo "$SELF" | jq -r .state)"
        if [ "$SELF_STATE" = "opened" ] && [ "$CHILD_OPEN" -eq 0 ]; then
            echo "  ※親はOPENだが子アイテムは全てCLOSED。#575 と同じ形である。"
            echo "    親がOPENであること自体をブロッカーにしてはいけない。"
        fi
    fi
else
    echo "※子アイテムの取得に失敗(有無は未確認)。GraphQLが使えない可能性がある。"
fi

echo
echo "=== 関連リンク (GitLab issue links) ==="
# GitLab CE のリンクは relates_to のみで、方向を持たない。GitHub の blocked_by と
# 同じものだと誤解されないよう、ここで明示する。
LINKS="$(fetch "projects/:id/issues/${ISSUE}/links" "関連リンク")"
if [ "$(echo "$LINKS" | jq 'length')" -eq 0 ]; then
    echo "(なし)"
else
    echo "$LINKS" | jq -r '.[] | "  #\(.iid) \(.state) link_type=\(.link_type // "relates_to") \(.title)"'
fi
echo
echo "  注意: GitLab CE のリンク種別は relates_to のみで、方向を持たない。"
echo "  これは GitHub の blocked_by とは違い、それ自体は状態ベースのブロッカーではない。"
echo "  判断すべきは「このIssueの受入基準を現在のコードベースに対して実装し検証できるか」。"
echo "  (.claude/CLAUDE.md の Dependency Resolution を参照)"

echo
echo "=== 本文の依存節が挙げる Issue ==="
# 依存の見出しから次の見出しまでを切り出し、#<数字>を拾う。
# 見出しの表記は揺れる(「## 依存」「## 依存関係」「## Dependencies」)ので前方一致で拾う。
DEP_SECTION="$(echo "$SELF" | jq -r '.description // ""' \
    | awk '
        /^#+[[:space:]]*(依存|Dependencies|Depends)/ { inside = 1; next }
        inside && /^#+[[:space:]]/ { inside = 0 }
        inside { print }
    ')"

if [ -z "$(echo "$DEP_SECTION" | tr -d '[:space:]')" ]; then
    echo "(依存の節が無い、または空)"
else
    DEP_NUMBERS="$(echo "$DEP_SECTION" | grep -oE '#[0-9]+' | tr -d '#' | sort -un || true)"
    # 「依存は無い」と明示している場合と、依存はあるが識別子で書かれていない場合を区別する。
    # 前者に後者の警告を出すと、依存ゼロのIssueを「依存不明」と誤って扱わせてしまう。
    # trはバイト単位で動くため、ここで多バイト文字を消してはいけない
    # (`tr -d '。'` は E3/80/82 の各バイトを消すので「なし」のE3まで壊す)。
    # 空白除去とlower化はASCIIバイトしか触らないので安全。句読点は正規表現側で吸収する。
    DEP_COMPACT="$(echo "$DEP_SECTION" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    # 箇条書きの「- なし」や、「なし。単独でデリバリ可能。」のように説明文が続く形も
    # 依存ゼロと読む(#1139)。ただし A4 / B6 のような Epic 略記が1つでも書かれていれば
    # 依存ゼロとは読まない(検出を弱めない)。
    DEP_FIRST="$(echo "$DEP_SECTION" | grep -v '^[[:space:]]*$' | head -n1 \
        | sed -E 's/^[[:space:]]*([-*+][[:space:]]*)?//' | tr '[:upper:]' '[:lower:]')"
    DEP_DECLARES_NONE=0
    if [[ "$DEP_COMPACT" =~ ^(なし|無し|特になし|none|n/a|na|-)[。.]?$ ]]; then
        DEP_DECLARES_NONE=1
    elif [[ "$DEP_FIRST" =~ ^(なし|無し|特になし|none|n/a) ]] \
        && ! echo "$DEP_SECTION" | grep -qE '(^|[^A-Za-z0-9])[A-Za-z]{1,2}[0-9]+([^A-Za-z0-9]|$)'; then
        DEP_DECLARES_NONE=1
    fi
    if [ -z "$DEP_NUMBERS" ] && [ "$DEP_DECLARES_NONE" = 1 ]; then
        echo "(依存なしと明記されている)"
        echo "  | $(echo "$DEP_SECTION" | tr -d '\n' | sed 's/^[[:space:]]*//')"
        echo "  → 依存ゼロ。これは「依存が識別できない」状態とは別物であり、"
        echo "    Dependencies の判定は PASS 側に倒してよい。"
    elif [ -z "$DEP_NUMBERS" ]; then
        echo "(節はあるが解決可能なIssue参照が無い。本文は以下のとおり)"
        echo "$DEP_SECTION" | sed 's/^/  | /'
        echo
        echo "  警告: 依存が識別子として書かれていない。#584がまさにこの形("
        echo "  「A4, B6, C14」というEpicコードの略記)で、実行のたびに"
        echo "  「ラベル→Issue番号」の翻訳が必要になる。この翻訳が実行ごとにぶれるため、"
        echo "  判定も揺れる(#751)。"
        echo "  対処: 判定を出す前に、Epicコードが指すIssue番号を特定して本文を更新すること。"
        echo "  特定できないなら、その旨を判定コメントに書く(「依存不明のため差し戻し」は"
        echo "  許容されるが、「依存が未解決のため差し戻し」と断定してはいけない)。"
    else
        for n in $DEP_NUMBERS; do
            print_issue_line "$n" "  "
        done
        echo
        echo "  注意: ここに挙がったIssueがOPENであること自体はブロッカーではない。"
        echo "  判断すべきは「このIssueの受入基準を現在のコードベースに対して実装し検証できるか」。"
        echo "  親がOPENでも子アイテムが全てCLOSEDで実体がコードにあるなら着手可能と判定してよい。"
        echo "  どちらの根拠で判定したかは、判定コメントに必ず明記すること。"
    fi
fi

echo
echo "=== 既存の Readiness 判定コメント ==="
# 逆向きの判定を無自覚に投稿しないための材料。判定を覆すときは、
# ここに出たコメントが挙げた根拠を1つずつライブで再確認すること(#751)。
NOTES="$(fetch "projects/:id/issues/${ISSUE}/notes" "コメント")"
COMMENTS="$(echo "$NOTES" \
    | jq -r '[.[]
        | select(.body | test("READY|Ready|Backlog|readiness|Readiness"))
        | "\(.created_at)\t\(.body | split("\n")[0:2] | join(" / ") | .[0:160])"] | .[]')"

if [ -z "$COMMENTS" ]; then
    echo "(なし)"
else
    echo "$COMMENTS" | sed 's/^/  /'
    echo
    echo "  注意: これから出す判定が直近の判定と逆向きなら、そのまま差し戻さず、"
    echo "  直近コメントが挙げた根拠を1つずつライブで再確認し、どれが現在は成立しないのかを"
    echo "  新しいコメントに明示すること(#751)。"
fi
