# compose プロジェクト名の既定値の導出(issue #1201)。`source` して使う。
#
# docker スタックは1本(compose プロジェクト名 = メイン作業ツリーのディレクトリ名)で、
# git worktree(.claude/worktrees/agent-<id>/ など)はそれを共有する。REPO_ROOT の basename
# をそのまま使うと worktree から呼んだときに存在しないプロジェクトを指してしまうため、
# リンクされた worktree ではメイン作業ツリーのディレクトリ名を使う。
#
#   1. COMPOSE_PROJECT_NAME が設定されていればそれ
#   2. リンク worktree なら、メイン作業ツリー(git common dir の親)の basename
#   3. それ以外(メイン作業ツリー・git 管理外)は REPO_ROOT の basename
#
# 使い方: COMPOSE_PROJECT="$(resolve_compose_project "$REPO_ROOT")"
resolve_compose_project() {
  local repo_root="$1" git_dir common_dir
  if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
    printf '%s\n' "$COMPOSE_PROJECT_NAME"
    return 0
  fi
  if git_dir="$(git -C "$repo_root" rev-parse --absolute-git-dir 2>/dev/null)" &&
    common_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" &&
    [ "$git_dir" != "$common_dir" ]; then
    basename "$(dirname "$common_dir")"
    return 0
  fi
  basename "$repo_root"
}
