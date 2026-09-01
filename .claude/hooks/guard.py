#!/usr/bin/env python3
"""Claude Code の PreToolUse フックとして、`.claude/CLAUDE.md` の規約を機械的に強制する。

強制する対象は3つ。いずれも CLAUDE.md 側が唯一の定義であり、ここはその執行機構:

1. **Read-Only Stages** — `discover-issues` / `triage-backlog` / `ready-issue` の実行中は、
   リポジトリ内のいかなるファイルも書き換えさせない。
2. **Test-First Implementation** — テストコードとプロダクションコードを同一コミットに
   混在させない。テストを skip/ignore/削除して緑にすることを許さない。
3. **Merge Conflicts** — `--admin` や squash 以外のマージ方式による強制マージを許さない。

使い方(settings.json から):
    guard.py stage   # PreToolUse: Skill        読み取り専用ステージの開始/終了を記録
    guard.py clear   # UserPromptSubmit         ステージ状態をリセット
    guard.py write   # PreToolUse: Write|Edit|NotebookEdit
    guard.py bash    # PreToolUse: Bash
"""

import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import classify, is_production, is_test  # noqa: E402

READ_ONLY_SKILLS = {"discover-issues", "triage-backlog", "ready-issue"}

# テストを黙らせる手段。CLAUDE.md → Test-First Implementation → Never skip a test。
SILENCERS = [
    (r"@Disabled\b", "@Disabled"),
    (r"@Ignore\b", "@Ignore"),
    (r"\b(test|it|describe|context)\.skip\s*\(", "test.skip()"),
    (r"\b(test|it|describe)\.fixme\s*\(", "test.fixme()"),
    (r"\bxit\s*\(", "xit()"),
    (r"\bxdescribe\s*\(", "xdescribe()"),
    (r"testPathIgnorePatterns", "testPathIgnorePatterns"),
    (r"@(skip|fixme)\b", "@skip / @fixme タグ"),
]

# 読み取り専用ステージ中に禁止するシェル操作。
MUTATING_SHELL = [
    (r"\bsed\b[^|;&]*\s-i\b", "sed -i"),
    (r"\b(rm|mv|cp|tee|patch|truncate)\b", "ファイルを書き換えるコマンド"),
    (
        r"\bgit\s+(add|commit|checkout|switch|branch|merge|rebase|stash|restore|reset|push|apply|cherry-pick|tag|rm|mv)\b",
        "リポジトリ状態を変える git コマンド",
    ),
    (r"\b(npm|pnpm|yarn)\s+(i|install|ci|add|update)\b", "ロックファイルを書き換える依存インストール"),
]


def emit_deny(reason):
    json.dump(
        {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            }
        },
        sys.stdout,
    )
    sys.exit(0)


def allow():
    sys.exit(0)


def project_dir(payload):
    return os.path.realpath(
        os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or os.getcwd()
    )


def marker_path(payload):
    state = os.path.join(project_dir(payload), ".claude", ".state")
    session = payload.get("session_id") or "unknown"
    return os.path.join(state, "readonly-%s" % re.sub(r"[^A-Za-z0-9_-]", "_", session))


def read_stage(payload):
    try:
        with open(marker_path(payload), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return None


def strip_redirect_noise(command):
    """`2>/dev/null` のような無害なリダイレクトを除いた文字列を返す。"""
    for noise in (r"2>&1", r"&>\s*/dev/null", r"\d?>\s*/dev/null"):
        command = re.sub(noise, " ", command)
    return command


def cmd_of(payload):
    return payload.get("tool_input", {}).get("command", "") or ""


def git(args, cwd):
    try:
        out = subprocess.run(
            ["git"] + args, cwd=cwd, capture_output=True, text=True, timeout=20
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return out.stdout


# --------------------------------------------------------------------------- stage


def cmd_stage(payload):
    skill = payload.get("tool_input", {}).get("skill", "")
    path = marker_path(payload)
    if skill in READ_ONLY_SKILLS:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(skill)
    else:
        # 別のスキルが始まったら読み取り専用ステージは終わっている。
        try:
            os.remove(path)
        except OSError:
            pass
    allow()


def cmd_clear(payload):
    try:
        os.remove(marker_path(payload))
    except OSError:
        pass
    sys.exit(0)


# --------------------------------------------------------------------------- write


def cmd_write(payload):
    stage = read_stage(payload)
    tool_input = payload.get("tool_input", {})
    target = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
    root = project_dir(payload)
    inside = bool(target) and os.path.realpath(target).startswith(root + os.sep)
    rel = os.path.relpath(os.path.realpath(target), root) if inside else target

    if stage and inside:
        emit_deny(
            "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
            "リポジトリ内のファイル(%s)は変更できません。"
            "見つけた問題は直さずに GitHub Issue として登録し、報告してください。"
            "一時メモはスクラッチパッドディレクトリへ。" % (stage, rel)
        )

    if inside and not re.match(r"^(\.claude|docs|\.github|scripts)/", rel) and not rel.endswith(".md"):
        added = tool_input.get("new_string") or tool_input.get("content") or ""
        for pattern, label in SILENCERS:
            if re.search(pattern, added):
                emit_deny(
                    "%s に `%s` を追加しようとしています(CLAUDE.md → Test-First Implementation → "
                    "Never skip a test)。テストを黙らせて緑にすることは禁止です。"
                    "失敗しているならまずプロダクションコードを直してください。"
                    "テストケース自体が不適切だと示せる場合のみテストを修正し、理由を報告すること。"
                    % (rel, label)
                )
    allow()


# --------------------------------------------------------------------------- bash


def check_read_only(payload, command):
    stage = read_stage(payload)
    if not stage:
        return
    cleaned = strip_redirect_noise(command)
    for pattern, label in MUTATING_SHELL:
        if re.search(pattern, cleaned):
            emit_deny(
                "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
                "%s は実行できません。許可されているのは GitHub Issue の操作(`gh issue` / "
                "プロジェクト状態の更新)と読み取り専用の調査だけです。" % (stage, label)
            )
    if re.search(r"(^|[^0-9&])>>?[^&]", cleaned):
        emit_deny(
            "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
            "リダイレクトによるファイル書き込みは実行できません。"
            "一時ファイルが要る場合はスクラッチパッドディレクトリを使ってください。" % stage
        )


# コマンド境界(文頭 / ; / && / | の直後)。ヒアドキュメントや引用符の中に現れた
# 同じ文字列を「実行しようとしている」と誤検知しないための前置き。
BOUNDARY = r"(?:^|[;&|]\s*)"


def check_merge_flags(command):
    if not re.search(BOUNDARY + r"gh\s+pr\s+merge\b", command):
        return
    if "--admin" in command:
        emit_deny(
            "`gh pr merge --admin` は禁止です(CLAUDE.md → Merge Conflicts)。"
            "コンフリクトは作業ブランチ側で解決し、クリーンな状態で squash マージしてください。"
        )
    if re.search(r"--(merge|rebase)\b", command):
        emit_deny(
            "このリポジトリの Issue PR のマージ方式は squash のみです"
            "(CLAUDE.md → Completion Definition)。`--squash` を使ってください。"
        )


def check_no_verify(command):
    if re.search(BOUNDARY + r"git\s+(commit|push)\b", command) and re.search(
        r"--no-verify\b|\-n\b(?=.*\bcommit\b)", command
    ):
        emit_deny(
            "`--no-verify` は禁止です。git フックはこのリポジトリのフェーズ分離"
            "(CLAUDE.md → Test-First Implementation)を強制するためのものです。"
        )


def check_commit_phase(payload, command):
    if not re.search(BOUNDARY + r"git\s+commit\b", command):
        return
    root = project_dir(payload)
    staged = git(["diff", "--cached", "--name-only"], root)
    if staged is None:
        return
    files = [p for p in staged.splitlines() if p.strip()]
    if re.search(r"\bgit\s+commit\b[^|;&]*\s-(a|am|ma)\b", command):
        tracked = git(["diff", "--name-only"], root) or ""
        files += [p for p in tracked.splitlines() if p.strip()]
    tests, prod = classify(files)
    if tests and prod:
        emit_deny(
            "テストコードとプロダクションコードが同じコミットに混在しています"
            "(CLAUDE.md → Test-First Implementation → Never edit tests and production code "
            "in the same phase)。\n\nテスト: %s\nプロダクション: %s\n\n"
            "`git restore --staged <path>` で片側を外し、フェーズごとに分けてコミットしてください。"
            % (", ".join(tests[:10]), ", ".join(prod[:10]))
        )


def check_pr_coverage(payload, command):
    if not re.search(BOUNDARY + r"gh\s+pr\s+create\b", command):
        return
    root = project_dir(payload)
    script = os.path.join(root, "scripts", "check-changed-coverage.py")
    if not os.path.exists(script):
        return
    result = subprocess.run(
        [sys.executable, script], cwd=root, capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        emit_deny(
            "変更したプロダクションコードの C1/C2 カバレッジが基準(90%%)を満たしていないため、"
            "Pull Request を作成できません(CLAUDE.md → Test-First Implementation → Coverage)。\n\n%s"
            % (result.stdout + result.stderr).strip()[:2000]
        )


def cmd_bash(payload):
    command = cmd_of(payload)
    check_read_only(payload, command)
    check_merge_flags(command)
    check_no_verify(command)
    check_commit_phase(payload, command)
    check_pr_coverage(payload, command)
    allow()


def main():
    if len(sys.argv) < 2:
        sys.exit(0)
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)
    {
        "stage": cmd_stage,
        "clear": cmd_clear,
        "write": cmd_write,
        "bash": cmd_bash,
    }.get(sys.argv[1], lambda _: sys.exit(0))(payload)


if __name__ == "__main__":
    main()
