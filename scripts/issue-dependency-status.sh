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
# 2つの判定が同時に成立してしまう。加えて#584には正式なblocked_byリンクが1本も無く
# (`dependencies/blocked_by`は空配列を返す)、依存関係は本文の散文としてしか存在しない。
# 散文は実行のたびに解釈し直されるので、解釈が揺れる。
#
# このスクリプトは判定そのものは行わない。判定の「入力」を、誰がいつ実行しても
# 同じ形で得られるように固定する。判定基準は .claude/CLAUDE.md の
# 「Dependency Resolution」節を参照すること。
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

if ! command -v gh >/dev/null 2>&1; then
    echo "エラー: ghコマンドが見つかりません。bin/loop exec 経由で実行してください。" >&2
    exit 2
fi

if ! REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)" || [ -z "$REPO" ]; then
    echo "エラー: リポジトリを特定できませんでした(ghの認証切れ、またはリポジトリ外での実行)" >&2
    exit 1
fi

# gh api を叩いて配列を返す。失敗を黙って空配列にすると「依存なし」と区別できず、
# 一時的なネットワーク/レート制限エラーが「ブロッカー無し」に化けるので、明示的に落とす。
# (set -euo pipefail の下では代入の失敗が無言終了になるため、ここで握って理由を出す)
fetch_array() {
    local path="$1"
    local out
    if ! out="$(gh api "$path" --paginate 2>&1)"; then
        echo "エラー: $path の取得に失敗しました。判定を出さずに中断します。" >&2
        echo "$out" | head -3 >&2
        exit 1
    fi
    echo "$out" | jq -s 'add // []'
}

# Issue本体。存在しなければここで落ちる。
if ! SELF="$(gh issue view "$ISSUE" --repo "$REPO" --json number,title,state,body 2>/dev/null)"; then
    echo "エラー: Issue #$ISSUE を取得できませんでした($REPO)" >&2
    exit 1
fi

# 1行サマリを出す。状態はライブ取得した値のみを使う。
print_issue_line() {
    local n="$1"
    local prefix="$2"
    local json state title closed

    if ! json="$(gh issue view "$n" --repo "$REPO" --json number,title,state,closedAt 2>/dev/null)"; then
        echo "${prefix}#${n} <取得失敗: このリポジトリに存在しない可能性>"
        return
    fi

    state="$(echo "$json" | jq -r .state)"
    title="$(echo "$json" | jq -r .title)"
    closed="$(echo "$json" | jq -r '.closedAt // "-"')"

    # 親トラッキングIssueの見落としを防ぐため、sub-issueの内訳も併せて出す。
    # 分割子が全てCLOSEDなのに親がOPEN、という#575のケースがここで可視化される。
    # 取得に失敗したときは「分割子なし」と区別できるよう明示する。黙って空扱いにすると、
    # 親トラッキングIssueの分割子が全てCLOSEDという事実を見落としたまま判定が進む。
    local subs sub_total sub_open sub_note=""
    if subs="$(gh api "repos/$REPO/issues/$n/sub_issues" --paginate 2>/dev/null)"; then
        sub_total="$(echo "$subs" | jq -s 'add // [] | length')"
        if [ "$sub_total" -gt 0 ]; then
            sub_open="$(echo "$subs" | jq -s '[add[] | select(.state == "open")] | length')"
            sub_note=" sub-issues=${sub_total}(open ${sub_open})"
            if [ "$state" = "OPEN" ] && [ "$sub_open" -eq 0 ]; then
                sub_note="${sub_note} ※親はOPENだが分割子は全てCLOSED"
            fi
        fi
    else
        sub_note=" ※sub-issuesの取得に失敗(分割子の有無は未確認)"
    fi

    echo "${prefix}#${n} ${state} closed=${closed}${sub_note} ${title}"
}

echo "=== 対象 Issue ==="
print_issue_line "$ISSUE" ""

echo
echo "=== 正式な依存リンク (GitHub issue dependencies) ==="
echo "-- blocked_by (OPENならこれが唯一の状態ベースのブロッカー) --"
BLOCKED_BY="$(fetch_array "repos/$REPO/issues/$ISSUE/dependencies/blocked_by")"
BLOCKED_BY_COUNT="$(echo "$BLOCKED_BY" | jq 'length')"
if [ "$BLOCKED_BY_COUNT" -eq 0 ]; then
    echo "(なし)"
    echo "  → 正式なリンクが張られていない。この場合、本文の散文だけが依存の記録になる。"
    echo "    散文の依存Issueの状態は、それ単体ではブロッカーにしない"
    echo "    (.claude/CLAUDE.md の Dependency Resolution を参照)。"
else
    for n in $(echo "$BLOCKED_BY" | jq -r '.[].number'); do
        print_issue_line "$n" "  "
    done
fi

echo "-- blocking (このIssueが塞いでいる先) --"
BLOCKING="$(fetch_array "repos/$REPO/issues/$ISSUE/dependencies/blocking")"
if [ "$(echo "$BLOCKING" | jq 'length')" -eq 0 ]; then
    echo "(なし)"
else
    for n in $(echo "$BLOCKING" | jq -r '.[].number'); do
        print_issue_line "$n" "  "
    done
fi

echo
echo "=== 本文の依存節が挙げる Issue ==="
# 依存の見出しから次の見出しまでを切り出し、#<数字>を拾う。
# 見出しの表記は揺れる(「## 依存」「## 依存関係」「## Dependencies」)ので前方一致で拾う。
DEP_SECTION="$(echo "$SELF" | jq -r .body \
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
    if [ -z "$DEP_NUMBERS" ] && [[ "$DEP_COMPACT" =~ ^(なし|無し|特になし|none|n/a|na|-)[。.]?$ ]]; then
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
        echo "  対処: 判定を出す前に、Epicコードが指すIssue番号を特定して本文を更新するか、"
        echo "  GitHubの正式なblocked_byリンクを張ること。特定できないなら、"
        echo "  その旨を判定コメントに書く(「依存不明のため差し戻し」は許容されるが、"
        echo "  「依存が未解決のため差し戻し」と断定してはいけない)。"
    else
        for n in $DEP_NUMBERS; do
            print_issue_line "$n" "  "
        done
        echo
        echo "  注意: ここに挙がったIssueがOPENであること自体はブロッカーではない。"
        echo "  判断すべきは「このIssueの受入基準を現在のコードベースに対して実装し検証できるか」。"
        echo "  親がOPENでも分割子が全てCLOSEDで実体がコードにあるなら着手可能と判定してよい。"
        echo "  どちらの根拠で判定したかは、判定コメントに必ず明記すること。"
    fi
fi

echo
echo "=== 既存の Readiness 判定コメント ==="
# 逆向きの判定を無自覚に投稿しないための材料。判定を覆すときは、
# ここに出たコメントが挙げた根拠を1つずつライブで再確認すること(#751)。
if ! COMMENTS_JSON="$(gh issue view "$ISSUE" --repo "$REPO" --json comments 2>&1)"; then
    echo "エラー: コメントの取得に失敗しました。判定を出さずに中断します。" >&2
    echo "$COMMENTS_JSON" | head -3 >&2
    exit 1
fi
COMMENTS="$(echo "$COMMENTS_JSON" \
    | jq -r '[.comments[]
        | select(.body | test("READY|Ready|Backlog|readiness|Readiness"))
        | "\(.createdAt)\t\(.body | split("\n")[0:2] | join(" / ") | .[0:160])"] | .[]')"

if [ -z "$COMMENTS" ]; then
    echo "(なし)"
else
    echo "$COMMENTS" | sed 's/^/  /'
    echo
    echo "  注意: これから出す判定が直近の判定と逆向きなら、そのまま差し戻さず、"
    echo "  直近コメントが挙げた根拠を1つずつライブで再確認し、どれが現在は成立しないのかを"
    echo "  新しいコメントに明示すること(#751)。"
fi
