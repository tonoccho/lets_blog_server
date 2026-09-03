#!/bin/bash
# issue #1039: `scripts/git-hooks/` を git に束縛し、束縛されていることを点検する。
#
# なぜ必要か:
#
# `CLAUDE.md` → Enforcement は、テストファースト等の不変条件が2層で強制されると述べている。
# `.claude/hooks/guard.py` はエージェントのツール呼び出ししか見られず、人手のコミットや
# `bash -c` で素通りしたコミットは見えない。その穴を埋めるのが `scripts/git-hooks/pre-commit`
# である(#976)。
#
# ところが `core.hooksPath` は **git の設定であってリポジトリの内容ではない**。クローンにも
# チェックアウトにも含まれず、コミットもされない。したがって:
#
#   - 設定漏れは差分に現れず、レビューで見つからない
#   - フックが動いていないことの唯一の症状は「**何も起きない**」こと
#   - guard.py 側は動いているので「強制は効いている」という誤った確信を与える
#
# 実際 #976 以降のすべてのコミットが、この層の検査を受けていなかった。しかも README と
# CLAUDE.md は「設定済み」と断言していたので、誰も疑わなかった。
#
# 束縛(既定)と点検(--check)を1つのスクリプトに置くのは、直し方と気づき方が
# 同じ場所にあるべきだから。点検は決して設定を書き換えない — 黙って直してしまうと、
# 「外れていた」という事実そのものが観測できなくなる。
#
# 使い方:
#   bash scripts/setup-git-hooks.sh            # 束縛する(冪等。新規クローンで一度)
#   bash scripts/setup-git-hooks.sh --check    # 束縛されているか点検する(書き換えない)
#
# 終了コード: 0 = 束縛されている / 1 = 束縛されていない・壊れている / 2 = 使い方や環境の誤り

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOKS_DIR="scripts/git-hooks"
HOOK="$HOOKS_DIR/pre-commit"

MODE="fix"
case "${1:-}" in
    "") ;;
    --check) MODE="check" ;;
    -h|--help)
        sed -n '/^# 使い方:/,/^# 終了コード:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "エラー: 不明なオプション: $1" >&2
        echo "  使い方: bash scripts/setup-git-hooks.sh [--check]" >&2
        exit 2
        ;;
esac

if ! git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "エラー: $REPO_ROOT は git の作業ツリーではありません。" >&2
    exit 2
fi

# フックの実体が無い/実行できない状態で束縛すると、git のあらゆるコミットが
# 落ちるか、あるいは黙って飛ばされる。どちらも束縛より先に直すべき問題なので、
# 束縛の前に見る。
check_hook_file() {
    if [ ! -d "$REPO_ROOT/$HOOKS_DIR" ]; then
        echo "✗ $HOOKS_DIR が存在しません。"
        echo "    → 束縛先が無い状態です。リポジトリの状態を確認してください。"
        return 1
    fi
    if [ ! -f "$REPO_ROOT/$HOOK" ]; then
        echo "✗ $HOOK が存在しません。"
        return 1
    fi
    if [ ! -x "$REPO_ROOT/$HOOK" ]; then
        echo "✗ $HOOK に実行ビットがありません。"
        echo "    → git は実行できないフックを**黙って飛ばす**ため、束縛しても何も起きません。"
        echo "      chmod +x $HOOK"
        return 1
    fi
    return 0
}

configured_path() {
    git -C "$REPO_ROOT" config --get core.hooksPath 2>/dev/null
}

# 設定値は相対でも絶対でも書ける。文字列比較ではなく、同じディレクトリを指しているかで見る。
points_at_hooks_dir() {
    local configured="$1"
    [ -n "$configured" ] || return 1
    local resolved
    resolved="$(cd "$REPO_ROOT" && cd "$configured" 2>/dev/null && pwd -P)" || return 1
    local expected
    expected="$(cd "$REPO_ROOT/$HOOKS_DIR" && pwd -P)" || return 1
    [ "$resolved" = "$expected" ]
}

report_unbound() {
    local configured="$1"
    if [ -z "$configured" ]; then
        echo "✗ core.hooksPath が未設定です。git フックが一切動いていません。"
        echo "    症状は「何も起きない」ことだけです。テストとプロダクションの混在コミットも、"
        echo "    テストを黙らせる変更も、テストより先のプロダクションコミットも素通りします。"
    else
        echo "✗ core.hooksPath が $HOOKS_DIR ではなく '$configured' を指しています。"
    fi
    echo "    → bash scripts/setup-git-hooks.sh   で束縛してください。"
}

if ! check_hook_file; then
    exit 1
fi

CONFIGURED="$(configured_path)"

if [ "$MODE" = "check" ]; then
    if points_at_hooks_dir "$CONFIGURED"; then
        echo "✓ git フックは有効です (core.hooksPath = $CONFIGURED)"
        exit 0
    fi
    report_unbound "$CONFIGURED"
    exit 1
fi

if points_at_hooks_dir "$CONFIGURED"; then
    echo "✓ git フックはすでに有効です (core.hooksPath = $CONFIGURED)"
    exit 0
fi

if ! git -C "$REPO_ROOT" config core.hooksPath "$HOOKS_DIR"; then
    echo "エラー: core.hooksPath を設定できませんでした。" >&2
    exit 2
fi

CONFIGURED="$(configured_path)"
if ! points_at_hooks_dir "$CONFIGURED"; then
    echo "✗ 設定したのに $HOOKS_DIR を指していません (core.hooksPath = '$CONFIGURED')。" >&2
    exit 1
fi

echo "✓ git フックを有効にしました (core.hooksPath = $CONFIGURED)"
echo "  あらゆるコミッタ(エージェント・人間を問わず)のコミットが"
echo "  $HOOK の検査を受けます: フェーズ分離 / テストの黙殺 / テストファースト。"
echo "  点検: bash scripts/setup-git-hooks.sh --check"
