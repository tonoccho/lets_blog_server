#!/bin/bash
# issue #1032: 移行前に閉じた Issue の closed_at を GitHub から取り出して保全する。
#
# ## なぜ必要か
#
# GitHub から GitLab へ移行した Issue は `closed_at` が null になっている。移行時に
# 引き継がれなかった。GitLab 上で閉じた Issue には値が入るため、移行前後で断絶がある。
#
#   #926  state=closed  closed_at=null              ← GitHub 時代に閉じた
#   #1022 state=closed  closed_at=2026-09-03T...    ← GitLab で閉じた
#
# `scripts/issue-dependency-status.sh` の冒頭コメントが記録する #751 の分析は、
# まさにこのタイムスタンプに依拠していた(「#575 が CLOSED になったのは 02:12:19Z。
# 差し戻しコメントの40分後」)。同種の事後分析を将来また行う必要が生じたとき、
# 移行前の Issue についてはその追跡ができない。
#
# ## なぜ GitLab に書き戻さないのか
#
# **書き戻せないから。** GitLab の API は `closed_at` を受け付けない(実測、#1032)。
#
#   REST    PUT /projects/:id/issues/:iid に closed_at を渡すと、受理パラメータの
#           一覧を挙げたエラーが返る。created_at は受理されるが closed_at は無い
#   GraphQL UpdateIssueInput が closedAt という引数を持たない
#
# したがって取れる手は「別の形で保全する」か「失われたものとして受け入れる」の2つ。
# 本スクリプトは前者を実行する。
#
# ## 期限がある
#
# 取得元は移行元の GitHub リポジトリである。**それが削除された時点で、この情報は
# 永久に失われる。** 判断は先送りできるが、判断できる期間には期限がある。
#
# ## 使い方
#
#   gh auth login                          # 一時的に認証する
#   bash scripts/export-github-closed-at.sh
#   gh auth logout                         # **必ず戻すこと**(下記)
#
# 出力: docs/migration/github-closed-at.json
#
# ## 作業後に必ずログアウトすること
#
# `gh` に認証が通った状態は、スキルが移行元のリポジトリを誤って読み書きする事故の
# 温床である(#1025 の Background 参照)。移行元は private のまま現存しており、
# `gh issue edit` の類が通ってしまう。取得が済んだら必ず `gh auth logout` すること。

set -euo pipefail

REPO="${GITHUB_SOURCE_REPO:-tonoccho/lets_blog_server}"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/migration"
OUT="$OUT_DIR/github-closed-at.json"

if ! command -v gh >/dev/null 2>&1; then
    echo "エラー: gh コマンドが見つかりません。" >&2
    exit 2
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "エラー: gh が認証されていません。" >&2
    echo "  gh auth login を実行してから再度お試しください。" >&2
    echo "  **取得後は必ず gh auth logout すること**(このスクリプト冒頭のコメント参照)。" >&2
    exit 1
fi

if ! gh repo view "$REPO" >/dev/null 2>&1; then
    echo "エラー: $REPO にアクセスできません。" >&2
    echo "  リポジトリが削除された場合、この情報はもう取得できません。" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "取得中: $REPO の closed Issue ..."

# Issue のみ(PR を除く)。GitHub は同じエンドポイントで PR も返すため除外する。
# 番号と closed_at だけを保つ。本文・タイトル・作成者は含めない — 必要なのは
# 「いつ閉じたか」だけであり、個人情報や本文を持ち出す理由が無い。
gh api "repos/$REPO/issues?state=closed&per_page=100" --paginate \
    | jq -s 'add
        | map(select(.pull_request == null))
        | map({number, closed_at})
        | sort_by(.number)' > "$OUT.tmp"

COUNT=$(jq 'length' "$OUT.tmp")

jq --arg repo "$REPO" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
   '{source: $repo, exported_at: $at, note: "GitLab は closed_at の設定を受け付けないため、書き戻しはできない(#1032)。参照用の記録である。", issues: .}' \
   "$OUT.tmp" > "$OUT"
rm -f "$OUT.tmp"

echo "保存しました: $OUT ($COUNT 件)"
echo
echo "**忘れずに gh auth logout を実行してください。**"
echo "  認証が通った gh は、スキルが移行元を誤って読み書きする事故の温床です(#1025)。"
