#!/usr/bin/env python3
"""SSH経由のwp-cliコマンド文字列に、ShellQuoteを通さない動的な値が無いかを検査する(issue #1416)。

`ShellQuote` の javadoc がこのファイル群の規約を定めている:

    SSHのexecはリモートシェルが解釈する1本の文字列を送るだけなので、
    コマンド文字列へ埋め込む動的な値は必ずこれを通す(コマンドインジェクション対策)。

#1416 の時点で45箇所がこの規約を守り、7箇所が漏れていた。規約がjavadocに書いてある
だけでは漏れは防げなかったので、機械的に検査する(#1416 Requirement 3)。

    python3 scripts/check-ssh-command-quoting.py

## 何を見るか

Javaの文字列連結 `"... " + <式> + " ..."` のうち、コマンド文字列の組み立てに
見えるもの(`wp` のサブコマンド名で始まる文字列リテラルを含む行)を対象に、
連結される式が `ShellQuote.single(...)` を通っているかを見る。

## 何を見ないか(既知の限界)

行単位の字句検査であり、Javaの構文解析はしない。複数行にまたがる連結や、
いったん変数へ入れてから連結する形は追えない。**完全性は主張しない。**
`postFieldsArgs` のように内部で各値をクォート済みの合成文字列は、
`SAFE_COMPOSED` に列挙して除外する(再クォートすると壊れるため)。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

TARGET = os.environ.get(
    "CHECK_SSH_COMMAND_QUOTING_FILE",
    os.path.join(REPO_ROOT, "services/publishing/src/main/java/com/letsblog/publishing/cms/ssh/WordPressSshOperations.java"),
)

#: wp-cli のサブコマンドを組み立てている文字列リテラルの先頭語。
WP_SUBCOMMANDS = (
    "post", "term", "plugin", "theme", "user", "option", "core",
    "media", "db", "eval", "config", "rewrite", "cache", "transient",
)

#: wp-cli を介さず直接リモートシェルへ送るコマンド。3周目で見つかった盲点(issue #1416)。
#: `exec()` の行き先は同じリモートシェルなので、wp-cli のサブコマンドだけを見ていては足りない。
#: 例: `"tar -czf " + ... + " " + dirName` の `dirName` は1・2周目の走査対象外だった。
#: `<式> + " <動詞> ..."` の <動詞>。式の側がサブコマンド名になっている形を拾うため。
COMMAND_VERBS = (
    "list", "install", "uninstall", "delete", "update", "create", "get",
    "activate", "deactivate", "import", "export", "search", "add", "remove",
    "meta", "term", "path", "is-installed", "status",
)

PLAIN_SHELL_COMMANDS = (
    "tar", "test", "mkdir", "rm", "cp", "mv", "cat", "chmod", "chown",
    "ls", "find", "sed", "awk", "grep", "sh", "bash", "curl", "wget",
    "mysql", "mysqldump", "php", "unzip", "zip", "echo", "touch",
)

#: 内部で各値をクォート済みの合成文字列。再クォートすると壊れるので除外する。
#: **値の出どころが変わったらここから外すこと。**`postFieldsArgs` がクォートしない項目を
#: 足したら、この検査は黙って通ってしまう。
SAFE_COMPOSED = ("fields",)

#: `.append(...)` に渡してよい値。**呼び出し側が文字列リテラルしか渡さないもの**に限る。
#: 値の出どころが変わったらここから外すこと。
SAFE_APPEND = (
    "field",  # appendFieldIfPresent の第2引数。呼び出しは全て文字列リテラル
)

#: 連結してよい式。**生成値でメタ文字を含みえないもの**に限る。
SAFE_EXPRESSIONS = (
    "UUID.randomUUID()",  # 16進とハイフンのみ。シェルのメタ文字を含みえない
)

_LITERAL_WITH_CONCAT = re.compile(r'"((?:[^"\\]|\\.)*)"\s*\+\s*([A-Za-z_][A-Za-z0-9_.()]*)')

#: `<式> + "literal"` の順。3周目で見つかった盲点(issue #1416)。
#: `type + " list --fields=..."` のように式が先に来る形を1・2周目は見ていなかった。
_CONCAT_THEN_LITERAL = re.compile(r'([A-Za-z_][A-Za-z0-9_.()]*)\s*\+\s*"((?:[^"\\]|\\.)*)"')

#: `StringBuilder` でコマンドを組み立てる形。`.append(<式>)` の <式> が
#: 文字列リテラルでも ShellQuote でもなければ素通しである(issue #1416 の2周目で追加)。
#: 最初の版は `" + x` の形しか見ておらず、`new StringBuilder("term create ").append(taxonomy)`
#: を見逃していた。実際にその形で5箇所漏れていた。
_APPEND_CALL = re.compile(r'\.append\(\s*([A-Za-z_][A-Za-z0-9_.()]*)\s*\)')

_SHELLQUOTE_CALL = "ShellQuote.single("


def strip_shellquote_calls(line: str) -> str:
    """`ShellQuote.single(...)` の**中身**を取り除く。

    中身の連結(例: `ShellQuote.single(prefix + "users," + prefix + "usermeta")`)は
    既にクォートの内側なので違反ではない。素朴に行全体を走査すると誤検知になる。
    括弧の対応を数えて呼び出し全体を落とす。
    """
    out = []
    i = 0
    while i < len(line):
        at = line.find(_SHELLQUOTE_CALL, i)
        if at < 0:
            out.append(line[i:])
            break
        out.append(line[i:at])
        out.append("QUOTED")
        depth = 0
        j = at + len(_SHELLQUOTE_CALL) - 1
        while j < len(line):
            if line[j] == "(":
                depth += 1
            elif line[j] == ")":
                depth -= 1
                if depth == 0:
                    j += 1
                    break
            j += 1
        i = j
    return "".join(out)


def looks_like_command_literal(literal: str) -> bool:
    head = literal.strip().split(" ")[0] if literal.strip() else ""
    return head in WP_SUBCOMMANDS or head in PLAIN_SHELL_COMMANDS


def looks_like_command_tail(literal: str) -> bool:
    """`<式> + "literal"` の literal 側が、コマンドの続きに見えるか。

    `type + " list --fields=..."` のように、式が先でリテラルが後に来る形を拾う。
    オプション(`-`で始まる)やサブコマンド名で始まるものをコマンドの続きとみなす。
    """
    body = literal.strip()
    if not body:
        return False
    head = body.split(" ")[0]
    if head in WP_SUBCOMMANDS or head in PLAIN_SHELL_COMMANDS:
        return True
    # 本物のフラグは `-x` / `--x` のように英数字が続く。ただの区切りの `"-"` は
    # パス文字列の組み立て(`"/tmp/letsblog-" + dirName + "-" + ...`)なので対象外。
    if re.match(r"-{1,2}[A-Za-z]", head):
        return True
    # `type + " list --fields=..."` のように、式がサブコマンド名そのもので、
    # リテラルがその続き(動詞やフラグ)になっている形。
    if head in COMMAND_VERBS:
        return True
    return " --" in literal or bool(re.search(r"\s-[A-Za-z]", literal))


def violations(path: str):
    found = []
    append_violations = []
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    # 直前行に wp-cli のサブコマンドリテラルがある複数行連結も拾えるよう、1行前を持ち越す。
    carry_is_command = False
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("*") or stripped.startswith("//"):
            continue
        scanned = strip_shellquote_calls(line)
        matches = list(_LITERAL_WITH_CONCAT.findall(scanned))
        # `<式> + "literal"` の順(3周目で追加)。
        for expr, literal in _CONCAT_THEN_LITERAL.findall(scanned):
            if expr.startswith("QUOTED") or expr in SAFE_COMPOSED or expr in SAFE_EXPRESSIONS:
                continue
            if looks_like_command_tail(literal):
                append_violations.append((number, expr, stripped))
        # StringBuilder 形式。**このファイルは全体がSSHコマンドの組み立て専用**なので、
        # 素の `.append(<式>)`(文字列リテラルでもShellQuoteでもないもの)は
        # 行の文脈を問わず違反として扱う。`command.append(" --parent=").append(parent.termId())`
        # のように、直前のリテラルがサブコマンド名でない形を取りこぼさないための保守的な規則。
        # 安全だと判断したものは SAFE_APPEND に理由とともに列挙する。
        for expr in _APPEND_CALL.findall(scanned):
            if expr in SAFE_APPEND or expr in SAFE_EXPRESSIONS or expr.startswith("QUOTED"):
                continue
            append_violations.append((number, expr, stripped))
        line_is_command = carry_is_command
        for literal in re.findall(r'StringBuilder\("((?:[^"\\]|\\.)*)"', scanned):
            if looks_like_command_literal(literal):
                line_is_command = True
        for literal, expr in matches:
            if literal and looks_like_command_literal(literal):
                line_is_command = True
            if not line_is_command:
                continue
            if expr.startswith("QUOTED"):
                # strip_shellquote_calls が置いた印。既にクォート済み。
                continue
            if expr in SAFE_COMPOSED or expr in SAFE_EXPRESSIONS:
                continue
            found.append((number, expr, stripped))
        # 末尾が文字列リテラルで終わる継続行のために、コマンド判定を次行へ持ち越す。
        carry_is_command = line_is_command and not stripped.endswith(";")
    return found + append_violations


def main() -> int:
    if not os.path.exists(TARGET):
        print("検査対象が見つかりません: %s" % TARGET, file=sys.stderr)
        return 1
    found = violations(TARGET)
    if not found:
        print("OK: ShellQuoteを通さない動的な値はありません (%s)" % os.path.relpath(TARGET, REPO_ROOT))
        return 0
    print("✗ ShellQuoteを通さない動的な値がコマンド文字列に埋め込まれています(issue #1416):\n", file=sys.stderr)
    for number, expr, text in found:
        print("  %s:%d  %s\n      %s" % (os.path.relpath(TARGET, REPO_ROOT), number, expr, text), file=sys.stderr)
    print("\nShellQuote.single(...) を通してください。"
          "内部でクォート済みの合成文字列なら SAFE_COMPOSED へ理由とともに追加すること。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
