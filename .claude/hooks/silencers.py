"""テストを黙らせる手段のパターン。`CLAUDE.md` → Test-First Implementation → Never skip a test。

`.claude/hooks/paths.py` が両フックから import される唯一の分類器であるのと同じ理由・同じ形で、
`SILENCERS` もここを唯一の出所とする(#1055)。`.claude/hooks/guard.py`(Claude Code の
PreToolUse フック)と `scripts/git-hooks/pre-commit`(git フック)の両方がこれを import する。
パターンを他所に書き写さないこと — 書き写すと、片方だけを更新したときに静かにずれる
(現に `pre-commit` 側だけ `@(skip|fixme)` が欠落していた)。
"""

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
