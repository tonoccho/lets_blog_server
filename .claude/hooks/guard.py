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


# サブコマンド一致判定(invokes())の前に読み飛ばす、グローバルな永続フラグの集合。
# `glab`(cobra/pflag)は永続フラグをサブコマンドの前後どちらに置いても受け付けるため、
# `glab --repo owner/repo mr merge` のように値が独立したトークンになる形では、値が
# サブコマンドの位置にずれ込んで一致しなくなる(#1435)。読み飛ばしの根拠は「値を
# 取るから」ではない — 詳細と `-p` の位置依存性は下記(#1450)を参照。
#
# `=` 結合形(`--repo=owner/repo`)と短縮形の直結(`-Rowner/repo`)は1トークンで `-`
# から始まるため、素朴な `not a.startswith("-")` フィルタで元から正しく除外されている。
# ここで読み飛ばす必要があるのは「フラグ本体と値が別トークン」の場合だけ。
#
# ブール型フラグ(`-h`/`--help` など)は含めない。含めると `glab --help mr merge` の
# `mr` を値として消費してしまい、本物のサブコマンドを取りこぼす(見逃しは #1029 の
# 教訓に反する)。`strip_wrappers()` の `value_flags` と同じ「値を取ると分かっている
# フラグの明示的な集合」方式であり、推測(「`-` 始まりの次は常に値」など)はしない。
#
# `git` 呼び出し元(`check_no_verify` / `check_commit_phase`)とは値を取るフラグの
# 集合が異なる(`git` は `-C` / `--git-dir` / `-c` など)ため、program ごとのテーブルに
# しておく(#1440)。
#
# `--exec-path` は `=` 無しでは値を取らない(bare form は値を表示して終了するだけで、
# 次のトークンを消費しない)ので含めない。`--bare` / `--paginate` / `-P`(`--no-pager`)
# などブール型フラグも同様に含めない。
#
# ## `-p` を一致前は使い、一致後は使わない(#1450 レビュー1回目で確定)
#
# #1450 レビュー1回目は、`issue update` の `-p`(`--public`、ブール)が
# `GLOBAL_VALUE_FLAGS["glab"]` の想定と食い違うことへの対処として `-p` をこの
# 集合(または集合全体)から除外する実装を提出したが、差し戻された。この集合は
# `invokes()` の**サブコマンド一致が完了するまでの位置引数整列**にも使われており、
# そこで `-p` を除外すると逆方向の穴が開く: `glab -p 2 mr merge --rebase` のように
# `-p <値>` が**サブコマンドより前**に置かれると、値(`2`)が読み飛ばされずに
# 位置引数として数えられ、`positional[:len(subcommands)]` の前方一致が崩れて
# `invokes()` が**空リストを返す** — `check_merge_flags` 等の個々のチェック関数が
# 丸ごと呼ばれなくなる(#1450 レビュー1回目 BLOCKING)。
#
# `-p` の曖昧さは「値を取るかどうか」ではなく「**位置によって意味が変わる**」ことに
# 起因する。この読み飛ばしが必要なのは各エントリが「値を取るフラグだから」ではない
# — 根拠は glab(cobra)のコマンド探索が使う `stripFlags` が、**定義の有無や値の
# 要否に関わらず、`-` で始まる未知のトークンの次のトークンを一律に消費する**ことに
# ある。値がサブコマンドより前に着地するのはその副作用であり、「そのフラグが値を
# 取ると分かっているから読み飛ばす」のではなく「読み飛ばさないとサブコマンドの
# 位置がずれる」という理由で読み飛ばす。実機(glab 1.116.0)で確認:
#
#   $ glab -p   issue list   → Unknown command "list" for "glab".
#   $ glab -z   issue list   → Unknown command "list" for "glab".   ← 定義すら無いフラグ
#   $ glab --zz issue list   → Unknown command "list" for "glab".   ← 同上
#
# 3つとも同一の壊れ方であり、`-z`/`--zz` は `GLOBAL_VALUE_FLAGS` にも glab の実際の
# フラグ定義にも存在しない。次トークンを食うのは「値を取るフラグだから」ではなく
# 「未知フラグすべてで一律にそうなる」ことの証拠である。
#
# したがって「値を取る」という語彙でこの集合を正当化しない。以下は
# `GLOBAL_VALUE_FLAGS["glab"]` 8エントリそれぞれについて、実行したコマンドと rc の
# 実測(glab 1.116.0)。(a) `glab <flag> <値> version` の rc — root の永続フラグかどうか。
# (b) `glab <flag> <値> <対象4サブコマンドのいずれか> --help` の rc — 前置形が受理
# されるかどうか(`-p` を除く4つで同一の結果だったため1列にまとめた。`-p` は行に
# 併記)。
#
#   | エントリ     | (a) rc | (b) rc               | 備考                          |
#   | ------------ | ------ | --------------------- | ----------------------------- |
#   | `-R`         | 0      | 0                      | 4サブコマンドすべてに実在      |
#   | `--repo`     | 0      | 0                      | 4サブコマンドすべてに実在      |
#   | `--jq`       | 1      | 1                      | どれにも無い(Unknown flag)     |
#   | `-F`         | 1      | 1                      | 同上(Unknown shorthand flag)   |
#   | `--output`   | 1      | 1                      | 同上                           |
#   | `-p`         | 1      | 0(`issue update` のみ) | `issue update` のみに実在。ただしブール(`--public`) |
#   | `--page`     | 1      | 1                      | どれにも無い                   |
#   | `-P`         | 1      | 1                      | 同上                           |
#   | `--per-page` | 1      | 1                      | 同上                           |
#
# `-p` の (b) rc=0 は `issue update` が独自にブールの `-p`(`--public`)を定義して
# いるために起きるのであって、`-p` が値を取ることの証拠ではない。読み飛ばしの根拠は
# あくまで上記の `stripFlags` であり、この (a)(b) の表は根拠ではなく実測の記録に
# すぎない。
#
# **この8エントリの網羅性に安全性を依存させていない。** ここに載っていない値フラグ
# (`--sha` / `-m` / `--title` / `-l` / `-t` / `--due-date` など、各サブコマンドが
# 個別に定義する値フラグ)をサブコマンドより前に置いても同じ位置引数ずれは起きるが、
# それはこの集合を拡張しても閉じられない(cobra は未知フラグすべてで次トークンを
# 食うため、curated なリストは原理的に後追いにしかならない)。この先行欠陥は
# #1450 の範囲外であり、#1454(P1)として別に起票されている。
GLOBAL_VALUE_FLAGS = {
    "glab": {"-R", "--repo", "--jq", "-F", "--output", "-p", "--page", "-P", "--per-page"},
    "git": {"-C", "--git-dir", "--work-tree", "-c", "--namespace", "--config-env"},
}


def invokes(command, program, subcommands=()):
    """`program`(必要なら続く部分コマンド)を実行する箇所の残り引数を列挙する。

    解析できない場合は、生の文字列に対する緩い照合へフォールバックする。
    見逃す(#1029)よりは過検知に倒す。

    `subcommands` を渡した呼び出しは、サブコマンド一致に加えて `--help`/`-h` だけの
    呼び出しを除外する(#1446)。ヘルプ表示は副作用の無い読み取り専用の操作であり、
    サブコマンド一致で判定するどのガードにとっても「操作」ではないため。判定対象は
    サブコマンドに一致した分とグローバル値フラグの対を除いた残り(`_is_help_invocation`
    参照)であり、それ以外のトークン(他のフラグ・その値・追加の位置引数)が1つでも
    残っていれば非ヘルプとして通常判定に委ねる。
    `subcommands=()` で呼ぶ `check_status_label_integrity` と、
    `check_hotfix_label_immutability` 内の `glab api ... --method PUT` を拾うループは
    この判定を経由しない — 特定のサブコマンドへの一致を前提にしていないので、
    `--help` の有無を云々する対象でもない。

    ## `GLOBAL_VALUE_FLAGS` の読み飛ばしは、サブコマンド一致が完了するまでだけ(#1450)

    `-p` のように、サブコマンドの前後で扱いが変わるべきトークンがある。サブコマンドの
    前で `-p` は永続フラグではなく値も取らない(`GLOBAL_VALUE_FLAGS` 直前のコメントの
    実測表 (a) 参照 — `glab -p x/y version` は rc 1)。それでも一致前に読み飛ばす必要が
    あるのは、cobra のコマンド探索が使う `stripFlags` が未知フラグの次トークンを一律に
    消費するためである(同コメント参照)。一方サブコマンドの後で `-p` は `issue update`
    が定義するブールの `--public` であり、次のトークンを消費してはならない。単一の
    集合で「読み飛ばす/読み飛ばさない」を位置に関係なく決め打つと、どちらかの方向で
    `invokes()` が呼び出しを丸ごと見失う:

    - **一致完了前**に読み飛ばさない場合: 値(`glab -p 2 mr merge` の `2`)が位置引数
      として数えられ、`positional[:len(subcommands)]` の前方一致が崩れて
      `invokes()` が空リストを返す — 依存する全ガードが到達しなくなる(fail-open。
      #1450 レビュー1回目 BLOCKING)。
    - **一致完了後**に読み飛ばす場合: 値を取ると仮定したトークンの次が読み飛ばされて
      `residual` から消え、`_is_help_invocation` が誤ってヘルプと判定しうる
      (fail-open。#1450 元の欠陥)。

    そのため位置で使い分ける: **一致完了前(`matched < len(target)`)は
    `GLOBAL_VALUE_FLAGS[program]` をそのまま使って読み飛ばす**(位置引数整列に必要。
    根拠は cobra のコマンド探索時 `stripFlags` が未知フラグの次トークンを一律に
    消費すること — 上記コメントの表と同じ)。**一致完了後は一切読み飛ばさない**
    (curated なリストを引かない。`residual` に本物のトークンが残るので
    `_is_help_invocation` は安全側=非ヘルプに倒れる)。

    受け入れる代償: 一致後に置かれたグローバル値フラグと `--help`(または `-h`)の併記
    (`glab mr merge --repo o/r --help`)は、`--repo`/`o/r` が読み飛ばされず
    `residual` に残るため、ヘルプ呼び出しと判定されなくなる(→ 通常判定に委ねられ、
    このケースでは `--squash` 未指定として deny される)。この代償は `--repo` に限らず
    `GLOBAL_VALUE_FLAGS[program]` のどのエントリでも、また `--help` に限らず `-h` でも
    同様に生じる(例: `glab mr merge --jq .x --help` / `glab issue create --per-page 2
    --help` / `glab mr merge -R o/r -h` / `glab mr create --output json --help` は
    いずれも一致前なら許可されるヘルプ相当の形が一致後では通常判定に委ねられる)。
    向きはすべて無害な誤拒否(fail-closed)であり、#1449 が受け入れている代償と同種。
    一致**前**の同じ形(`glab --repo o/r mr merge --help`)はこれまでどおりヘルプ判定
    される。
    """
    parsed = simple_commands(command)
    if parsed is None:
        pattern = r"\b" + re.escape(program) + r"\b"
        if subcommands:
            pattern += r"\s+" + r"\s+".join(re.escape(s) for s in subcommands) + r"\b"
        return [command.split()] if re.search(pattern, command) else []

    value_flags = GLOBAL_VALUE_FLAGS.get(program, set())
    found = []
    for argv, _ in parsed:
        if not argv or os.path.basename(argv[0]) != program:
            continue
        rest = argv[1:]
        if subcommands:
            target = list(subcommands)
            positional = []
            residual = []
            matched = 0
            skip_next = False
            for a in rest:
                if skip_next:
                    skip_next = False
                    continue
                match_complete = matched >= len(target)
                if not match_complete and a in value_flags:
                    skip_next = True
                    continue
                if a.startswith("-"):
                    residual.append(a)
                    continue
                positional.append(a)
                if not match_complete and a == target[matched]:
                    matched += 1
                else:
                    residual.append(a)
            if positional[: len(subcommands)] != list(subcommands):
                continue
            if _is_help_invocation(residual):
                continue
        found.append(rest)
    return found


# `_has_flag()` の短縮フラグクラスタ判定で、値を取ることが分かっている短縮フラグの
# 集合。呼び出し文脈(プログラム + サブコマンド)ごとに異なるため、GLOBAL_VALUE_FLAGS
# (#1435/#1440)と同じ「program ごとのテーブル」の考え方でここも分ける(#1441)。
#
# `-Rowner/repo` や `-mメッセージ` のように短縮フラグへ値を直結できる形は1トークンで
# `-` から始まるため、素朴な「`-` から始まる各文字をブールフラグとみなす」走査では、
# 値の中の文字を無関係な別の短縮フラグと誤認する。値を取ると判明している短縮フラグに
# 出会った時点で、そのトークンの残りは値として扱いを打ち切る必要がある。
#
# `glab mr merge --help` で確認: `-m`(--message、コミットメッセージ)と
# `-R`(--repo。GLOBAL_VALUE_FLAGS の一部と同じ意味)が値を取る短縮フラグ。
# `git commit -h` で確認: 値を取る短縮フラグは次の8つ全部(#1445。#1441 時点では
# `m`/`F`/`c`/`C`/`S` の5つしか登録されておらず、`t`/`U`/`u` が漏れていた)。
#   -F <file>                 --file
#   -m <message>               --message
#   -c <commit>                --reedit-message
#   -C <commit>                --reuse-message
#   -t <file>                  --template
#   -S[<key-id>]               --gpg-sign(直結のみ値を取る)
#   -U <n>                     --unified
#   -u[<mode>]                 --untracked-files(直結のみ値を取る)
# 将来 git の版でこの一覧が増減したら、`git commit -h` を取り直してこの集合を
# 更新すること。
#
# `("glab", "issue update")` / `("glab", "issue create")` のエントリは #1446 以前に
# `_is_help_invocation` の誤認防止用として置かれていたが、`_has_flag`/`_cluster_has_flag`
# はどちらの呼び出し元でも使われていない(ラベルの抽出は `_issue_update_label_args` /
# `_short_flag_attached_value` が別に行う)。`_is_help_invocation` は #1446 のレビュー
# 対応で完全一致方式に切り替わり、この一覧を引かなくなった(下記参照)。使われなくなった
# エントリを残すと「curated なリストを安全側の判断に使ってよい」という誤った前例に
# なるため削除する。
SHORT_VALUE_FLAGS = {
    ("glab", "mr merge"): {"m", "R"},
    ("git", "commit"): {"m", "F", "c", "C", "S", "t", "U", "u"},
}


def _cluster_has_flag(cluster, short, value_shorts):
    """短縮フラグクラスタ(`-` を除いた残り)の中に、ブールフラグ `short` があるか。

    値を取ることが分かっている短縮フラグ(`value_shorts`)に出会ったら、それ以降は
    直結された値とみなして走査を打ち切る(`-Rowner/repo` の `owner/repo` を
    フラグの並びとして読まない)。本物のブールクラスタ(`-sd`)は、含まれる文字が
    どれも値を取らないため最後まで走査され、目的の文字が見つかる。
    """
    for ch in cluster:
        if ch == short:
            return True
        if ch in value_shorts:
            return False
    return False


def _is_help_invocation(residual):
    """`residual`(サブコマンドに一致した分と、グローバル値フラグの対を `invokes()` が
    既に取り除いた残り)が、`--help`/`-h` だけのヘルプ表示呼び出しか。

    判定: `residual` が空でなく、かつ**すべての**トークンが `--help` または独立した
    トークンとしての `-h` であるときだけ真。1つでも他のトークン(他のフラグ、その値、
    位置引数)が残っていれば偽。

    ## 経緯(#1446、レビュー2回まで)

    1回目までの実装(クラスタ内の文字一致 → 完全一致)はどちらも「`residual` の中に
    `--help`/`-h` が**1つでも**含まれていれば真」という OR 方式だった。これは、値を
    取るフラグの値としてたまたま独立トークンの `-h` が渡された形を、本物の `-h` と
    区別できない: `git commit -m -h --no-verify` の `-h` は `-m` の値、
    `glab issue update ... --title -h` の `-h` は `--title` の値であり、どちらも
    ヘルプ表示ではなく通常の操作(かつ危険なフラグを伴う)である。`residual` には
    `-m`/`--title` 自身も残っているのに、OR 方式はそれらを無視して `-h` の存在だけで
    真と判定し、後続の危険フラグ検査(`--no-verify` 禁止、hotfix 不変性)ごと
    スキップしてしまっていた(fail-open。レビュー2回目 BLOCKING の指摘)。

    「すべてのトークンが help」という AND 方式にすることで、`-h`/`--help` 以外の
    トークンが1つでも残っていれば非ヘルプと判定するようになり、上記の fail-open を
    防ぐ。

    副作用として、位置引数や他のフラグが `--help`/`-h` と共存する呼び出し
    (`glab mr merge 42 --help` の `42`、`-h --rebase` の `--rebase`)は、実際の cobra
    ではヘルプだけを表示し `42` のマージや `--rebase` の適用は起きないはずだが、この
    実装では非ヘルプと判定し通常判定(deny/発火)に倒す。これは意図的に受け入れる
    誤拒否(fail-closed)であり、値を取る短縮フラグを curated に列挙したリスト
    (旧 `SHORT_VALUE_FLAGS` 依存方式)の網羅性に頼る設計へ戻らないためのトレードオフ
    として、呼び出し元(2026-09-27 レビュー2回目)が明示的に受け入れた。fail-open では
    なく実害はない。
    """
    return bool(residual) and all(tok in ("--help", "-h") for tok in residual)


# マージ方式のフラグ。長いフラグと、cobra が受け付ける短縮フラグの結合(`-sd`)の両方。
# `--squash-message` はコミットメッセージの指定であって方式の指定ではないので、
# `--squash` の前方一致で拾ってはいけない。`-R`(--repo)は `-r`(--rebase)ではない。
def _has_flag(args, long_name, short, value_shorts=frozenset()):
    for arg in args:
        if arg == long_name or arg.startswith(long_name + "="):
            return True
        if arg.startswith("--"):
            continue
        if arg.startswith("-") and len(arg) > 1 and _cluster_has_flag(arg[1:], short, value_shorts):
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

    `--squash` の明示を要求する理由と、プロジェクト設定だけに頼らない根拠は CLAUDE.md →
    Enforcement → Where squash is enforced が単一の定義であり、ここはそれを判定として
    符号化しているだけ。二層構造の根拠・網羅性の検証は CLAUDE.md 側に書く。ここに
    理由を再掲しない。

    `gh pr merge` は方式を指定しないと対話的に尋ねる仕様だったため、旧実装は
    `--merge` / `--rebase` を明示したときだけ拒否すれば足りていた。GitLab はここが
    異なり、判定は「禁止フラグの検出」ではなく「squash 指定の要求」でなければならない。

    `--admin` に相当する管理者バイパスは GitLab には無い。保護ブランチの回避は
    フックではなく GitLab 側の権限設定で防ぐ(CLAUDE.md → Merge Conflicts)。
    """
    value_shorts = SHORT_VALUE_FLAGS[("glab", "mr merge")]
    for args in invokes(command, "glab", ("mr", "merge")):
        if _has_flag(args, "--rebase", "r", value_shorts):
            emit_deny(
                "このリポジトリの Issue MR のマージ方式は squash のみです"
                "(CLAUDE.md → Completion Definition)。`--rebase` は使えません。"
                "`glab mr merge --squash --remove-source-branch` を使ってください。"
            )
        if not _has_flag(args, "--squash", "s", value_shorts):
            emit_deny(
                "`glab mr merge` にマージ方式が指定されていません。設定は変わりうる"
                "ため、プロジェクト設定だけに頼らず常に `--squash` を明示してください"
                "(CLAUDE.md → Enforcement → Where squash is enforced)。"
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


def check_issue_creation_requires_status(command):
    """CLAUDE.md → How to change status: 作成にも `status::` がちょうど1つ必要(#1444)。

    `check_status_label_integrity` は `glab issue create` を遷移ではないとして素通り
    させている(759-760行) — それ自体は正しい。作成は遷移ではなく、最初のステータスは
    そこから来る。しかし「作成に `status::` が含まれているか」を確認する仕組みが
    予防層のどこにも無く、#1441 は `status::` を1つも持たずに起票され、約10時間
    ボードのどの列にも現れなかった。

    `check_hotfix_creation`(#1434)の Readiness Report が確立した方針どおり、
    `check_status_label_integrity` を拡張せず**独立した新規関数**として追加する —
    あちらは `status::` 専用の遷移表と密結合しており、ここで見ている壊れ方
    (作成時の欠落・重複)とは無関係。

    ラベル値の抽出は `_issue_update_label_args`(#1433)を再利用する。`glab issue create`
    の `--label`/`-l` は `glab issue update` と同じ pflag の記法(空白区切り、`--label=`、
    `-l` の値直結形、カンマ併記、複数回指定)を受け付けるため、専用の解析を新たに書かない。
    """
    for args in invokes(command, "glab", ("issue", "create")):
        added, _ = _issue_update_label_args(args)
        statuses = [label for label in added if label.startswith("status::")]
        if not statuses:
            emit_deny(
                "`status::` ラベルを持たない Issue は作成できません(CLAUDE.md → How to "
                "change status)。`--label` に `status::Inbox` 等を含めてください。"
            )
        if len(statuses) > 1:
            emit_deny(
                "`status::` ラベルが複数指定されています(%s)(CLAUDE.md → How to change "
                "status)。ちょうど1つにしてください。" % ", ".join(statuses)
            )


def check_no_verify(command):
    for sub in ("commit", "push"):
        for args in invokes(command, "git", (sub,)):
            if "--no-verify" in args or (sub == "commit" and "-n" in args):
                emit_deny(
                    "`--no-verify` は禁止です。git フックはこのリポジトリのフェーズ分離"
                    "(CLAUDE.md → Test-First Implementation)を強制するためのものです。"
                )


def _leading_global_git_flags(args):
    """`args`(`git` の後続トークン列)のうち、最初の positional トークン(サブコマンド)

    より前のグローバルフラグ列を返す(#1443)。`invokes()` がサブコマンド検出に使う
    のと全く同じ規則(`GLOBAL_VALUE_FLAGS["git"]` にあるフラグは次のトークンも値として
    読み飛ばす)で走査するので、`invokes()` が返す `args` の先頭が確実にこの境界に
    一致する。
    """
    value_flags = GLOBAL_VALUE_FLAGS.get("git", set())
    i = 0
    skip_next = False
    while i < len(args):
        a = args[i]
        if skip_next:
            skip_next = False
            i += 1
            continue
        if a in value_flags:
            skip_next = True
            i += 1
            continue
        if not a.startswith("-"):
            break
        i += 1
    return args[:i]


def check_commit_phase(payload, command):
    commits = invokes(command, "git", ("commit",))
    if not commits:
        return
    root = project_dir(payload)
    commit_value_shorts = SHORT_VALUE_FLAGS[("git", "commit")]
    for args in commits:
        # `-C`/`--git-dir`/`--work-tree` は自前で値を解釈して root を組み立てず、
        # 呼び出し側が書いたとおりの形で `git` 自身に渡す(#1443)。こうすることで
        # 相対パスの解決(cwd 基準)・複数の `-C` の累積・`--git-dir` と
        # `--work-tree` の優先順位を、すべて git 自身の実装に委ねられる。
        prefix = _leading_global_git_flags(args)
        staged = git(prefix + ["diff", "--cached", "--name-only"], root)
        if staged is None:
            continue
        files = [p for p in staged.splitlines() if p.strip()]
        if _has_flag(args, "--all", "a", commit_value_shorts):
            tracked = git(prefix + ["diff", "--name-only"], root) or ""
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
    check_issue_creation_requires_status(command)
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
        ("Issue作成のstatus::必須", check_issue_creation_requires_status),
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
