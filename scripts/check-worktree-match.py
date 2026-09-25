#!/usr/bin/env python3
"""共有 docker compose スタックを作った作業ツリーと、今テストしようとしている作業ツリーが
一致するかを確認する(#1202)。

## 背景

受け入れテストは共有された1本の docker compose スタックに対して走る(ホストのメモリで
2本目のスタックが成立しないため)。無人ループを複数worktree・複数ブランチで並列に走らせると、
「自分のブランチのコードをテストしている」という前提が誰にも保証されなくなる:

  1. プロダクションコードを変更したブランチのAT — 共有スタックのイメージ/bind mountが
     別の作業ツリーのものだと、変更前のコードを検証したまま緑になる(症状が無い)。
  2. `scripts/rebuild-acceptance-env.sh` を別worktreeから実行すると、共有スタックを
     黙って自分のブランチへ作り替えてしまう(もう一方のワーカーの足元をすくう)。

このスクリプトはどちらの入口でも呼ばれる、判定ロジックの唯一の実装である:

  - `at-start` … `apps/web/e2e/global-setup.ts` から、受け入れテスト開始前に呼ばれる。
    作業ツリーが不一致でも、このブランチが `origin/develop` からプロダクションコードを
    変更していなければ通す(要件4)。
  - `rebuild` … `scripts/rebuild-acceptance-env.sh` から、撤去を始める前に呼ばれる。
    共有スタックを黙って乗っ取る操作そのものなので、プロダクション差分の有無を問わず、
    作業ツリーが不一致なら拒否する(要件5)。

プロダクションコードかどうかの判定は `.claude/hooks/paths.py` の `classify()` を使う。
分類規則はそこが唯一の定義であり、ここに書き写さない(要件3)。

## 迂回

環境変数 `AT_WORKTREE_CHECK_BYPASS=1` が、このチェック全体を明示的に迂回する唯一の
エスケープハッチである(要件6)。迂回した場合は必ず標準出力に記録する。

## 使い方

    python3 scripts/check-worktree-match.py at-start
    python3 scripts/check-worktree-match.py rebuild

単体テスト:

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

(docker デーモンも複数worktreeも要らない。`docker compose ls` と `git` の呼び出しを
差し替えて判定ロジックだけを検証する。`scripts/test_check_worktree_match.py` のモジュール
docstring に、Gherkin ではなくここで検証する理由を書いてある。)
"""

import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

sys.path.insert(0, os.path.join(REPO_ROOT, ".claude", "hooks"))
from paths import classify  # noqa: E402

BYPASS_ENV = "AT_WORKTREE_CHECK_BYPASS"
COMPOSE_PROJECT_NAME = "lets_blog_server"


class WorktreeCheckError(Exception):
    """git / docker の呼び出しが失敗し、判定を安全に下せないことを表す。"""


# ---------------------------------------------------------------- 外部コマンドの境界
#
# 単体テストはこの2関数だけを差し替える。それ以外のロジックは純粋関数として検証する。


def _docker_compose_ls():
    """`docker compose ls --format json` の標準出力を返す。取得できなければ空文字列。

    docker が入っていない・デーモンが無いホストでの失敗は「判定できない」であって
    「不一致」ではない(E2E_SKIP_* 系のフラグと同じ、無いものは無いとして先に進める設計)。
    """
    try:
        r = subprocess.run(
            ["docker", "compose", "ls", "--format", "json"],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return r.stdout if r.returncode == 0 else ""


def _git(args, cwd):
    """`git <args>` を `cwd` で実行し、`(returncode, stdout, stderr)` を返す。"""
    try:
        r = subprocess.run(
            ["git"] + list(args), cwd=cwd, capture_output=True, text=True, timeout=30
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


# ---------------------------------------------------------------- 判定ロジック


def get_stack_worktree_roots(project_name=COMPOSE_PROJECT_NAME):
    """稼働中の compose プロジェクトを作った作業ツリーのルートパスの集合を返す。

    権威は `docker compose ls --format json` の `ConfigFiles`(絶対パス、カンマ区切り)。
    スタックが無い/取得できない場合は空集合(= 比較のしようが無い)を返す。
    """
    raw = _docker_compose_ls()
    if not raw:
        return set()
    try:
        projects = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return set()

    roots = set()
    for p in projects:
        if not isinstance(p, dict) or p.get("Name") != project_name:
            continue
        config_files = p.get("ConfigFiles") or ""
        for f in config_files.split(","):
            f = f.strip()
            if f:
                roots.add(os.path.dirname(f))
    return roots


def current_worktree_root(cwd=None):
    """今実行している作業ツリーのルートを返す(git worktree ならその worktree のルート)。"""
    cwd = cwd or REPO_ROOT
    code, out, err = _git(["rev-parse", "--show-toplevel"], cwd)
    if code != 0:
        raise WorktreeCheckError(
            "git rev-parse --show-toplevel に失敗しました: %s" % err.strip()
        )
    return out.strip()


def changed_paths_since_develop(cwd=None, base="origin/develop"):
    """`git merge-base <base> HEAD` を起点に、そこから HEAD までの変更パス一覧を返す。

    ローカルの `develop` ブランチの追随状況に依存しない(要件の実装メモどおり)。
    """
    cwd = cwd or REPO_ROOT
    code, out, err = _git(["merge-base", base, "HEAD"], cwd)
    if code != 0:
        raise WorktreeCheckError(
            "git merge-base %s HEAD に失敗しました: %s" % (base, err.strip())
        )
    merge_base = out.strip()

    code, out, err = _git(["diff", "--name-only", merge_base, "HEAD"], cwd)
    if code != 0:
        raise WorktreeCheckError("git diff --name-only に失敗しました: %s" % err.strip())
    return [line for line in out.splitlines() if line.strip()]


def production_changes_since_develop(cwd=None, base="origin/develop"):
    """このブランチが `origin/develop` から変更したプロダクションコードのパス一覧を返す。

    分類は `.claude/hooks/paths.py` の `classify()` に委ねる(規則を書き写さない)。
    """
    _tests, prod = classify(changed_paths_since_develop(cwd=cwd, base=base))
    return prod


def check(mode, cwd=None):
    """(ok, message) を返す。`ok=False` は開始/続行を拒否すべきことを意味する。

    `mode` は `"at-start"`(受け入れテスト開始前)か `"rebuild"`
    (`rebuild-acceptance-env.sh` からの呼び出し)。
    """
    if os.environ.get(BYPASS_ENV) == "1":
        return True, "%s=1 のため作業ツリーの一致チェックを迂回しました" % BYPASS_ENV

    stack_roots = get_stack_worktree_roots()
    if not stack_roots:
        return (
            True,
            "稼働中の compose プロジェクト '%s' が見つからないため、"
            "作業ツリーの一致確認を省略します" % COMPOSE_PROJECT_NAME,
        )

    try:
        current = current_worktree_root(cwd=cwd)
    except WorktreeCheckError as e:
        return False, (
            "エラー: 現在の作業ツリーを特定できません(%s)。\n"
            "判定を安全に下せないため停止します。続行するには %s=1 を指定してください。"
            % (e, BYPASS_ENV)
        )

    normalized_stack_roots = {os.path.abspath(r) for r in stack_roots}
    if os.path.abspath(current) in normalized_stack_roots:
        return True, "作業ツリーは共有スタックと一致しています: %s" % current

    stack_roots_display = ", ".join(sorted(stack_roots))

    if mode == "rebuild":
        return False, (
            "エラー: 実行中の compose プロジェクト '%s' は別の作業ツリーから作られています。\n"
            "  このスクリプトの作業ツリー   : %s\n"
            "  スタックを作った作業ツリー   : %s\n"
            "このまま再構築すると、共有スタックを別ワーカーから黙って乗っ取ります(#1202)。\n"
            "続行するには %s=1 を指定してください(明示的な迂回。標準出力に記録されます)。"
            % (COMPOSE_PROJECT_NAME, current, stack_roots_display, BYPASS_ENV)
        )

    # mode == "at-start"
    try:
        prod = production_changes_since_develop(cwd=current)
    except WorktreeCheckError as e:
        return False, (
            "エラー: origin/develop に対するプロダクションコード変更の判定に失敗しました"
            "(%s)。\n作業ツリーの不一致を安全に判定できないため開始しません。\n"
            "続行するには %s=1 を指定してください。" % (e, BYPASS_ENV)
        )

    if not prod:
        return True, (
            "作業ツリーは共有スタックと不一致ですが、プロダクションコードの変更が無いため"
            "受け入れテストを開始します"
        )

    prod_list = "\n".join("  - " + p for p in prod)
    return False, (
        "エラー: 実行中の共有スタックは別の作業ツリーから作られています。\n"
        "  このブランチの作業ツリー   : %s\n"
        "  スタックを作った作業ツリー : %s\n"
        "このブランチは origin/develop から以下のプロダクションコードを変更していますが、\n"
        "共有スタックにはその変更が反映されていません"
        "(変更前のコードを検証することになり、テストは緑になっても何も検証していません):\n"
        "%s\n"
        "共有スタックを自分の作業ツリーから再構築するか(scripts/rebuild-acceptance-env.sh --yes)、\n"
        "スタックを作った作業ツリーからテストを実行してください。\n"
        "明示的に迂回するには %s=1 を指定してください。"
        % (current, stack_roots_display, prod_list, BYPASS_ENV)
    )


def main(argv):
    if len(argv) != 2 or argv[1] not in ("at-start", "rebuild"):
        print(
            "使い方: check-worktree-match.py {at-start|rebuild}",
            file=sys.stderr,
        )
        return 2

    ok, message = check(argv[1])
    if ok:
        print(message)
        return 0
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
