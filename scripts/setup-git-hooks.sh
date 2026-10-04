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
# `pre-commit` は `git commit`(とコンフリクト解決後の明示コミット)しか拾わない。
# コンフリクトなしの `git merge` は別のフック `pre-merge-commit` の担当で(#1452、
# `man githooks`)、`core.hooksPath` はディレクトリ単位で束縛されるため個別の設定は
# 要らないが、実体が欠けている/実行できない状態は個別に点検する必要がある。
# `pre-push`(#1514)は push 前の green を強制する。コミットは RED でよいので、
# それを担保する層がコミットとは別に要る。欠けても黙って飛ばされるので同様に点検する。
HOOK_NAMES=("pre-commit" "pre-merge-commit" "pre-push")

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
# 束縛の前に見る。`pre-commit` / `pre-merge-commit` / `pre-push` を点検する(#1452、#1514) —
# 前者だけを見ていると、後者が欠けている/実行できないことに気づく手段が無い。
check_hook_file() {
    if [ ! -d "$REPO_ROOT/$HOOKS_DIR" ]; then
        echo "✗ $HOOKS_DIR が存在しません。"
        echo "    → 束縛先が無い状態です。リポジトリの状態を確認してください。"
        return 1
    fi
    local name path
    for name in "${HOOK_NAMES[@]}"; do
        path="$HOOKS_DIR/$name"
        if [ ! -f "$REPO_ROOT/$path" ]; then
            echo "✗ $path が存在しません。"
            return 1
        fi
        if [ ! -x "$REPO_ROOT/$path" ]; then
            echo "✗ $path に実行ビットがありません。"
            echo "    → git は実行できないフックを**黙って飛ばす**ため、束縛しても何も起きません。"
            echo "      chmod +x $path"
            return 1
        fi
    done
    return 0
}

configured_path() {
    git -C "$REPO_ROOT" config --get core.hooksPath 2>/dev/null
}

# 設定値は相対でも絶対でも書ける。文字列比較ではなく、同じディレクトリを指しているかで見る。
#
# linked worktree(`git worktree add`)では `.git/config` が全作業ツリーで共有され、
# `core.hooksPath` にはメイン作業ツリーの絶対パスが入る(#1290)。それは worktree 自身の
# `scripts/git-hooks` とは別ディレクトリだが、フックはそのパスで実際に動いている。
# したがって「このリポジトリのいずれかの作業ツリーの `scripts/git-hooks`」を指していれば
# 束縛済みとみなす。無関係なディレクトリや未設定は従来どおり未束縛である。
points_at_hooks_dir() {
    local configured="$1"
    [ -n "$configured" ] || return 1
    local resolved
    resolved="$(cd "$REPO_ROOT" && cd "$configured" 2>/dev/null && pwd -P)" || return 1
    # grep -q は早期終了で SIGPIPE を起こし pipefail と衝突するので、全件読んでから比べる。
    local wt candidate worktrees
    worktrees="$(printf '%s\n' "$REPO_ROOT"; git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null | sed -n 's/^worktree //p')"
    while IFS= read -r wt; do
        candidate="$(cd "$wt/$HOOKS_DIR" 2>/dev/null && pwd -P)" || continue
        [ "$resolved" = "$candidate" ] && return 0
    done <<<"$worktrees"
    return 1
}

report_unbound() {
    local configured="$1"
    if [ -z "$configured" ]; then
        echo "✗ core.hooksPath が未設定です。git フックが一切動いていません。"
        echo "    症状は「何も起きない」ことだけです。テストとプロダクションの混在コミットも、"
        echo "    テストを黙らせる変更も、テストより先のプロダクションコミットも、"
        echo "    apps/web のカバレッジ床割れも、未分類パスのコミットも素通りします。"
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
echo "  $HOOK の検査を受けます: フェーズ分離 / テストの黙殺 / テストファースト /"
echo "  apps/web カバレッジ床 / 未分類パスの拒否。"
echo "  コンフリクトなしの git merge(pre-commit を通らない経路)は"
echo "  $HOOKS_DIR/pre-merge-commit が未分類パスの拒否と、マージ前の HEAD 側に無い"
echo "  テストの黙殺の拒否だけを別途検査します(#1452、#1460)。"
echo "  push は $HOOKS_DIR/pre-push が検査します: apps/web を変更する push の前に"
echo "  npm run test:coverage が green であること(#1514)。"
echo "  点検: bash scripts/setup-git-hooks.sh --check"
