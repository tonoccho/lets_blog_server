#!/bin/bash
# issue #756: `.env` が `.env.example` に追随できているかを確認する。
#
# 背景: `.env` はセットアップ時に `cp .env.example .env` で作るきりで、その後
# `.env.example` に項目が増えても既存の `.env` は追随しない。追随漏れは静かに壊れる。
#
#   - `docker compose` が「The "X" variable is not set. Defaulting to a blank string.」と
#     警告するが、起動自体は成功するので見落とされる
#   - `infra/mysql/init/01-create-service-schemas.sh` は LBS_*_DB_PASSWORD が空だと
#     該当ユーザーの作成をスキップする。#756 では LBS_BACKUP_DB_PASSWORD がこれに当たり、
#     lbs_backup ユーザーが作られず platform-service のバックアップ機能が動かなかった
#
# `.env.example` を契約とみなし、そこにあるキーが `.env` に揃っているかを見る。
#
# 使い方:
#   bash scripts/check-env.sh            # リポジトリ直下の .env を見る
#   bash scripts/check-env.sh path/to/.env
#
# 終了コード: 0 = 問題なし / 1 = 不足または重複あり / 2 = 使い方の誤り

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$REPO_ROOT/.env.example"
TARGET="${1:-$REPO_ROOT/.env}"

if [ ! -f "$EXAMPLE" ]; then
    echo "エラー: $EXAMPLE が見つかりません" >&2
    exit 2
fi

if [ ! -f "$TARGET" ]; then
    echo "エラー: $TARGET が見つかりません。" >&2
    echo "  cp .env.example .env  を実行してから設定してください(docs/setup.md)。" >&2
    exit 1
fi

# `KEY=` 形式の行からキー名だけを取り出す。コメント行と空行は自然に外れる。
keys_of() {
    grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' "$1" | tr -d '=' | sort -u
}

# 同じキーが2回以上定義されている行。`sort -u` を通す keys_of とは別に、
# **重複を潰さずに**数える必要があるのでここだけ -u を付けない(#959)。
#
# なぜ重複が問題か: `docker compose` の env_file も shell の `source` も**後の定義が勝つ**。
# したがってキーが2箇所にあると、コメント付きの正しい定義を利用者が書き換えても、
# コメントの無い後の行に上書きされて無視される。しかも警告は一切出ない。
# #959 では `.env.example` の PENPOT_SECRET_KEY がまさにこれで、
# 「512-bit base64 で生成せよ」という指示を持たないほうが有効になっていた。
duplicate_keys_of() {
    grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' "$1" | tr -d '=' | sort | uniq -d
}

# 値が空のキー(`KEY=` だけ、または空白のみ)。compose の警告と同じものを拾う。
empty_keys_of() {
    grep -oE '^[A-Za-z_][A-Za-z0-9_]*=[[:space:]]*$' "$1" | grep -oE '^[A-Za-z_][A-Za-z0-9_]*' | sort -u
}

missing="$(comm -23 <(keys_of "$EXAMPLE") <(keys_of "$TARGET"))"
extra="$(comm -13 <(keys_of "$EXAMPLE") <(keys_of "$TARGET"))"

# `.env.example` では値が入っているのに `.env` で空になっているキー。
# 単なる未設定と違い「消してしまった」ケースなので、不足と同じ扱いにする。
blanked="$(comm -12 <(empty_keys_of "$TARGET") <(comm -23 <(keys_of "$EXAMPLE") <(empty_keys_of "$EXAMPLE")))"

status=0

# 契約そのもの(.env.example)の重複を先に見る。ここが壊れていると、`.env` を
# どれだけ正しく作っても後勝ちで弱い値が有効になるため、不足の議論より前に置く。
example_dups="$(duplicate_keys_of "$EXAMPLE")"
if [ -n "$example_dups" ]; then
    status=1
    echo "✗ .env.example で重複定義されているキー:"
    echo "$example_dups" | sed 's/^/    /'
    echo "    → 後の定義が勝つため、前の行のコメントや値が無視される。1箇所にまとめること。"
fi

target_dups="$(duplicate_keys_of "$TARGET")"
if [ -n "$target_dups" ]; then
    status=1
    echo "✗ $(basename "$TARGET") で重複定義されているキー:"
    echo "$target_dups" | sed 's/^/    /'
    echo "    → 後の定義が勝つ。意図しない値が有効になっていないか確認し、1箇所にまとめること。"
fi

if [ -n "$missing" ]; then
    status=1
    echo "✗ .env.example にあって .env に無いキー:"
    echo "$missing" | sed 's/^/    /'
fi

if [ -n "$blanked" ]; then
    status=1
    echo "✗ .env.example では値が入っているのに .env で空のキー:"
    echo "$blanked" | sed 's/^/    /'
fi

if [ "$status" -ne 0 ]; then
    echo
    echo "  対処:"
    echo "    1. .env.example の該当行を .env にコピーし、環境に合わせた値を設定する"
    echo "    2. LBS_*_DB_PASSWORD を足した場合は、MySQLのユーザー作成をやり直す。"
    echo "       docker-entrypoint-initdb.d はデータボリュームが空のときしか走らないため、"
    echo "       既存ボリュームでは手動で再実行する(冪等):"
    echo "         docker compose up -d mysql"
    echo "         docker compose exec mysql bash /docker-entrypoint-initdb.d/01-create-service-schemas.sh"
    echo "       詳細は docs/SERVICE_SCHEMA_MIGRATION.md を参照。"
fi

# docker-compose.yml が既定値なしで変数を参照しているのに `.env.example` に無いと、
# `.env` を正しく作っても compose の警告が出続ける。`.env.example` を契約とみなす以上、
# 契約そのものの抜けもここで見る(#756 では LLM_CLAUDE_API_KEY / IMAGE_LLM_API_KEY が該当した)。
#
# 既定値ありの `${VAR:-default}` / `${VAR:+alt}` / `${VAR-default}` は警告にならないので対象外。
# 拾うのは `${VAR}`、必須指定の `${VAR:?msg}` / `${VAR?msg}`、波括弧なしの `$VAR`。
# 行末コメントを落とす。ただしクォートの中の `#` は値の一部なので残す(issue #840)。
#
# 素朴に `sed 's/[[:space:]]#.*$//'` とすると、
#   - "MAIL_FROM=Team #1 needs ${VAR}"   ← クォート内の # 以降が消え、VAR を取りこぼす
# となり、検査3が見逃す側に倒れる。#756 が問題にした「静かに壊れる」の再導入になるため、
# クォート状態を見て判定する。
#
# なお **クォートされていない** `- MAIL_FROM=Team #1 needs ${VAR}` は、YAML の仕様上
# ` #` 以降が本当にコメントである(compose も VAR を展開しない)。よってこれを
# 取りこぼしと扱うのは誤りで、ここで消すのが正しい挙動。
strip_yaml_comments() {
    awk '{
        out = ""; qc = ""
        n = length($0)
        for (i = 1; i <= n; i++) {
            c = substr($0, i, 1)
            if (qc != "") {
                # クォート内。同じ種類のクォートで閉じる。
                if (c == qc) { qc = "" }
            } else if (c == "\"" || c == "'"'"'") {
                qc = c
            } else if (c == "#" && (i == 1 || substr($0, i - 1, 1) ~ /[[:space:]]/)) {
                # クォート外で、直前が行頭または空白の `#` から先はコメント。
                break
            }
            out = out c
        }
        print out
    }' 
}

compose_required_vars() {
    # 行頭コメントを落としてから、クォートを考慮して行末コメントを落とす。
    # さらに `$$`(Compose におけるリテラル `$` のエスケープ)を先に取り除く。
    # `command: ["sh","-c","echo $$HOME"]` の `$$HOME` はコンテナ内シェルへ `$HOME` を
    # 渡す意図で、compose 自身が展開する変数ではない。取り除かないと 2 文字目の `$` から
    # マッチして HOME を必須キーと誤検出する(POSIX ERE に negative lookaround が無いため、
    # 正規表現だけでは除外できない。issue #840)。
    # 左から `$$` を潰すので、`$$$VAR`(リテラル `$` + 変数 VAR)は `$VAR` として正しく残る。
    grep -v '^[[:space:]]*#' "$1" \
        | strip_yaml_comments \
        | sed 's/\$\$//g' \
        | grep -ohE '\$\{[A-Za-z_][A-Za-z0-9_]*(:?[-+?][^}]*)?\}|\$[A-Za-z_][A-Za-z0-9_]*' \
        | sed -E 's/^\$\{//; s/\}$//; s/^\$//' \
        | grep -vE '^[A-Za-z_][A-Za-z0-9_]*:?[-+]' \
        | sed -E 's/:?\?.*$//' \
        | sort -u
}

COMPOSE="$REPO_ROOT/docker-compose.yml"
if [ -f "$COMPOSE" ] && [ ! -r "$COMPOSE" ]; then
    # 読めないまま `|| true` に飲み込ませると「参照0件」と区別が付かず、
    # 契約の抜けを見落としたまま ✓ を出してしまう。ここで落として区別する。
    echo "エラー: $COMPOSE を読み取れません。契約の抜けを検査できないため中断します。" >&2
    exit 1
fi
if [ -f "$COMPOSE" ]; then
    # `|| true` が要る。参照が1件も無いと途中の grep が exit 1 を返し、pipefail によって
    # パイプライン全体が失敗扱いになり、set -e でこの行が無言終了してしまう。
    # 検査スクリプトが理由も出さずに落ちるのは、#756 が問題にしている「静かに壊れる」そのもの。
    uncontracted="$(comm -23 <(compose_required_vars "$COMPOSE" || true) <(keys_of "$EXAMPLE"))"
    if [ -n "$uncontracted" ]; then
        status=1
        echo "✗ docker-compose.yml が既定値なしで参照しているのに .env.example に無いキー:"
        echo "$uncontracted" | sed 's/^/    /'
        echo "    → .env を正しく作っても compose の警告が消えない。.env.example に行を足すこと"
        echo "      (使わないキーなら値は空でよい。行が無いことが警告の原因)。"
    fi
fi

if [ -n "$extra" ]; then
    # 手元だけの上書きは正当な使い方なので、警告に留めて終了コードは変えない。
    echo "△ .env にあって .env.example に無いキー(独自設定なら問題なし):"
    echo "$extra" | sed 's/^/    /'
fi

if [ "$status" -eq 0 ] && [ -z "$extra" ]; then
    echo "✓ .env は .env.example の全項目を満たしています"
elif [ "$status" -eq 0 ]; then
    echo "✓ 不足なし(.env.example の全項目が .env に存在します)"
fi

exit "$status"
