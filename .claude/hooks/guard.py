#!/usr/bin/env python3
"""Claude Code の PreToolUse フックとして、`.claude/CLAUDE.md` の規約を機械的に強制する。

強制する対象は3つ。いずれも CLAUDE.md 側が唯一の定義であり、ここはその執行機構:

1. **Read-Only Stages** — `discover-issues` / `triage-backlog` / `ready-issue` / `report-bug`
   の実行中は、リポジトリ内のいかなるファイルも書き換えさせない。
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
from paths import classify, is_production, is_test, strip_worktree  # noqa: E402
from silencers import SILENCERS  # noqa: E402

READ_ONLY_SKILLS = {"discover-issues", "triage-backlog", "ready-issue", "report-bug"}

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

    # 免除の判定も worktree の接頭辞を剥がしてから行う(#1036)。剥がさないと、
    # worktree 内のプロダクションコードへの `test.skip` 追加が `.claude/` の免除で
    # 素通りする。
    rel_real = strip_worktree(rel)
    if inside and not re.match(r"^(\.claude|docs|scripts)/", rel_real) and not rel_real.endswith(".md"):
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
        # ここに来ない(#986)。fd 複製(`2>&1`)は split_commands が既に落としている(#1034)。
        for target in redirects:
            if target in ("/dev/null", "/dev/stderr", "/dev/stdout"):
                continue
            # 文言は実装に合わせる(#1034 Requirement 4、方針A)。以前は
            # 「一時ファイルが要る場合はスクラッチパッドディレクトリを使ってください」と
            # 案内していたが、**この検査はリダイレクト先を問わないため、スクラッチパッドへ
            # 書こうとしても同じく拒否される**。実行できない手段を案内していた。
            #
            # 実装をメッセージに寄せる(スクラッチパッド配下だけ許可する)道もあるが、
            # それは Read-Only Stages の境界を「リポジトリ内を書き換えない」から
            # 「特定ディレクトリ以外を書き換えない」へ広げる変更であり、影響が大きい。
            # ここでは案内のほうを事実に合わせる。
            emit_deny(
                "`%s` は読み取り専用ステージです(CLAUDE.md → Read-Only Stages)。"
                "リダイレクトによるファイル書き込み(%s)は実行できません。"
                "**書き込み先を問わず**拒否されます(スクラッチパッドも含む)。"
                "この段階で必要な情報は、パイプと標準出力だけで組み立ててください。"
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
#
# 演算子は2種類あり、**ターゲットの意味が逆になる**(#1034)。
#
#   ファイルへ書く   `>` `>>` `>|` `&>`  … 直後のトークンはファイル名
#   fd を複製する    `>&`               … 直後のトークンは fd 番号か `-`(クローズ)
#
# `2>&1` はファイルを1バイトも作らない。これをファイル名として許可リストに
# かけていたため、読み取り専用ステージで正当な調査が拒否されていた。
# 逆に `&>` は列挙から漏れており、`echo x &> real.txt` が素通りしていた。
# `&>/dev/null` が通っていたのは正しく除外されていたからではなく、
# **オペレータ自体が見えていなかった偶然**である。
FILE_REDIRECTS = {">", ">>", ">|", "&>"}
FD_DUPLICATIONS = {">&"}
REDIRECTS = FILE_REDIRECTS | FD_DUPLICATIONS

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


def _heredoc_marker(line):
    """`line` がヒアドキュメント演算子を含むなら `(終端語, タブ除去するか)` を返す。

    引用符の内側の `<<` を演算子と誤認しないよう、この1行だけを shlex で解析する
    (本文はまだ読んでいないので、この行の中に閉じない引用符が無い限り安全)。
    `<<WORD` と `<<-WORD` はどちらも `<<` トークンの直後に `WORD`(`<<-` の場合は
    `-WORD`)が続く形で出てくる — `-` は句読点文字ではないので、`<<-` 自体が
    1つのトークンになることはない。
    """
    try:
        lexer = shlex.shlex(line, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        tokens = list(lexer)
    except ValueError:
        return None
    for i, token in enumerate(tokens):
        if token == "<<" and i + 1 < len(tokens):
            word = tokens[i + 1]
            strip_tabs = word.startswith("-")
            return (word[1:] if strip_tabs else word), strip_tabs
    return None


def strip_heredoc_bodies(command):
    """ヒアドキュメントの本文行を取り除いた文字列を返す。

    本文中の `;` `|` `>` はシェルの演算子ではなく、ただの文字である(#1035)。
    行単位で処理し、終端語(`<<-` なら先頭タブを落としてから比較)と完全一致する
    行が来るまで、本文行を丸ごと落とす。落とした行(終端行を含む)は出力に含めない
    — 空行を残しても `split_commands` の判定に影響しないため、追跡は不要。

    **扱える範囲**: 1行につきヒアドキュメント演算子は1つまで。同じ行に複数の
    ヒアドキュメント(`cmd <<A <<B`)が並ぶ場合、2つ目以降は対象外(最初の演算子
    の本文だけを終端語まで読み飛ばし、その後は通常どおり解析される)。改行を
    跨がない `<<<`(herestring)はそもそも対象外(演算子自体が別物で、本文を
    複数行に持たない)。
    """
    if "<<" not in command:
        return command
    lines = command.split("\n")
    out = []
    delimiter = None
    strip_tabs = False
    for line in lines:
        if delimiter is not None:
            candidate = line.lstrip("\t") if strip_tabs else line
            if candidate == delimiter:
                delimiter = None
                strip_tabs = False
            continue
        out.append(line)
        marker = _heredoc_marker(line)
        if marker is not None:
            delimiter, strip_tabs = marker
    return "\n".join(out)


PROCESS_SUBSTITUTION_OPENERS = {"<(", ">("}


def split_commands(command):
    """コマンド文字列を「実行される個々のコマンド」のトークン列へ分解する。

    戻り値は `(argv, redirect_targets)` の並び。解析できない場合は None を返す
    (呼び出し側が保守的なフォールバックへ倒すため。空リストと区別する)。

    ヒアドキュメントの本文は事前に取り除く(`strip_heredoc_bodies`)。プロセス
    置換 `>(...)` / `<(...)` の中身は、外側のコマンドの引数としてではなく、
    それ自体が独立して実行される1コマンドとして `commands` に加える —
    実際のシェルでもサブシェルとして実行されるので、この分解は虚構ではない。

    **扱える範囲**: 1階層のプロセス置換。入れ子(`diff <(cat <(x)) y`)は、
    最も内側の境界まで丸ごと1つの塊として扱うため、内側の置換だけを独立した
    コマンドとして取り出すことはしない(完全性は主張しない。CLAUDE.md →
    Enforcement → What the guards are, and are not)。
    """
    lexer = shlex.shlex(strip_heredoc_bodies(command), posix=True, punctuation_chars=True)
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
    # None = ターゲットを待っていない / True = ファイル名を待つ / False = fd を待つ
    expect_target = None
    i = 0
    n = len(tokens)
    while i < n:
        token = tokens[i]
        i += 1
        if expect_target is not None:
            # fd 複製のターゲット(`1` / `2` / `-`)はファイルではないので捨てる。
            if expect_target:
                redirects.append(token)
            expect_target = None
            continue
        if token in PROCESS_SUBSTITUTION_OPENERS:
            # 開き括弧1つ分から始まる。句読点文字はまとめて1トークンになるので
            # (`))` のように)、閉じ括弧の分だけ深さを引き、1文字ずつではなく
            # トークン単位で追う。
            depth = 1
            inner = []
            while i < n and depth > 0:
                t = tokens[i]
                i += 1
                delta = t.count("(") - t.count(")")
                if depth + delta <= 0:
                    trimmed = t
                    remaining = depth
                    while remaining > 0 and trimmed.endswith(")"):
                        trimmed = trimmed[:-1]
                        remaining -= 1
                    if trimmed:
                        inner.append(trimmed)
                    depth = 0
                else:
                    inner.append(t)
                    depth += delta
            if inner:
                commands.append((inner, []))
            continue
        if token in SEPARATORS:
            if argv or redirects:
                commands.append((argv, redirects))
            argv, redirects = [], []
            continue
        if token in FD_DUPLICATIONS:
            expect_target = False
            continue
        if token in FILE_REDIRECTS:
            expect_target = True
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


def _flag_value(args, long_name):
    """`--flag value` / `--flag=value` の値を返す。指定が無ければ None。"""
    for i, arg in enumerate(args):
        if arg == long_name:
            return args[i + 1] if i + 1 < len(args) else None
        if arg.startswith(long_name + "="):
            return arg[len(long_name) + 1 :]
    return None


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


def _status_name(value):
    """`value`(`add_labels=`/`remove_labels=` の右辺)から `status::` の中身を返す。

    複数のステータスラベルが混じっていた場合は最初の1つだけを見る。それ以外は
    #1023 の一意性チェック(この関数の呼び出し元より前)がすでに拒否している。
    """
    for part in value.split(","):
        part = part.strip()
        if part.startswith("status::"):
            return part[len("status::"):]
    return None


# CLAUDE.md → How to change status → Legal Transitions が単一の定義であり、ここは
# それをデータとして符号化しているだけ(#1031)。表そのものの根拠・網羅性の検証は
# CLAUDE.md 側に書く。ここに理由を再掲しない。
LEGAL_STATUS_TRANSITIONS = {
    # 前進
    ("Inbox", "Backlog"),
    ("Backlog", "Ready"),
    ("Ready", "In Progress"),
    ("In Progress", "Review"),
    ("Review", "QA"),
    ("QA", "Done"),
    # 差し戻し
    ("Review", "In Progress"),
    ("QA", "In Progress"),
    ("Ready", "Backlog"),
    ("Review", "Backlog"),
    ("In Progress", "Ready"),
}


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

        # ここまでで「片側だけの付け外し」は拒否済みなので、残るのは
        # 「どちらも status:: を含まない(このステータス変更とは無関係)」か
        # 「両方が status:: を含む(実際の遷移)」のどちらか。後者だけを遷移表で検証する。
        old_status = _status_name(removed)
        new_status = _status_name(added)
        if old_status and new_status and (old_status, new_status) not in LEGAL_STATUS_TRANSITIONS:
            emit_deny(
                "`status::%s → status::%s` は正当な遷移として定義されていません"
                "(CLAUDE.md → How to change status → Legal Transitions)。"
                "段を飛ばした遷移か、定義されていない差し戻しです。"
                "定義済みの遷移の一覧は CLAUDE.md を参照してください。"
                % (old_status, new_status)
            )


def _short_flag_attached_value(arg, short):
    """pflag の短縮形の値直結形から値を取り出す。該当しなければ None。

    pflag は値を取るフラグの短縮形について、`-l hotfix`(空白区切り)だけでなく
    `-lhotfix`(直結)と `-l=hotfix`(`=` 付き直結、`=` は剥がされる)も同じ値として
    受け付ける。実機の glab(1.116.0)で確認済み: `-lhotfix`/`-uhotfix`/`-l=hotfix`/
    `-u=hotfix` はいずれもパースエラーにならずネットワーク呼び出しに到達する
    (対照として無効な短縮形 `-zhotfix` は `Unknown shorthand flag` になる)。
    `check_merge_flags` の `_has_flag` はブール短縮フラグの結合(`-sd`)用で、値を
    取るフラグのこの直結形は別物なので使い回さない。
    """
    prefix = "-" + short
    if arg.startswith("--") or not arg.startswith(prefix) or len(arg) <= len(prefix):
        return None
    value = arg[len(prefix):]
    if value.startswith("="):
        value = value[1:]
    return value


def _issue_update_label_args(args):
    """`glab issue update` の `-l/--label` / `-u/--unlabel` から、カンマ区切りのラベル名を集める。

    `_label_fields` は `-f key=value` 形式(`add_labels=`/`remove_labels=`)専用で、
    `--label`/`--unlabel` はラベル名そのものをカンマ区切りで渡す別形式なので使い回せない
    (#1433 の Readiness Report)。返り値は (追加されたラベル名のリスト, 削除されたラベル名のリスト)。
    """
    added = []
    removed = []
    i = 0
    while i < len(args):
        arg = args[i]
        target = None
        value = None
        if arg in ("-l", "--label"):
            target = added
            if i + 1 < len(args):
                value = args[i + 1]
                i += 1
        elif arg.startswith("--label="):
            target = added
            value = arg[len("--label="):]
        elif arg in ("-u", "--unlabel"):
            target = removed
            if i + 1 < len(args):
                value = args[i + 1]
                i += 1
        elif arg.startswith("--unlabel="):
            target = removed
            value = arg[len("--unlabel="):]
        else:
            attached = _short_flag_attached_value(arg, "l")
            if attached is not None:
                target = added
                value = attached
            else:
                attached = _short_flag_attached_value(arg, "u")
                if attached is not None:
                    target = removed
                    value = attached
        i += 1
        if value is None:
            continue
        target.extend(part.strip() for part in value.split(",") if part.strip())
    return added, removed


def check_hotfix_label_immutability(command):
    """CLAUDE.md → Issue Provenance → hotfix: 付与・削除はユーザーのみ(#1433)。

    `hotfix` は選択順の第0キーで、`bug` と同じく Claude は読むだけの前提に立っている。
    GitLab CE のラベルにこれを守らせる仕組みは無い(スコープ付きラベルは Premium)ので、
    既存 Issue への `hotfix` の付け外しは Claude の呼び出しの時点で一律に拒否する。

    `check_status_label_integrity` を拡張せず**独立した関数**にしているのは、あちらが
    `status::` 専用のロジック(一意性・遷移表)と密結合しているためで、両者は無関係な
    壊れ方を検査している。#1389 が同じ既存関数を触る計画があることとも独立に保てる。

    上限3件の判定はここではしない。現在の件数を知るには API 問い合わせが要り、
    guard.py はネットワークを使わない方針(CLAUDE.md → Enforcement)。上限超過の
    事後検出は `scripts/check-issue-labels.sh` の役目。

    `glab issue create` は対象外(遷移ではなく新規作成。#1434 で扱う。現状維持)。
    """

    def has_hotfix(value):
        return any(part.strip() == "hotfix" for part in value.split(","))

    # `glab api projects/:id/issues/<n> --method PUT` の add_labels=/remove_labels=
    for args in invokes(command, "glab", ()):
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
        added = fields.get("add_labels", "")
        removed = fields.get("remove_labels", "")
        if has_hotfix(added) or has_hotfix(removed):
            emit_deny(
                "`hotfix` の付与・削除は禁止です。既存 Issue の `hotfix` はユーザーのみが"
                "操作します。Claude は読むだけです(CLAUDE.md → Issue Provenance → hotfix)。"
            )

    # `glab issue update <n> --label/--unlabel`
    for args in invokes(command, "glab", ("issue", "update")):
        added, removed = _issue_update_label_args(args)
        if "hotfix" in added or "hotfix" in removed:
            emit_deny(
                "`hotfix` の付与・削除は禁止です。既存 Issue の `hotfix` はユーザーのみが"
                "操作します。Claude は読むだけです(CLAUDE.md → Issue Provenance → hotfix)。"
            )


def check_hotfix_creation(payload, command):
    """CLAUDE.md → Issue Provenance → hotfix: 作成時の付与は `report-bug` の実行中だけ(#1434)。

    `check_status_label_integrity` は `glab issue create` を遷移ではないとして素通りさせ
    (681-682行のコメント)、`check_hotfix_label_immutability` も「作成は対象外(遷移ではなく
    新規作成。#1434 で扱う)」と明記して現状を維持している。ここはその隙間を埋める。
    #1433 の Readiness Report が指摘したとおり、`check_status_label_integrity` を拡張せず
    **独立した関数**として追加する — あちらは `status::` 専用のロジック(一意性・遷移表)と
    密結合しており、この判定は無関係な壊れ方を見ている。

    判定には read-only stage マーカー(`read_stage`)をそのまま使う。ネットワーク呼び出しは
    無い。マーカーは `cmd_stage`(`Skill` フックの PreToolUse)が `READ_ONLY_SKILLS` に
    含まれるスキル名だけを書き込むので、`report-bug` がこの集合に入っていないと、
    `report-bug` 自身の起票呼び出しもここで拒否されてしまう(#1434 Readiness Report の
    実装順序の注意)。`READ_ONLY_SKILLS` への `report-bug` の追加がこの関数より先に
    必要な理由はそこにある。

    ラベル値の抽出は `_issue_update_label_args` を再利用する。`glab issue create` の
    `--label`/`-l` は `glab issue update` と同じ pflag の記法(空白区切り、`--label=`、
    `-l` の値直結形、`-l=` のカンマ併記)を受け付けるため、専用の解析を新たに書かない。
    """
    for args in invokes(command, "glab", ("issue", "create")):
        added, _ = _issue_update_label_args(args)
        if "hotfix" not in added:
            continue
        if read_stage(payload) != "report-bug":
            emit_deny(
                "`glab issue create` に `hotfix` を含めての起票は `/report-bug` の実行中"
                "だけ許可されます(CLAUDE.md → Issue Provenance → hotfix)。"
                "`/report-bug` はここに至る前に open な `hotfix` の件数を数え、上限(3件)で"
                "止まります。他のスキルや手動呼び出しから `hotfix` 付きで起票することは"
                "できません。"
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


def coverage_worktree_root(payload):
    """カバレッジ検査の対象にすべき作業ツリーのルートを返す(#1229)。

    `project_dir()` は `CLAUDE_PROJECT_DIR` を `payload["cwd"]` より優先するため、
    `glab mr create` を worktree から実行した場合にセッションのメイン作業ツリーを
    指してしまう — worktree の HEAD ではなく、その時メインツリーがたまたま乗っていた
    ブランチが計測される。

    ここではその優先順位を逆にする: **ツール呼び出しの実際の cwd
    (`payload["cwd"]`)を優先し**、それが取れない場合だけ `CLAUDE_PROJECT_DIR` に
    フォールバックする。取得できた cwd は `git rev-parse --show-toplevel` でその
    worktree 自身のルートに正規化する — worktree のサブディレクトリから呼ばれても、
    そのツリー自身の HEAD を指すようにするため。

    この関数はカバレッジ検査だけに使う。他のガード(フェーズ分離・マージ方式など)の
    `project_dir()` 依存はこの Issue の Scope 外であり、ここでは変更しない。
    """
    cwd = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    cwd = os.path.realpath(cwd)
    toplevel = git(["rev-parse", "--show-toplevel"], cwd)
    if toplevel:
        return os.path.realpath(toplevel.strip())
    return cwd


def check_pr_coverage(payload, command):
    calls = invokes(command, "glab", ("mr", "create"))
    if not calls:
        return
    root = coverage_worktree_root(payload)
    branch_out = git(["rev-parse", "--abbrev-ref", "HEAD"], root)
    branch = branch_out.strip() if branch_out else None

    if branch:
        for args in calls:
            source = _flag_value(args, "--source-branch")
            if source and source != branch:
                emit_deny(
                    "`--source-branch %s` が、実際にカバレッジを計測したブランチ `%s`"
                    "(%s)と食い違っています(CLAUDE.md → Test-First Implementation → "
                    "Coverage)。\n食い違ったまま Merge Request を作ると、指定と別の"
                    "ブランチが計測されます。`--source-branch` を計測対象のブランチに"
                    "合わせるか、計測対象のブランチをチェックアウトしてください。"
                    % (source, branch, root)
                )

    script = os.path.join(root, "scripts", "check-changed-coverage.py")
    if not os.path.exists(script):
        return
    result = subprocess.run(
        [sys.executable, script], cwd=root, capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        emit_deny(
            "変更したプロダクションコードの C1/C2 カバレッジが基準(90%%)を満たしていないため、"
            "Merge Request を作成できません(CLAUDE.md → Test-First Implementation → Coverage)。\n"
            "計測したブランチ: %s (%s)\n\n%s"
            % (branch or "(不明)", root, (result.stdout + result.stderr).strip()[:2000])
        )


def cmd_bash(payload):
    command = cmd_of(payload)
    check_read_only(payload, command)
    check_merge_flags(command)
    check_status_label_integrity(command)
    check_hotfix_label_immutability(command)
    check_hotfix_creation(payload, command)
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
        ("ステータスラベル", check_status_label_integrity),
        ("hotfixラベル", check_hotfix_label_immutability),
        ("--no-verify 禁止", check_no_verify),
    ):
        try:
            check(command)
        except Denied as denied:
            print("判定: DENY [%s] %s" % (label, denied.reason))
            return 0

    # `cmd_bash` が呼ぶ残りのガードは payload(セッション状態・作業ツリー・計測結果)に
    # 依存し、コマンド文字列だけでは判定できない。判定に含まれていないことを明示する(#1183)。
    print("注記: 次のガードは payload に依存するため explain では判定していない:")
    print("      check_read_only(読み取り専用ステージの状態)")
    print("      check_commit_phase(ステージ済みファイル)")
    print("      check_pr_coverage(カバレッジ計測結果)")
    print("      check_hotfix_creation(read-only stage マーカーが report-bug かどうか)")

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
