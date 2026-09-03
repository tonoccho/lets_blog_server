#!/usr/bin/env python3
"""Claude Code の PreToolUse フックとして、`.claude/CLAUDE.md` の規約を機械的に強制する。

強制する対象は3つ。いずれも CLAUDE.md 側が唯一の定義であり、ここはその執行機構:

1. **Read-Only Stages** — `discover-issues` / `triage-backlog` / `ready-issue` の実行中は、
   リポジトリ内のいかなるファイルも書き換えさせない。
2. **Test-First Implementation** — テストコードとプロダクションコードを同一コミットに
   混在させない。テストを skip/ignore/削除して緑にすることを許さない。
3. **Completion Definition** — squash 以外のマージ方式を許さない。

使い方(settings.json から):
    guard.py stage   # PreToolUse: Skill        読み取り専用ステージの開始/終了を記録
    guard.py clear   # UserPromptSubmit         ステージ状態をリセット
    guard.py write   # PreToolUse: Write|Edit|NotebookEdit
    guard.py bash    # PreToolUse: Bash
"""

import json
import os
import re
import shlex
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

# 読み取り専用ステージ中に禁止するコマンド。**コマンド名で判定する**(#986)。
# 旧実装は生の文字列に `\b(rm|mv|cp|tee|patch|truncate)\b` をかけていたため、
# `grep -n 'patch' CHANGELOG.md` や `cat docs/patch-notes.md` のように、検索語や
# ファイル名にこれらの語が含まれるだけの調査コマンドまで拒否していた。
# 読み取り専用ステージの目的はリポジトリを変更させないことであって、調査を
# 妨げることではない。
DESTRUCTIVE_COMMANDS = {
    "rm": "ファイルを削除するコマンド",
    "mv": "ファイルを移動するコマンド",
    "cp": "ファイルを複製するコマンド",
    "tee": "ファイルへ書き出すコマンド",
    "patch": "ファイルへパッチを当てるコマンド",
    "truncate": "ファイルを切り詰めるコマンド",
    "install": "ファイルを配置するコマンド",
}

# 状態を変える git のサブコマンド。`git log` / `git diff` / `git show` は読み取りなので通す。
MUTATING_GIT = {
    "add", "commit", "checkout", "switch", "branch", "merge", "rebase", "stash",
    "restore", "reset", "push", "apply", "cherry-pick", "tag", "rm", "mv",
}

# ロックファイルを書き換える依存インストール。
PACKAGE_MANAGERS = {"npm", "pnpm", "yarn", "bun"}
INSTALL_SUBCOMMANDS = {"i", "install", "ci", "add", "update", "upgrade", "remove", "uninstall"}


def destructive_reason(argv):
    """読み取り専用ステージで禁止すべきコマンドなら、その説明を返す。"""
    if not argv:
        return None
    name = os.path.basename(argv[0])
    args = argv[1:]

    if name in DESTRUCTIVE_COMMANDS:
        return DESTRUCTIVE_COMMANDS[name]

    # `sed -i` だけが書き込む。`sed -n '1,5p' file` は読み取り。
    if name == "sed":
        for arg in args:
            if arg == "--in-place" or arg.startswith("--in-place="):
                return "sed --in-place"
            if arg.startswith("-") and not arg.startswith("--") and "i" in arg[1:]:
                return "sed -i"
        return None

    if name == "git":
        for arg in args:
            if arg.startswith("-"):
                continue
            return "リポジトリ状態を変える git コマンド" if arg in MUTATING_GIT else None
        return None

    if name in PACKAGE_MANAGERS:
        for arg in args:
            if arg.startswith("-"):
                continue
            return "ロックファイルを書き換える依存インストール" if arg in INSTALL_SUBCOMMANDS else None
        return None

    return None


class Denied(Exception):
    """explain モードで、拒否理由を出力せずに受け取るための例外。"""

    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


# explain モードでは emit_deny が標準出力に書かず Denied を送出する。
# フック本来の経路(stdout に JSON、exit 0)を explain のために変えたくないため。
EXPLAIN_MODE = False


def emit_deny(reason):
    if EXPLAIN_MODE:
        raise Denied(reason)
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
            "見つけた問題は直さずに GitLab Issue として登録し、報告してください。"
            "一時メモはスクラッチパッドディレクトリへ。" % (stage, rel)
        )

    if inside and not re.match(r"^(\.claude|docs|scripts)/", rel) and not rel.endswith(".md"):
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

    parsed = simple_commands(command)
    if parsed is None:
        # 解析できないコマンドは、読み取り専用ステージでは通さない。ここだけは
        # 過検知に倒す(調査は解析できる書き方でやり直せるが、見逃しは戻せない)。
        emit_deny(
            "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
            "コマンドを解析できなかったため実行を許可できません"
            "(引用符が閉じていない可能性があります)。" % stage
        )

    for argv, redirects in parsed:
        reason = destructive_reason(argv)
        if reason:
            emit_deny(
                "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
                "%s は実行できません。許可されているのは GitLab Issue の操作(`glab issue` / "
                "`status::` ラベルによるステータス更新)と読み取り専用の調査だけです。"
                % (stage, reason)
            )
        # リダイレクトはトークンとして現れたものだけを見る。引用符の内側の `>` は
        # トークンに埋もれるので、`awk '$1 > 5'` や `python3 -c '... if x>3 ...'` は
        # ここに来ない(#986)。
        for target in redirects:
            if target in ("/dev/null", "/dev/stderr", "/dev/stdout"):
                continue
            emit_deny(
                "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
                "リダイレクトによるファイル書き込み(%s)は実行できません。"
                "一時ファイルが要る場合はスクラッチパッドディレクトリを使ってください。"
                % (stage, target)
            )


# --------------------------------------------------------------- コマンドの解析
#
# ここが #1029 と #986 の共通の修正点である。
#
# 旧実装は、コマンド文字列を**構文解析せずに**正規表現へかけていた。その結果、
# 逆向きの2つの欠陥が同時に存在していた。
#
#   #1029(偽陰性): 判定を `BOUNDARY = (?:^|[;&|]\s*)` でコマンド先頭に固定していた
#     ため、`timeout 60 git push --no-verify` のように前置詞を伴うだけでガードが
#     黙って外れた。故意の迂回ではなく、普通の書き方で踏む。
#   #986(偽陽性): 引用を解釈しないため、`grep -n 'patch' file` や
#     `python3 -c '... if len(b)>3000 ...'` の引用符の内側を、破壊的コマンドや
#     シェルのリダイレクトと誤認して正当な調査を拒否した。
#
# どちらも「そのコマンドが実際に何を実行するか」を見ていないことから来ている。
# 以下は、コマンド文字列を「実行される個々のコマンド」へ分解する。
#
# **完全性は主張しない。** シェルは `bash -c '...'`、`eval`、変数展開といった
# 任意の間接実行を許すので、この方式でガードを完全にすることはできない。
# 目的は「うっかりを止めること」であって「悪意ある迂回を防ぐこと」ではない
# (CLAUDE.md → Enforcement)。悪意ある迂回に対する防御は、GitLab の保護ブランチ
# 設定と git フック側にある。

# コマンドの区切り。shlex に punctuation_chars を与えると、これらは
# 独立したトークンとして出てくる(引用符の内側にあるものは分割されない)。
SEPARATORS = {";", "&", "&&", "|", "||", "\n"}

# 出力のリダイレクト。トークンとして現れたものだけがシェルの演算子であり、
# 引用符の内側の `>` は文字列の一部としてトークンに埋もれる。
REDIRECTS = {">", ">>", ">|", ">&"}

# 先頭から剥がすラッパー。値を取る引数を持つものは、その分も読み飛ばす。
# 網羅は不可能なので、これは「既知の穴を塞ぐ」列挙である。
#   name: (値を取るフラグの集合, フラグ以外の引数をいくつ読み飛ばすか)
WRAPPERS = {
    "timeout": ({"-s", "--signal", "-k", "--kill-after"}, 1),  # 継続時間を1つ取る
    "env": (set(), 0),
    "nice": ({"-n", "--adjustment"}, 0),
    "ionice": ({"-c", "-n", "-p"}, 0),
    "sudo": ({"-u", "--user", "-g", "--group", "-p", "--prompt"}, 0),
    "time": (set(), 0),
    "command": (set(), 0),
    "nohup": (set(), 0),
    "stdbuf": ({"-i", "-o", "-e"}, 0),
    "xargs": ({"-I", "-n", "-P", "-d", "-a", "-E", "-s"}, 0),
    "setsid": (set(), 0),
}

ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")


def split_commands(command):
    """コマンド文字列を「実行される個々のコマンド」のトークン列へ分解する。

    戻り値は `(argv, redirect_targets)` の並び。解析できない場合は None を返す
    (呼び出し側が保守的なフォールバックへ倒すため。空リストと区別する)。
    """
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        tokens = list(lexer)
    except ValueError:
        # 引用符が閉じていない等。解析できないものを「該当なし」と扱うと
        # #1029 と同じ見逃しになるので、呼び出し側で生文字列へフォールバックする。
        return None

    commands = []
    argv = []
    redirects = []
    expect_redirect_target = False
    for token in tokens:
        if expect_redirect_target:
            redirects.append(token)
            expect_redirect_target = False
            continue
        if token in SEPARATORS:
            if argv or redirects:
                commands.append((argv, redirects))
            argv, redirects = [], []
            continue
        if token in REDIRECTS:
            expect_redirect_target = True
            continue
        argv.append(token)
    if argv or redirects:
        commands.append((argv, redirects))
    return commands


def strip_wrappers(argv):
    """環境変数代入と既知のラッパーを剥がし、(実体のargv, 剥がしたもの) を返す。"""
    stripped = []
    argv = list(argv)
    while argv:
        head = argv[0]
        if ASSIGNMENT.match(head):
            stripped.append(argv.pop(0))
            continue
        name = os.path.basename(head)
        if name not in WRAPPERS:
            break
        value_flags, positionals = WRAPPERS[name]
        stripped.append(argv.pop(0))
        # ラッパー自身のフラグを読み飛ばす。値を取るフラグは次のトークンも。
        while argv and argv[0].startswith("-") and argv[0] != "--":
            flag = argv.pop(0)
            base = flag.split("=", 1)[0]
            if base in value_flags and "=" not in flag and len(base) == len(flag):
                if argv:
                    argv.pop(0)
        if argv and argv[0] == "--":
            argv.pop(0)
        for _ in range(positionals):
            if argv:
                stripped.append(argv.pop(0))
    return argv, stripped


def simple_commands(command):
    """ラッパーを剥がした後の argv の並びを返す。解析不能なら None。"""
    parsed = split_commands(command)
    if parsed is None:
        return None
    out = []
    for argv, redirects in parsed:
        real, _ = strip_wrappers(argv)
        out.append((real, redirects))
    return out


def invokes(command, program, subcommands=()):
    """`program`(必要なら続く部分コマンド)を実行する箇所の残り引数を列挙する。

    解析できない場合は、生の文字列に対する緩い照合へフォールバックする。
    見逃す(#1029)よりは過検知に倒す。
    """
    parsed = simple_commands(command)
    if parsed is None:
        pattern = r"\b" + re.escape(program) + r"\b"
        if subcommands:
            pattern += r"\s+" + r"\s+".join(re.escape(s) for s in subcommands) + r"\b"
        return [command.split()] if re.search(pattern, command) else []

    found = []
    for argv, _ in parsed:
        if not argv or os.path.basename(argv[0]) != program:
            continue
        rest = argv[1:]
        if subcommands:
            positional = [a for a in rest if not a.startswith("-")]
            if positional[: len(subcommands)] != list(subcommands):
                continue
        found.append(rest)
    return found


# マージ方式のフラグ。長いフラグと、cobra が受け付ける短縮フラグの結合(`-sd`)の両方。
# `--squash-message` はコミットメッセージの指定であって方式の指定ではないので、
# `--squash` の前方一致で拾ってはいけない。`-R`(--repo)は `-r`(--rebase)ではない。
def _has_flag(args, long_name, short):
    for arg in args:
        if arg == long_name or arg.startswith(long_name + "="):
            return True
        if arg.startswith("--"):
            continue
        if arg.startswith("-") and len(arg) > 1 and short in arg[1:]:
            return True
    return False


def check_merge_flags(command):
    """CLAUDE.md → Completion Definition: Issue の MR は squash のみ。

    `gh pr merge` は方式を指定しないと対話的に尋ねる仕様だったため、旧実装は
    `--merge` / `--rebase` を明示したときだけ拒否すれば足りていた。GitLab は違う。
    `glab mr merge` に方式のフラグを付けないと**黙ってマージコミットを作る**
    (プロジェクト設定 `squash_option` が `default_off` のため)。したがって判定は
    「禁止フラグの検出」ではなく「squash 指定の要求」でなければならない。

    `--admin` に相当する管理者バイパスは GitLab には無い。保護ブランチの回避は
    フックではなく GitLab 側の権限設定で防ぐ(CLAUDE.md → Merge Conflicts)。
    """
    for args in invokes(command, "glab", ("mr", "merge")):
        if _has_flag(args, "--rebase", "r"):
            emit_deny(
                "このリポジトリの Issue MR のマージ方式は squash のみです"
                "(CLAUDE.md → Completion Definition)。`--rebase` は使えません。"
                "`glab mr merge --squash --remove-source-branch` を使ってください。"
            )
        if not _has_flag(args, "--squash", "s"):
            emit_deny(
                "`glab mr merge` にマージ方式が指定されていません。GitLab は方式未指定だと"
                "マージコミットを作ります(このプロジェクトの squash_option は default_off)。"
                "このリポジトリの Issue MR は squash のみです"
                "(CLAUDE.md → Completion Definition)。"
                "`glab mr merge --squash --remove-source-branch` を使ってください。"
            )


# Issue のラベルを変える呼び出しから、`-f key=value` / `--field key=value` を拾う。
LABEL_FIELD = re.compile(r"^(labels|add_labels|remove_labels)=(.*)$", re.S)


def _label_fields(args):
    """`-f`/`--field` で渡されたラベル関連の値を {key: value} で返す。"""
    fields = {}
    i = 0
    while i < len(args):
        arg = args[i]
        value = None
        if arg in ("-f", "--field", "-F", "--raw-field"):
            if i + 1 < len(args):
                value = args[i + 1]
                i += 1
        elif arg.startswith("--field="):
            value = arg[len("--field="):]
        i += 1
        if value is None:
            continue
        match = LABEL_FIELD.match(value)
        if match:
            fields[match.group(1)] = match.group(2)
    return fields


def _has_status(value):
    return any(part.strip().startswith("status::") for part in value.split(","))


def check_status_label_integrity(command):
    """CLAUDE.md → How to change status: ステータスは常にちょうど1つ(#1023)。

    GitHub Projects の Status は単一選択フィールドで、2つ持つことは構造的に不可能
    だった。GitLab CE のラベルにその保証は無い(スコープ付きラベルは Premium)。
    ワークフローの選択ロジックはこの一意性に依拠している。

    ここで止めるのは、実際に起きる2つの壊し方だけである。両方とも**構文だけで**
    判定できる — Issue の現在のラベルを問い合わせないので、フックは速いままで、
    ネットワークにも認証にも依存しない。
    """
    for args in invokes(command, "glab", ()):
        # Issue への PUT だけが対象。作成(`glab issue create --label`)は遷移ではなく、
        # 最初のステータスはそこで付く。読み取りも対象外。
        if "--method" not in args and "-X" not in args:
            continue
        if not any(re.search(r"issues/\d+", a) for a in args):
            continue
        method = ""
        for i, a in enumerate(args):
            if a in ("--method", "-X") and i + 1 < len(args):
                method = args[i + 1].upper()
        if method != "PUT":
            continue

        fields = _label_fields(args)

        if "labels" in fields:
            emit_deny(
                "`labels=` はラベル集合の**上書き**です。Issue が持っている `epic` や "
                "`bug` などのラベルが黙って消えます(CLAUDE.md → How to change status)。"
                "`add_labels=` と `remove_labels=` を使ってください。"
            )

        added = fields.get("add_labels", "")
        removed = fields.get("remove_labels", "")
        if _has_status(added) and not _has_status(removed):
            emit_deny(
                "ステータスを足すだけの呼び出しです。GitLab CE のラベルに排他性は無いので"
                "(スコープ付きラベルは Premium)、これでは `status::` が2つになります"
                "(CLAUDE.md → How to change status)。"
                "同じ呼び出しに `remove_labels=status::<現在の値>` を含めてください。"
            )
        if _has_status(removed) and not _has_status(added):
            emit_deny(
                "ステータスを外すだけの呼び出しです。`status::` が0個の Issue は"
                "ボードのどの列にも現れず、`work-next` からも triage からも見えなくなります"
                "(CLAUDE.md → How to change status)。"
                "同じ呼び出しに `add_labels=status::<次の値>` を含めてください。"
            )


def check_no_verify(command):
    for sub in ("commit", "push"):
        for args in invokes(command, "git", (sub,)):
            if "--no-verify" in args or (sub == "commit" and "-n" in args):
                emit_deny(
                    "`--no-verify` は禁止です。git フックはこのリポジトリのフェーズ分離"
                    "(CLAUDE.md → Test-First Implementation)を強制するためのものです。"
                )


def check_commit_phase(payload, command):
    commits = invokes(command, "git", ("commit",))
    if not commits:
        return
    root = project_dir(payload)
    staged = git(["diff", "--cached", "--name-only"], root)
    if staged is None:
        return
    files = [p for p in staged.splitlines() if p.strip()]
    if any(_has_flag(args, "--all", "a") for args in commits):
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
    if not invokes(command, "glab", ("mr", "create")):
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
            "Merge Request を作成できません(CLAUDE.md → Test-First Implementation → Coverage)。\n\n%s"
            % (result.stdout + result.stderr).strip()[:2000]
        )


def cmd_bash(payload):
    command = cmd_of(payload)
    check_read_only(payload, command)
    check_merge_flags(command)
    check_status_label_integrity(command)
    check_no_verify(command)
    check_commit_phase(payload, command)
    check_pr_coverage(payload, command)
    allow()


# --------------------------------------------------------------------- explain


def cmd_explain(command):
    """コマンドをどう解析し、どう判定したかを表示する。

    #1029 が長く気づかれなかったのは、ガードが対象コマンドを**どう読んだか**を
    確認する手段が無かったためである。`timeout 60 git push --no-verify` が通るのを見て
    「フックが動いていない」と誤診断した(#1029 のコメント参照)。実際にはフックは
    動いており、判定が前置詞で外れていた。この2つを区別できるようにする。

        python3 .claude/hooks/guard.py explain '<コマンド>'
    """
    global EXPLAIN_MODE
    EXPLAIN_MODE = True

    print("入力: %s" % command)
    parsed = split_commands(command)
    if parsed is None:
        print("解析: 失敗(引用符が閉じていない可能性)")
        print("      通常のガードは生文字列への緩い照合へフォールバックする")
        print("      読み取り専用ステージでは解析不能そのものを拒否する")
    else:
        print("解析: %d 個のコマンド" % len(parsed))
        for i, (argv, redirects) in enumerate(parsed, 1):
            real, stripped = strip_wrappers(argv)
            print("  [%d] 実体      : %s" % (i, real))
            if stripped:
                print("      剥がした前置: %s" % stripped)
            if redirects:
                print("      書き込み先  : %s" % redirects)
            reason = destructive_reason(real)
            if reason:
                print("      読み取り専用ステージ: 拒否(%s)" % reason)

    for label, check in (
        ("マージ方式", check_merge_flags),
        ("--no-verify 禁止", check_no_verify),
    ):
        try:
            check(command)
        except Denied as denied:
            print("判定: DENY [%s] %s" % (label, denied.reason))
            return 0

    if invokes(command, "glab", ("mr", "create")):
        print("判定: glab mr create を検出。カバレッジ検査が走る(結果は計測次第)")
        return 0

    print("判定: allow(このコマンドを止めるガードは無い)")
    return 0


def main():
    if len(sys.argv) < 2:
        sys.exit(0)
    if sys.argv[1] == "explain":
        sys.exit(cmd_explain(" ".join(sys.argv[2:])))
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
