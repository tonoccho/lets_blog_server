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

READ_ONLY_SKILLS = {"discover-issues", "triage-backlog", "ready-issue", "report-bug", "close-epic", "close-issue"}

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


def slash_command_skill(payload):
    """プロンプトが `/<skill>` で始まり、`<skill>` が READ_ONLY_SKILLS の要素なら、その要素を返す。

    スラッシュコマンド起動では `Skill` ツールが呼ばれず `cmd_stage` が動かない(#1469)。
    返すのは常に READ_ONLY_SKILLS の要素そのもので、プロンプトの文字列は書かない。
    """
    prompt = payload.get("prompt")
    if not isinstance(prompt, str):
        return None
    match = re.match(r"/([A-Za-z0-9_-]+)(?:\s|$)", prompt)
    if match and match.group(1) in READ_ONLY_SKILLS:
        return match.group(1)
    return None


def cmd_prompt(payload):
    """`UserPromptSubmit`: 従来どおりマーカーを消し、読み取り専用スキルのスラッシュコマンドなら立て直す。"""
    skill = slash_command_skill(payload)
    if skill:
        path = marker_path(payload)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(skill)
        sys.exit(0)
    cmd_clear(payload)


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
#   name: (値を取るフラグの集合, フラグ以外の引数をいくつ読み飛ばすか)
#
# ## 実機での洗い出し(#1462 Requirement 1・2)
#
# 以前は `WRAPPERS["env"]` が空集合のままで、`env -u FOO glab mr merge --rebase` /
# `env -C /tmp glab mr merge --rebase` のように、値取りフラグの**値**が読み飛ばされず
# 実体コマンドの検出に混入していた(`-u FOO` の `FOO` が誤って先頭トークン扱いになり、
# `glab` との一致が崩れる)。それぞれ実機で実際に動く形である
# (`env -u FOO git --version` / `env -C /tmp git --version` はどちらも rc 0)。
#
# 以下は、このマシンに実際にインストールされている各ラッパーの `--help`(または
# `--version`)を実行して確認した、値を取るフラグの網羅である。ブールフラグ
# (値を取らない)は `WRAPPER_BOOL_FLAGS` 側に分けて持つ — Requirement 4 の
# fail-closed 判定(`_unknown_wrapper_flags` / `check_unknown_wrapper_flag`)が、
# 「値フラグでもブールフラグでもないフラグ」を未知とみなすために両方必要になる。
#
# ### env(`env --version`: uutils coreutils 0.8.0。`env --help` で確認)
#
#   値を取る:   -C/--chdir <DIR>, -u/--unset <NAME>, -f/--file <PATH>,
#               -S/--split-string <S>, -a/--argv0 <a>
#   ブール:     -i/--ignore-environment, -0/--null, -v/--debug,
#               --ignore-signal[=SIG], --default-signal[=SIG],
#               --block-signal[=SIG], --list-signal-handling
#               (`[=SIG]` は `=` 付きでのみ値を持つ形なので、素の形は次の
#               トークンを消費しない。`=` 付きの形はどのラッパーでも下記の
#               ループが自己完結として扱う。#1449 と同種)
#
#   **`env -S'...'`(文字列を分割して実行)は間接実行なので、その内容の解析は
#   ここでは行わない(CLAUDE.md → Enforcement → What the guards are, and are not。
#   Requirement 3)。ここで扱うのは `-S` の次のトークンを値として読み飛ばすことだけ。**
#
# ### timeout(uutils coreutils 0.8.0。`timeout --help` で確認。
#   旧集合は実機の全フラグと一致していたため変更なし)
#
#   値を取る:   -s/--signal <SIGNAL>, -k/--kill-after <DURATION>
#   ブール:     -f/--foreground, -p/--preserve-status, -v/--verbose
#
# ### nice(uutils coreutils 0.8.0。`nice --help` で確認。変更なし)
#
#   値を取る:   -n/--adjustment <N>
#
# ### ionice(util-linux 2.41.3。`ionice --help` で確認。
#   旧集合は短縮形の一部(`-c`/`-n`/`-p`)だけで、長い形と `-P`/`-u` が漏れていた)
#
#   値を取る:   -c/--class <class>, -n/--classdata <num>, -p/--pid <pid>,
#               -P/--pgid <pgrp>, -u/--uid <uid>
#   ブール:     -t/--ignore
#
# ### sudo(sudo-rs 0.2.13-0ubuntu1.2。`sudo --help` で確認。
#   旧集合は `-u`/`-g`/`-p` 系のみで、`-D`/`--chdir`・`-U`/`--other-user` が漏れていた)
#
#   値を取る:   -u/--user <user>, -g/--group <group>, -p/--prompt <prompt>,
#               -D/--chdir <directory>, -U/--other-user <user>
#   ブール:     -A/--askpass, -b/--background, -B/--bell, -e/--edit, -i/--login,
#               -K/--remove-timestamp, -k/--reset-timestamp, -l/--list,
#               -n/--non-interactive, -S/--stdin, -s/--shell, -v/--validate
#               (`--preserve-env=list` は `=` 付きの形しかドキュメントに無いため、
#               素の `--preserve-env` は未知フラグとして fail-closed の対象にする)
#
# ### time(GNU Time、`/usr/bin/time --help` で確認。bash 組み込みの `time`
#   キーワードとは別物 — シェル組み込みには `--version`/`--help` が無い。
#   旧集合は空集合で、`-f`/`--format`・`-o`/`--output` が漏れていた)
#
#   値を取る:   -f/--format <FORMAT>, -o/--output <FILE>
#   ブール:     -a/--append, -p/--portability, -q/--quiet, -v/--verbose
#
# ### command(bash 組み込み。`help command` で確認。値フラグは無い。変更なし)
#
#   ブール:     -p, -V, -v
#
# ### nohup(uutils coreutils 0.8.0。`nohup --help` で確認。
#   `--help`/`--version` 以外にフラグは無い。変更なし)
#
# ### stdbuf(uutils coreutils 0.8.0。`stdbuf --help` で確認。
#   旧集合は短縮形のみで、長い形が漏れていた)
#
#   値を取る:   -i/--input <MODE>, -o/--output <MODE>, -e/--error <MODE>
#
# ### xargs(GNU findutils 4.10.0。`xargs --help` で確認。
#   旧集合は短縮形の一部のみで、`-L`/`--max-lines` が漏れており、長い形も無かった。
#   `--help`/`--version` に短縮形(`-h`/`-V`)は無い)
#
#   値を取る:   -a/--arg-file <FILE>, -d/--delimiter <CHARACTER>, -E <END>
#               (`-e`/`--eof` とは別物), -I <R>(空白区切りで必須),
#               -L/--max-lines <MAX-LINES>, -n/--max-args <MAX-ARGS>,
#               -P/--max-procs <MAX-PROCS>, -s/--max-chars <MAX-CHARS>
#   ブール:     -0/--null, -r/--no-run-if-empty, -t/--verbose, -x/--exit,
#               -p/--interactive, -o/--open-tty, --show-limits
#               (`-e`/`--eof[=END]`・`-i`/`--replace[=R]`・`-l[MAX-LINES]` は
#               角括弧内、つまり省略可能な形でしか値を示していない。素の形は
#               次のトークンを消費しないのでブール扱いにする。`--process-slot-var=VAR`
#               も `=` 付きの形しかドキュメントに無く、sudo の `--preserve-env` と
#               同様に素の形は未知フラグ扱いにする)
#
# ### setsid(util-linux 2.41.3。`setsid --help` で確認。
#   値フラグは無い。変更なし)
#
#   ブール:     -c/--ctty, -f/--fork, -w/--wait
WRAPPERS = {
    "timeout": ({"-s", "--signal", "-k", "--kill-after"}, 1),  # 継続時間を1つ取る
    "env": ({"-C", "--chdir", "-u", "--unset", "-f", "--file",
             "-S", "--split-string", "-a", "--argv0"}, 0),
    "nice": ({"-n", "--adjustment"}, 0),
    "ionice": ({"-c", "--class", "-n", "--classdata", "-p", "--pid",
                "-P", "--pgid", "-u", "--uid"}, 0),
    "sudo": ({"-u", "--user", "-g", "--group", "-p", "--prompt",
              "-D", "--chdir", "-U", "--other-user"}, 0),
    "time": ({"-f", "--format", "-o", "--output"}, 0),
    "command": (set(), 0),
    "nohup": (set(), 0),
    "stdbuf": ({"-i", "--input", "-o", "--output", "-e", "--error"}, 0),
    "xargs": ({"-I", "-n", "--max-args", "-P", "--max-procs", "-d", "--delimiter",
               "-a", "--arg-file", "-E", "-s", "--max-chars", "-L", "--max-lines"}, 0),
    "setsid": (set(), 0),
}

# `-h`/`--help`/`-V`/`--version` はどのラッパーにも共通のブールフラグなので、
# 各エントリの固有フラグとは別に持つ。一部のラッパー(`xargs` の短縮形、`command`
# の `--help`/`--version` など)には実在しない組み合わせも含むが、値を取らないと
# いう性質は変わらないので、Requirement 4 の判定を過検知させることはない。
COMMON_BOOL_FLAGS = {"-h", "--help", "-V", "--version"}

# 値を取らないと確認した(=ブール)フラグ。`WRAPPERS`(値取り)にも `COMMON_BOOL_FLAGS`
# にも無いフラグに出会ったら「未知」と判定し、fail-closed で拒否する
# (Requirement 4。`_unknown_wrapper_flags` / `check_unknown_wrapper_flag` 参照)。
# 各エントリの根拠は上の `WRAPPERS` のコメントに実行したコマンドとともに記載している。
WRAPPER_BOOL_FLAGS = {
    "timeout": {"-f", "--foreground", "-p", "--preserve-status", "-v", "--verbose"},
    "env": {"-i", "--ignore-environment", "-0", "--null", "-v", "--debug",
            "--ignore-signal", "--default-signal", "--block-signal",
            "--list-signal-handling"},
    "nice": set(),
    "ionice": {"-t", "--ignore"},
    "sudo": {"-A", "--askpass", "-b", "--background", "-B", "--bell", "-e", "--edit",
             "-i", "--login", "-K", "--remove-timestamp", "-k", "--reset-timestamp",
             "-l", "--list", "-n", "--non-interactive", "-S", "--stdin", "-s", "--shell",
             "-v", "--validate"},
    "time": {"-a", "--append", "-p", "--portability", "-q", "--quiet",
             "-v", "--verbose"},
    "command": {"-p", "-V", "-v"},
    "nohup": set(),
    "stdbuf": set(),
    "xargs": {"-0", "--null", "-e", "--eof", "-i", "--replace", "-l", "-r",
              "--no-run-if-empty", "-t", "--verbose", "-x", "--exit", "-p",
              "--interactive", "-o", "--open-tty", "--show-limits"},
    "setsid": {"-c", "--ctty", "-f", "--fork", "-w", "--wait"},
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


# 引用符つき文字列(そのまま残す)か、語頭に置かれリダイレクト演算子に**接した**数字。
# シェルは `2>x` の `2` を fd として読み、`2 >x` の `2` は引数として読む。
_FD_NUMBER = re.compile(
    r"""('[^']*'|"(?:\\.|[^"\\])*"|\\.)|(?<![^\s;&|(])\d+(?=[<>])"""
)


def strip_fd_numbers(command):
    """リダイレクトの fd 番号(`2>&1` の `2`)を取り除く(#1455)。

    shlex は空白の有無を捨てるため、トークン化の後では `2>&1` と `2 > x` を区別できない。
    引数として数えられた `2` は `invokes()` の前方一致と `--help` 判定を崩す。
    """
    return _FD_NUMBER.sub(lambda m: m.group(1) or "", command)


PROCESS_SUBSTITUTION_OPENERS = {"<(", ">("}


def split_glued_separator(token):
    """`;>` `&&>` `|>` のように区切りとリダイレクトが結合したトークンを分ける(#1524)。

    shlex の punctuation_chars は空白なしで連続する句読点を1トークンにするため、
    `echo a;>x` は `;>` になり、区切りでもリダイレクトでもなく素通りしていた。
    区切り+リダイレクトの組に分解できるときだけ分け、最長の区切りを優先する
    (`&&>` は `&&` + `>`)。それ以外のトークンは変えない。
    """
    if token in SEPARATORS or token in REDIRECTS:
        return [token]
    for cut in range(len(token) - 1, 0, -1):
        head, tail = token[:cut], token[cut:]
        if head in SEPARATORS and tail in REDIRECTS:
            return [head, tail]
    return [token]


def separate_unquoted_newlines(command):
    """クォートの外にある改行を ` ; ` に置き換える(#1667)。

    shlex は改行を空白として扱うため、そのままでは2行目のコマンドが1行目の
    引数になり、argv[0] で判定する検査をすり抜ける。bash と同じく、クォート外の
    改行は `;` と同じ区切りとして扱う。シングル/ダブルクォートの中の改行と、
    行末の `\\` + 改行(行の継続)はそのまま残す。クォートが閉じていない場合も
    そのまま返し、後段の shlex が ValueError で None へ倒す。
    """
    if "\n" not in command:
        return command
    out = []
    quote = None
    i = 0
    n = len(command)
    while i < n:
        c = command[i]
        if quote == "'":
            if c == "'":
                quote = None
        elif quote == '"':
            if c == "\\" and i + 1 < n:
                out.append(c)
                i += 1
                c = command[i]
            elif c == '"':
                quote = None
        elif c == "\\" and i + 1 < n:
            out.append(c)
            i += 1
            c = command[i]
        elif c in "'\"":
            quote = c
        elif c == "\n":
            out.append(" ; ")
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_word_initial_comments(command):
    """クォートの外で語の先頭にある `#` から行末までを取り除く(#1668)。

    shlex の既定(commenters='#')は語の途中の `#`(`a#b`、URL のフラグメント)も
    コメントとして行末まで捨て、同じ行の後ろのコマンドの検査をすり抜けた。bash では
    `#` が語の先頭(文字列の先頭、空白・改行・`;` `|` `&` `(` の直後)にあるときだけ
    コメントなので、その規則で自前に取り除き、shlex 側の commenters は空にする。
    改行そのものは残す(後段が区切りとして扱う)。
    """
    if "#" not in command:
        return command
    out = []
    quote = None
    # 直前の文字が、エスケープされていない区切り(空白・改行・`;` `|` `&` `(`)か
    # 文字列の先頭か。`a\\ #x` の `\\ ` は区切りではなく語の一部。
    word_start = True
    i = 0
    n = len(command)
    while i < n:
        c = command[i]
        was_start = word_start
        word_start = False
        if quote == "'":
            if c == "'":
                quote = None
        elif quote == '"':
            if c == "\\" and i + 1 < n:
                out.append(c)
                i += 1
                c = command[i]
            elif c == '"':
                quote = None
        elif c == "\\" and i + 1 < n:
            out.append(c)
            i += 1
            c = command[i]
        elif c in "'\"":
            quote = c
        elif c == "#" and was_start:
            while i < n and command[i] != "\n":
                i += 1
            continue
        else:
            word_start = quote is None and c in " \t\n;|&("
        out.append(c)
        i += 1
    return "".join(out)


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
    lexer = shlex.shlex(strip_fd_numbers(
        separate_unquoted_newlines(
            strip_word_initial_comments(strip_heredoc_bodies(command)))),
                        posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    lexer.commenters = ""
    try:
        tokens = [t for raw in lexer for t in split_glued_separator(raw)]
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
    """環境変数代入と既知のラッパーを剥がし、(実体のargv, 剥がしたもの, 未知フラグ) を返す。

    3つ目の要素は、既知のラッパーの直後で「値を取るかどうか判定できない」フラグに
    出会った場合の `(ラッパー名, フラグ)`。無ければ `None`(Requirement 4、#1462)。
    `WRAPPERS`/`WRAPPER_BOOL_FLAGS` の網羅性そのものに安全性を依存させないための
    合図であり、`check_unknown_wrapper_flag` がこれを見て fail-closed に拒否する。

    実体argv の組み立て自体はこれまでと変えない — 未知フラグも従来どおり値を取らない
    ものとして扱い(0個読み飛ばす)、既存の呼び出し元(各 `check_*` 関数)の挙動を
    変えない。安全側の拒否は `check_unknown_wrapper_flag` という別のガードが担う。
    """
    stripped = []
    unknown = None
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
        bool_flags = WRAPPER_BOOL_FLAGS.get(name, set()) | COMMON_BOOL_FLAGS
        stripped.append(argv.pop(0))
        # ラッパー自身のフラグを読み飛ばす。値を取るフラグは次のトークンも。
        while argv and argv[0].startswith("-") and argv[0] != "--":
            flag = argv.pop(0)
            base = flag.split("=", 1)[0]
            if "=" in flag:
                # 値直結(`--chdir=/tmp` 形)。既知未知を問わず1トークンで完結する
                # ので安全(#1449と同種)。
                continue
            # POSIX短縮直結形(`-C/tmp`, `-uFOO` = `-C /tmp` / `-u FOO` を1トークン
            # に詰めた形)。値がすでに同じトークンに埋め込まれているので、次の
            # トークンを消費してはいけない — `=` 付き直結形とは別チェックが要る
            # (#1462 レビュー2回目)。短い方の2文字(`-` + 1文字)が value_flags の
            # 短縮形と一致し、かつそれより長いトークンである場合に限る。
            short = flag[:2]
            if len(flag) > 2 and not flag.startswith("--") and short in value_flags:
                continue
            if base in value_flags:
                if argv:
                    argv.pop(0)
                continue
            if base in bool_flags:
                continue
            if unknown is None:
                unknown = (name, flag)
        if argv and argv[0] == "--":
            argv.pop(0)
        for _ in range(positionals):
            if argv:
                stripped.append(argv.pop(0))
    return argv, stripped, unknown


def simple_commands(command):
    """ラッパーを剥がした後の argv の並びを返す。解析不能なら None。"""
    parsed = split_commands(command)
    if parsed is None:
        return None
    out = []
    for argv, redirects in parsed:
        real, _stripped, _unknown = strip_wrappers(argv)
        out.append((real, redirects))
    return out


def _unknown_wrapper_flags(command):
    """既知のラッパーに続く、値を取るかどうか判定できないフラグを列挙する(Requirement 4)。

    解析不能なコマンドは(生文字列へのフォールバックの対象であって、ここでは)
    空リストを返す — `invokes()` 側のフォールバックが別途、見逃しより過検知に倒す。
    """
    parsed = split_commands(command)
    if parsed is None:
        return []
    found = []
    for argv, _redirects in parsed:
        _real, _stripped, unknown = strip_wrappers(argv)
        if unknown:
            found.append(unknown)
    return found


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
# 個別に定義する値フラグ)をサブコマンドより前に置いても同じ位置引数ずれが起き、
# それはこの集合を拡張しても閉じられない(cobra は未知フラグすべてで次トークンを
# 食うため、curated なリストは原理的に後追いにしかならない)。#1454 でこの依存を
# 断ち切った: `invokes()` の**検出**(`_subcommand_match_position()`)はこの集合を
# 一切引かない。ここに残っているのは、一致より前のトークンを `residual`(ヘルプ判定
# 対象)から取り除くためだけであり(#1450 の規則、`invokes()` のコメント参照)、
# **この集合が空でも検出そのものは崩れない**(#1454 AC2)。
GLOBAL_VALUE_FLAGS = {
    "glab": {"-R", "--repo", "--jq", "-F", "--output", "-p", "--page", "-P", "--per-page"},
    "git": {"-C", "--git-dir", "--work-tree", "-c", "--namespace", "--config-env"},
}


def _is_attached_global_value_flag(arg, value_flags):
    """`arg` が `value_flags` のグローバル値フラグの値直結形(`-Ro/r` / `-R=o/r` /
    `--repo=o/r`)なら True(#1449)。値は同じトークンの中にあるので、次トークンは
    読み飛ばさない。`residual` 組み立て(`match_end` より前)専用で、検出には使わない。
    """
    for flag in value_flags:
        if flag.startswith("--"):
            if arg.startswith(flag + "="):
                return True
        elif len(flag) == 2 and _short_flag_attached_value(arg, flag[1]) is not None:
            return True
    return False


def _subcommand_match_position(rest, subcommands):
    """`rest` の中で `subcommands` と完全一致する連続トークン列が現れる最初の位置を
    返す。無ければ `None`。これが3候補のうちの **`p_raw`**(#1454)。

    フラグか値かの区別を一切しない、生のトークン列そのものに対する連続一致。
    探索は最初の裸の `--` で打ち切る。`glab -- mr list` は root のヘルプを表示する
    だけで、`git -- status` は `unknown option: --` になり、どちらも実際には
    サブコマンドを実行しない(実測)ので、打ち切りは無害な誤拒否だけを生む。

    ## この関数**単独**では閉じない理由(#1454 レビュー1回目 BLOCKING1)

    サブコマンド2語の**あいだ**に別のトークンが挟まると、この関数は連続性が
    崩れて `None` を返す(`glab mr -R o/r merge` は `("mr","merge")` が連続しない)。
    `-R o/r` は #1435/#1440 が閉じたはずの `GLOBAL_VALUE_FLAGS` の一員であり、
    実行可能な形(実測: `glab mr -R gitlab-org/cli merge --help` は rc 0)である
    にもかかわらず検出を失う。そのため `invokes()` はこの関数(`p_raw`)を
    `_subcommand_match_position_prev`(`p_prev`)/`_subcommand_match_position_proj`
    (`p_proj`)との **OR** として使う。この関数はそれでも必要: `p_prev`/`p_proj`
    がどちらも見逃す形(例: `git -z 1 --no-advice commit --no-verify` のように
    `p_proj` がブール長形の次を誤って落とす場合)を `p_raw` が拾う。

    ## 受け入れる代償(#1454 本文で明示的に受け入れ済み。3候補共通)

    - **値の中身の誤検出**: `glab issue create -t mr merge` のように、`-t` の値の
      つもりで書いた `mr merge` が別トークンで連続していると、`("mr", "merge")` の
      検索に一致してしまう。クォートされた `--title "mr merge"` は `split_commands()`
      が1トークンとして扱うため一致しない。単一トークンのサブコマンド
      (`("commit",)` / `("push",)`)では `git log --oneline commit` のような
      リビジョン名が一致しうる。
    - 向きはすべて **fail-closed(無害な誤拒否)** であり、#1446 の「位置引数が残る
      ヘルプ呼び出しの誤拒否」、#1449 の「値直結形の誤拒否」と同種のトレードオフ。
    """
    if not subcommands:
        return None
    target = list(subcommands)
    n = len(target)
    limit = _rest_limit_before_bare_dashdash(rest)
    for i in range(max(0, limit - n + 1)):
        if rest[i : i + n] == target:
            return i
    return None


def _leading_flag_projected_tokens(rest, limit):
    """`rest[:limit]` を cobra の `stripFlags` と同じ形で射影した
    `(生 index, トークン)` の列を返す(`p_proj` の下請け、#1454)。

    `-` で始まる各トークンは射影から落とす。さらに、そのトークンが
    **「ちょうど2文字の短縮形(`-x`)」または「`--` で始まる長形」であり、
    かつ `=` を含まない場合に限って**、直後のトークンも落とす —
    cobra のコマンド探索 `stripFlags` が未知フラグの次トークンを一律に消費する
    のと同じ形。3文字以上の短縮形(`-Ro/r`)・短縮クラスタ(`-sd`)・`=` 結合形
    (`--repo=o/r`)は、cobra がその次を消費しない(実測)ため、ここでも次を
    落とさない — 一律に落とすと `glab mr --repo=o/r merge --rebase` /
    `glab mr -Ro/r merge --rebase` という**実行される**形(cobra は次を消費せず
    そのまま `merge --rebase` に到達する)を検出から逃してしまう
    (#1454 Readiness 再評価で判明した反例)。
    """
    projected = []
    skip_next = False
    idx = 0
    while idx < limit:
        a = rest[idx]
        if skip_next:
            skip_next = False
            idx += 1
            continue
        if a.startswith("-"):
            is_short = len(a) == 2 and not a.startswith("--")
            is_long = a.startswith("--") and "=" not in a
            if (is_short or is_long) and idx + 1 < limit:
                skip_next = True
            idx += 1
            continue
        projected.append((idx, a))
        idx += 1
    return projected


def _rest_limit_before_bare_dashdash(rest):
    """`rest` のうち、最初の裸の `--` より前の範囲の長さを返す(無ければ全長)。

    `p_raw`/`p_proj` の探索はこの範囲だけを見る(#1454 Requirement 1)。
    """
    for idx, a in enumerate(rest):
        if a == "--":
            return idx
    return len(rest)


def _subcommand_match_position_proj(rest, subcommands):
    """`p_proj`(#1454): 射影列(`_leading_flag_projected_tokens`)上で
    `subcommands` と連続一致する最初の位置を、生のトークン列の index 列として
    返す。無ければ `None`。curated な `GLOBAL_VALUE_FLAGS` を一切引かない —
    このリストに載っていない値フラグ(`--attr-source` 等)や、定義すら無い
    任意のフラグ(`-z`/`--zz`)がサブコマンドの**あいだ**に置かれても、cobra が
    実際に次を消費する形とだけ一致するように振る舞う。

    探索は最初の裸の `--` で打ち切る(規則は `_rest_limit_before_bare_dashdash()`
    の1箇所、#1466)。この打ち切りが**実際に効く**のは次の形で、打ち切りを外すと
    allow から DENY(fail-closed 方向)に動く:

        glab -- x mr merge --rebase

    `--` の直後が `mr` のとき(`-z 1` 前置など)は、打ち切りを外しても許可のまま
    である(`--` 自身が長形フラグとして次の `mr` を食うため)。その形はこの打ち切りの
    根拠にならない。
    """
    if not subcommands:
        return None
    target = list(subcommands)
    n = len(target)
    limit = _rest_limit_before_bare_dashdash(rest)
    projected = _leading_flag_projected_tokens(rest, limit)
    tokens = [tok for _, tok in projected]
    for i in range(max(0, len(tokens) - n + 1)):
        if tokens[i : i + n] == target:
            return tuple(idx for idx, _ in projected[i : i + n])
    return None


def _subcommand_match_position_prev(rest, subcommands, value_flags):
    """`p_prev`(#1454): merge-base(617c4a88)の位置引数整列をそのまま再現する。

    `-` で始まるトークンは読み飛ばし、一致が未完了のあいだは `value_flags`
    (`GLOBAL_VALUE_FLAGS[program]`)に一致するトークンと**その次**のトークンを
    読み飛ばす。残った位置引数列の**先頭**が `subcommands` に一致するときだけ
    成立し、一致した先頭 `len(subcommands)` 個の生 index を返す。無ければ
    `None`。裸の `--` で打ち切らない(merge-base と同一挙動を保つため —
    `glab -- mr merge --rebase` はこの関数だけなら「一致」と判定しうるが、
    `invokes()` の OR 相手である `p_raw`/`p_proj` が打ち切るので、検出全体としては
    #1454 Readiness 再評価のスイープで allow 方向の変化 0 件を維持できている)。

    `GLOBAL_VALUE_FLAGS` の網羅性を引く**唯一**の候補であり、`p_proj` が
    curated リストに依存せず検出を担保する(AC2)。`invokes()`/
    `_leading_global_git_flags()` の両方から呼ばれ、一致位置の求め方を1箇所に
    まとめる(#1454 Scope: `_leading_global_git_flags` は独自にフラグを走査しない)。
    """
    if not subcommands:
        return None
    target = list(subcommands)
    n = len(target)
    positional_raw = []
    matched = 0
    skip_next = False
    for idx, a in enumerate(rest):
        if skip_next:
            skip_next = False
            continue
        match_complete = matched >= n
        if not match_complete and a in value_flags:
            skip_next = True
            continue
        if a.startswith("-"):
            continue
        positional_raw.append(idx)
        if not match_complete and a == target[matched]:
            matched += 1
    if matched < n:
        return None
    head = positional_raw[:n]
    if [rest[i] for i in head] != target:
        return None
    return tuple(head)


def _subcommand_match_candidates(rest, subcommands, value_flags):
    """`p_prev`/`p_proj`/`p_raw` の3候補をまとめて計算する(#1454)。

    それぞれ「一致した `subcommands` トークンの生 index のタプル」または
    `None` を返す。`invokes()`(検出+`residual` 組み立て)と
    `_leading_global_git_flags()`(前置フラグ列の候補列)の両方がこの1関数を
    経由することで、「どちらも同じ一致規則を使う」という前提を1箇所で保つ。
    """
    prev = _subcommand_match_position_prev(rest, subcommands, value_flags)
    proj = _subcommand_match_position_proj(rest, subcommands)
    raw_start = _subcommand_match_position(rest, subcommands)
    raw = tuple(range(raw_start, raw_start + len(subcommands))) if raw_start is not None else None
    return prev, proj, raw


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

    ## サブコマンド検出は3候補の OR(#1454、レビュー1回目 BLOCKING1 対応)

    検出そのもの(呼び出しがあるかどうか)は `_subcommand_match_candidates()` が
    返す3候補(`p_prev`/`p_proj`/`p_raw`)の**いずれかが成立すれば真**とする。
    単独の連続一致(`p_raw` だけ)では、サブコマンド2語の**あいだ**に
    `GLOBAL_VALUE_FLAGS` のフラグ(`glab mr -R o/r merge` の `-R o/r`)が挟まると
    検出を失う(#1435/#1440 が閉じたはずの回避経路の再発、レビュー1回目
    BLOCKING1)。`p_prev`(merge-base の従来整列)を候補に含めることで、
    merge-base が検出していた呼び出しを1つも失わない — 検出集合が真に広がる
    だけになる(#1454 Readiness 再評価で 3,013 件のスイープにより確認: 2候補案
    では 77 件が merge-base の DENY を失ったが、`p_prev` を第3候補として OR に
    加えると 0 件)。

    **安全性は `GLOBAL_VALUE_FLAGS` の網羅性に依存しない**: `p_proj`(cobra の
    `stripFlags` と同じ規則の射影)は curated なリストを一切引かず、このリストに
    無い値フラグ(`--sha`/`-m`/`--title`/`--attr-source` 等)や定義すら無い任意の
    フラグ(`-z`/`--zz`)がサブコマンドの前後・あいだのどこにあっても検出する
    (AC2: `GLOBAL_VALUE_FLAGS` を空集合にしても `p_prev` は不成立になるだけで
    `p_proj` が検出を保つ)。

    ## 採用位置: `p_prev` → `p_proj` → `p_raw` の順(`residual` 組み立て用)

    3候補のうち最初に成立したものを`residual` 組み立てに使う。`p_prev` を
    最優先するのは、`p_prev` が成立する入力(既存呼び出しの大半)では `residual`
    が merge-base と**完全に同一**になり、#1446/#1450 のヘルプ判定がそのまま
    保たれるため。

    ## `residual` の組み立て(#1450 の規則を一般化。値の変更はしない)

    採用した候補の一致トークン(生 index の集合)は `residual` に含めない。
    `match_end` を「**最後に**一致したトークンの生 index」と定義し、
    **`match_end` より前**では `GLOBAL_VALUE_FLAGS[program]` に一致するトークンと
    その次を読み飛ばし、**`match_end` 以降**は一切読み飛ばさない。それ以外の
    トークンは `-` で始まるかどうかを問わず一律 `residual` に積む。一致が生の列で
    非連続な場合(`glab mr -R o/r merge --help` の `p_prev` は `mr`=index0、
    `merge`=index3 に一致し、`match_end`=3)、`-R`/`o/r`(index1・2)は
    `match_end` より前なので読み飛ばされ、`residual` は `['--help']` になる —
    #1450 が確定した「一致完了までは読み飛ばす/一致後は読み飛ばさない」を
    非連続一致へ一般化した言い換えであり、矛盾しない。連続一致(`p_raw` 由来)の
    場合は `match_end = match_at + len(subcommands) - 1` と等価になるので、
    #1450 までの挙動は変わらない。

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
            prev, proj, raw = _subcommand_match_candidates(rest, subcommands, value_flags)
            matched_indices = prev if prev is not None else (proj if proj is not None else raw)
            if matched_indices is None:
                continue
            matched_set = set(matched_indices)
            match_end = max(matched_indices)
            residual = []
            skip_next = False
            for idx, a in enumerate(rest):
                if skip_next:
                    skip_next = False
                    continue
                if idx in matched_set:
                    continue
                if idx < match_end and a in value_flags:
                    skip_next = True
                    continue
                if idx < match_end and _is_attached_global_value_flag(a, value_flags):
                    continue
                residual.append(a)
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

    #1446 レビュー1回目までの実装(クラスタ内の文字一致 → 完全一致)はどちらも「`residual` の中に
    `--help`/`-h` が**1つでも**含まれていれば真」という OR 方式だった。これは、値を
    取るフラグの値としてたまたま独立トークンの `-h` が渡された形を、本物の `-h` と
    区別できない: `git commit -m -h --no-verify` の `-h` は `-m` の値、
    `glab issue update ... --title -h` の `-h` は `--title` の値であり、どちらも
    ヘルプ表示ではなく通常の操作(かつ危険なフラグを伴う)である。`residual` には
    `-m`/`--title` 自身も残っているのに、OR 方式はそれらを無視して `-h` の存在だけで
    真と判定し、後続の危険フラグ検査(`--no-verify` 禁止、hotfix 不変性)ごと
    スキップしてしまっていた(fail-open。#1446 レビュー2回目 BLOCKING の指摘)。

    「すべてのトークンが help」という AND 方式にすることで、`-h`/`--help` 以外の
    トークンが1つでも残っていれば非ヘルプと判定するようになり、上記の fail-open を
    防ぐ。

    副作用として、位置引数や他のフラグが `--help`/`-h` と共存する呼び出し
    (`glab mr merge 42 --help` の `42`、`-h --rebase` の `--rebase`)は、実際の cobra
    ではヘルプだけを表示し `42` のマージや `--rebase` の適用は起きないはずだが、この
    実装では非ヘルプと判定し通常判定(deny/発火)に倒す。これは意図的に受け入れる
    誤拒否(fail-closed)であり、値を取る短縮フラグを curated に列挙したリスト
    (旧 `SHORT_VALUE_FLAGS` 依存方式)の網羅性に頼る設計へ戻らないためのトレードオフ
    として、呼び出し元(2026-09-27 #1446 レビュー2回目)が明示的に受け入れた。fail-open では
    なく実害はない。
    """
    return bool(residual) and all(tok in ("--help", "-h") for tok in residual)


# マージ方式のフラグ。長いフラグと、cobra が受け付ける短縮フラグの結合(`-sd`)の両方。
# `--squash-message` はコミットメッセージの指定であって方式の指定ではないので、
# `--squash` の前方一致で拾ってはいけない。`-R`(--repo)は `-r`(--rebase)ではない。
# `glab mr merge` の既知のブール型フラグ(glab 1.116.0 の `glab mr merge --help` で確認、#1463)。
# 値を取るのは `-m/--message`、`-R/--repo`、`--sha`、`--squash-message`。ここに無い
# フラグは「値を取るかもしれない」とみなし、次のトークンを値として消費する(fail-closed)。
MR_MERGE_BOOL_FLAGS = {
    "--auto-merge", "--help", "--rebase", "--remove-source-branch", "--squash", "--yes",
    "-h", "-r", "-d", "-s", "-y",
}


def _consumes_next(arg, value_shorts, bool_flags):
    """`arg`(`-` 始まり)が、次のトークンを自身の値として消費しうるか。

    `=` 直結・短縮直結(`-mmsg`)は1トークンで完結するので消費しない。既知のブールで
    なければ消費する側に倒す(未知フラグの網羅性に安全性を依存させない)。
    """
    if arg.startswith("--"):
        return "=" not in arg and arg not in bool_flags
    cluster = arg[1:]
    for i, ch in enumerate(cluster):
        if ch in value_shorts or f"-{ch}" not in bool_flags:
            return i == len(cluster) - 1
    return False


def _has_flag(args, long_name, short, value_shorts=frozenset(), bool_flags=None):
    """`bool_flags` を渡すと、値として消費されうるトークンはフラグと数えない(fail-closed)。

    `--squash` の「明示要求」のように、見逃しが許可側に倒れる判定で使う。`--rebase` の
    ような禁止フラグの検出では渡さない(値の中身でも拒否するほうが安全側)。
    """
    skip = False
    for arg in args:
        if skip:
            skip = False
            continue
        if arg == long_name or arg.startswith(long_name + "="):
            return True
        if bool_flags is not None and arg.startswith("-") and len(arg) > 1:
            skip = _consumes_next(arg, value_shorts, bool_flags)
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
        if not _has_flag(args, "--squash", "s", value_shorts, MR_MERGE_BOOL_FLAGS):
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


# read-only stage のマーカーが指定のスキルのときだけ許す遷移(#1625)。無条件の
# LEGAL_STATUS_TRANSITIONS には足さない(CLAUDE.md → Legal Transitions)。
# 値はスキル名の集合: 1つの遷移を複数のスキルに許せる(`(Inbox, Done)` は close-epic と
# close-issue、#1659)。In Progress / Review からの Done はここに無く、常に拒否される。
MARKER_GATED_STATUS_TRANSITIONS = {
    ("Inbox", "Done"): {"close-epic", "close-issue"},
    ("Backlog", "Done"): {"close-issue"},
    ("Ready", "Done"): {"close-issue"},
}


def check_status_label_integrity(command, payload=None):
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
        gate = MARKER_GATED_STATUS_TRANSITIONS.get((old_status, new_status), ())
        if gate and payload is not None and read_stage(payload) in gate:
            continue
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


def check_unknown_wrapper_flag(command):
    """CLAUDE.md → Enforcement → What the guards are, and are not(#1462 Requirement 4)。

    `env -u FOO ...` の `-u` のように、既知のラッパーに続くフラグが値を取るかどうか
    判定できないと、その値が実体コマンドの検出に混入し、`--rebase` 禁止・
    `--no-verify` 禁止・hotfix 不変性・カバレッジゲートのいずれも素通りしてしまう
    — この Issue で `env` の `-u`/`-C` が実際に踏んだ壊れ方そのもの。

    `WRAPPERS`/`WRAPPER_BOOL_FLAGS` は実機の `--help` で洗い出した既知のフラグの
    列挙だが、将来のバージョンでフラグが増減する可能性は消せない(網羅性の主張は
    しない、CLAUDE.md → ガード)。未知のフラグに出会ったら、どの下流ガードが
    対象にすべきコマンドかを推測せず、ここで一律に拒否する — 安全性をテーブルの
    網羅性に依存させないための、#1446 が到達した方向と同じ考え方。
    """
    for name, flag in _unknown_wrapper_flags(command):
        emit_deny(
            "`%s` の直後のフラグ `%s` を認識できません。値を取るかどうか判定できないため、"
            "後続のどこから実体のコマンドが始まるか安全に判定できません"
            "(CLAUDE.md → Enforcement → What the guards are, and are not)。"
            "`.claude/hooks/guard.py` の `WRAPPERS`/`WRAPPER_BOOL_FLAGS` を実機の "
            "`--help` で確認したうえで追加してください。" % (name, flag)
        )


def check_no_verify(command):
    # merge は pre-merge-commit(#1452)を、pull は内部で merge を呼ぶので同じフックを外せる
    # (#1465)。`-n` は commit だけが --no-verify の短縮で、merge / pull では --no-stat
    # (pull では --no-stat 相当)の短縮なので対象にしない。
    for sub in ("commit", "merge", "pull", "push"):
        for args in invokes(command, "git", (sub,)):
            if "--no-verify" in args or (sub == "commit" and "-n" in args):
                emit_deny(
                    "`--no-verify` は禁止です。git フックはこのリポジトリのフェーズ分離"
                    "(CLAUDE.md → Test-First Implementation)を強制するためのものです。"
                )


def _leading_global_git_flags(args):
    """`args`(`invokes(command, "git", ("commit",))` が返す、`git` の後続トークン列)
    のうち、`commit` サブコマンドより前のグローバルフラグ列の**候補列**を返す
    (#1443、#1454 Requirement 3)。

    単一のリストではなく、`_subcommand_match_candidates()` の3候補
    (`p_prev`/`p_proj`/`p_raw`)それぞれの一致開始位置から導いた `args[:開始位置]`
    を重複排除し、**短いものから順に**並べたリストを返す。呼び出し元
    (`check_commit_phase`)はこれを順に試し、実際に `git <候補> diff --cached
    --name-only` が成功した最初の候補を採用する。

    ## なぜ単一候補では原理的に閉じないか(#1454 レビュー1回目 BLOCKING2)

    グローバルフラグの値がサブコマンド語と同名のとき(`git -C commit commit`)は
    `p_proj`/`p_prev` の一致開始位置が正しく、ブールのグローバルフラグの直後に
    サブコマンド語と同名の位置引数があるとき(`git --attr-source commit commit`
    — `commit` を値に取る `--attr-source` の場合)は逆に `p_proj` だけが正しい
    位置を返す。どちらが正しいかは「どのグローバルフラグが値を取るか」を
    知らないと決まらず、それは curated なリストの再導入である。そのため
    「正しい1つを選ぶ」のではなく、**候補をすべて試して実際に動く1つを採用する**。

    `p_prev` は常に候補に含まれる(#1454 Readiness 再評価のスイープ、17,380件で
    反例0)ため、merge-base のフェーズ分離検出を1件も失わない。短い候補から
    試すのは、誤った候補が git 自身のグローバルオプション解釈で必ず失敗するため
    (実測: `git -C docs --cached --name-only` → `unknown option: --cached`)。

    `args` は `invokes()` が `("commit",)` への一致を確認済みの `rest` そのものであり、
    一致位置の求め方は `_subcommand_match_candidates()` 一箇所に集約している —
    `invokes()` の検出規則が変わってもここが自動的に追従する。
    """
    value_flags = GLOBAL_VALUE_FLAGS.get("git", set())
    prev, proj, raw = _subcommand_match_candidates(args, ("commit",), value_flags)
    starts = sorted({m[0] for m in (prev, proj, raw) if m is not None})
    if not starts:
        # `args` は呼び出し元が `invokes()` で一致を確認済みのものしか渡さないため、
        # 通常はここに来ない。来た場合、`args` 全体を前置として `git` に渡すと
        # `commit` 自身やその引数まで `diff --cached --name-only` の前に紛れ込み、
        # `git` がエラーになって `check_commit_phase` が `staged is None` から
        # 早期 `continue` する(フェーズ分離検査そのものが素通りする fail-open)。
        # 前置が特定できないなら「前置無し」(空リスト)の方が安全: 通常の
        # `git commit`(前置無し)と同じ経路になり、cwd のリポジトリをそのまま見る。
        return [[]]
    return [args[:s] for s in starts]


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
        #
        # 前置列は候補集合であり、単一の「正しい」ものを事前に決められない
        # (#1454 Requirement 3)。実際に `diff --cached --name-only` が成功した
        # 最初の候補を採用し、すべて失敗したときだけ検査自体を諦める。
        staged = None
        prefix = []
        for candidate in _leading_global_git_flags(args):
            result = git(candidate + ["diff", "--cached", "--name-only"], root)
            if result is not None:
                staged = result
                prefix = candidate
                break
        if staged is None:
            continue
        # マージ結果を確定するコミット(MERGE_HEAD あり)は、2つの履歴の機械的な
        # 突き合わせなのでフェーズ分離の対象外(CLAUDE.md → Merge Conflicts, #1546)。
        merge_head = git(
            prefix + ["rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD"],
            root,
        )
        if merge_head and os.path.exists(merge_head.strip()):
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
    check_unknown_wrapper_flag(command)
    check_merge_flags(command)
    check_status_label_integrity(command, payload)
    check_hotfix_label_immutability(command)
    check_hotfix_creation(payload, command)
    check_issue_creation_requires_status(command)
    check_no_verify(command)
    check_commit_phase(payload, command)
    check_pr_coverage(payload, command)
    allow()


# --------------------------------------------------------------------------- agent

# ワークフローのエージェント。バックグラウンドで起動すると、メイン側が「待つ」と宣言して
# ターンを終え、`claude -p` が結果を返して無人キューの再開を1回消費する(#1269)。
# 汎用エージェントは対話作業で正当にバックグラウンド起動されうり、フックからは
# ワークフロー中かどうか判別できないため対象外(docs/WORKFLOW_RULE_RATIONALE.md)。
WORKFLOW_AGENTS = {"implementer", "reviewer", "qa", "project-planner"}


def cmd_agent(payload):
    tool_input = payload.get("tool_input") or {}
    agent = tool_input.get("subagent_type")
    if agent in WORKFLOW_AGENTS and tool_input.get("run_in_background") is True:
        emit_deny(
            "ワークフローのエージェント `%s` をバックグラウンドで起動してはいけません"
            "(CLAUDE.md → Enforcement)。メイン側が待機を宣言してターンを終えると、"
            "結果を受け取る前に段階が終わります。`run_in_background` を付けず、"
            "フォアグラウンドで呼び直してください。" % agent
        )
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
            real, stripped, unknown = strip_wrappers(argv)
            print("  [%d] 実体      : %s" % (i, real))
            if stripped:
                print("      剥がした前置: %s" % stripped)
            if unknown:
                print("      未知フラグ  : %s の %s(fail-closed の対象、Requirement 4)" % unknown)
            if redirects:
                print("      書き込み先  : %s" % redirects)
            reason = destructive_reason(real)
            if reason:
                print("      読み取り専用ステージ: 拒否(%s)" % reason)

    for label, check in (
        ("未知のラッパーフラグ", check_unknown_wrapper_flag),
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
        "prompt": cmd_prompt,
        "write": cmd_write,
        "bash": cmd_bash,
        "agent": cmd_agent,
    }.get(sys.argv[1], lambda _: sys.exit(0))(payload)


if __name__ == "__main__":
    main()
