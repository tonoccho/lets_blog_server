"""リポジトリ内パスを「テストコード / プロダクションコード / 中立」に分類する。

`CLAUDE.md` → **Test-First Implementation** のフェーズ分離を機械的に判定するための
唯一の分類器。`.claude/hooks/guard.py`(Claude Code フック)と
`scripts/git-hooks/pre-commit`(git フック)の両方がこれを import する。
分類規則を他所に書き写さないこと。
"""

import re

# テストコード。JVM の src/test・src/testFixtures、Gherkin の .feature、
# playwright-bdd の e2e 一式、jest の *.test.* / *.spec.* を含む。
TEST_PATTERNS = [
    r"(^|/)src/test/",
    r"(^|/)src/testFixtures/",
    r"\.feature$",
    r"^apps/web/e2e/",
    r"\.test\.[jt]sx?$",
    r"\.spec\.[jt]sx?$",
    r"(^|/)__tests__/",
    r"(^|/)__mocks__/",
]

# プロダクションコード。上のテスト条件に当たらない、実装ソースツリー配下のファイル。
# docs/・config/・build.gradle・.github/・scripts/・.claude/ は「中立」であり、
# テストと同じコミットに入ってもフェーズ分離違反にはならない。
PRODUCTION_PATTERNS = [
    r"^apps/[^/]+/src/",
    r"^services/[^/]+/src/",
    r"^packages/[^/]+/src/",
]

TEST_RE = [re.compile(p) for p in TEST_PATTERNS]
PRODUCTION_RE = [re.compile(p) for p in PRODUCTION_PATTERNS]


def is_test(path: str) -> bool:
    return any(r.search(path) for r in TEST_RE)


def is_production(path: str) -> bool:
    if is_test(path):
        return False
    return any(r.search(path) for r in PRODUCTION_RE)


def classify(paths):
    """(テストコードのパス, プロダクションコードのパス) を返す。中立パスは捨てる。"""
    tests = [p for p in paths if is_test(p)]
    prod = [p for p in paths if is_production(p)]
    return tests, prod
