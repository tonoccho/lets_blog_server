#!/usr/bin/env python3
"""bddgen の生成物 `apps/web/.features-gen/` が全ランナー・全ツールから除外されることを検証する(#994)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

受け入れテストは `.feature` を playwright-bdd(`bddgen`)で `apps/web/.features-gen/**/*.spec.js`
へ変換して実行する(#926 / AT-0)。生成物はコミットせず、各ツールの設定で個別に除外している。

**除外は1か所ではなく複数か所にあり、同期している保証が無い。** 実際 #848 は eslint が
`playwright-report/` を走査して257件のエラーで落ちた Issue で、#994 は同型の再発である —
`.gitignore` / `eslint.config.mjs` / `tsconfig.json` の3か所は更新されたが、
`jest.config.ts` だけが漏れ、一度でも `bddgen` を走らせた作業ツリーでは `npm run test` が
「15 suites failed」になった。単体テスト自体は全て通っているのに、である。

漏れは**生成物が存在するときにしか現れない**。`bddgen` を走らせていない作業ツリーや
クリーンな CI では緑になるため、レビューでも気づけない。そこで設定そのものを検査する。

## jest だけ検査の形が違う理由

`.gitignore` / `eslint.config.mjs` / `tsconfig.json` は `.features-gen` を名指しで除外する。
jest はそうしない。`testPathIgnorePatterns` への追加は `.claude/hooks/guard.py` が
「テストの握りつぶし」としてブロックするためで、ガードはこのケース(テストではなく別ランナーの
生成物の除外)を区別しない。#994 の Implementation Notes が指示するとおり、ガードを迂回せず
`testMatch` で拾う対象を列挙することで除外する。したがって jest は「`.features-gen` を
名指ししていること」ではなく「拾う対象がそれを含み得ないこと」で検査する。

## 狭めすぎも検査する

範囲を狭める修正は、**黙って本物のテストを実行対象から外す**危険がある。実際 `roots` を
`<rootDir>/src` に閉じる最初の案は、web 直下にある `next.config.test.ts`(src/ の外にある
プロダクションコード next.config.ts の単体テスト。#984)を1スイートまるごと落としていた。
`npm run test` は緑のままスイート数だけが 32 → 31 に減るため、出力を見ても気づけない。
そこで「生成物を拾わないこと」と対で「src/ の外にある単体テストを拾うこと」も検査する。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
WEB = os.path.join(REPO_ROOT, "apps/web")

GENERATED_DIR = ".features-gen"

# 生成物の除外を担う設定ファイル。ここに挙げた数と、文書・コメントが書いている数が
# 一致していなければならない(下の test_documented_count_matches_reality)。
EXCLUSION_SITES = (
    "apps/web/.gitignore",
    "apps/web/eslint.config.mjs",
    "apps/web/tsconfig.json",
    "apps/web/jest.config.ts",
)

# 除外か所の数を書いている文書・コメント。
COUNT_DOCS = (
    "docs/ACCEPTANCE_TESTING.md",
    "apps/web/playwright.config.ts",
)

COUNT_RE = re.compile(r"(\d+)\s*か所")


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


def string_array(text, key):
    """`key: [...]` / `"key": [...]` の中の文字列リテラルをリストで返す。無ければ None。

    JSON としてパースしないのは、tsconfig.json の `"**/*.ts"` のようなグロブが
    ブロックコメント除去の正規表現と衝突するため。ここで必要なのは配列1つの中身だけで、
    設定ファイル全体を構文解析する必要はない。
    """
    m = re.search(r"[\"']?" + re.escape(key) + r"[\"']?\s*:\s*\[(.*?)\]", text, re.S)
    if m is None:
        return None
    return re.findall(r"['\"]([^'\"]+)['\"]", m.group(1))


def jest_test_match():
    """jest.config.ts の `testMatch` を文字列のリストとして返す。未設定なら None。"""
    return string_array(read("apps/web/jest.config.ts"), "testMatch")


class BddgenOutputExclusion(unittest.TestCase):
    def test_named_exclusion_sites_mention_generated_dir(self):
        """名指しで除外する3か所が、実際に `.features-gen` を挙げている。"""
        for rel_path in (
            "apps/web/.gitignore",
            "apps/web/eslint.config.mjs",
            "apps/web/tsconfig.json",
        ):
            with self.subTest(path=rel_path):
                self.assertIn(
                    GENERATED_DIR,
                    read(rel_path),
                    f"{rel_path} が {GENERATED_DIR} を除外していない",
                )

    def test_tsconfig_exclude_lists_generated_dir(self):
        """tsconfig の除外は `exclude` 配列に入っていなければ効かない。"""
        exclude = string_array(read("apps/web/tsconfig.json"), "exclude")
        self.assertIsNotNone(exclude, "tsconfig.json に exclude が無い")
        self.assertIn(GENERATED_DIR, exclude)

    def test_jest_test_match_cannot_reach_generated_dir(self):
        """jest が拾う対象が `.features-gen` を含み得ない。

        `testMatch` が未設定だと jest は既定の `**/?(*.)+(spec|test).[jt]s?(x)` で
        rootDir 全体を対象にし、`.features-gen/**/*.feature.spec.js` をテストとして
        拾ってしまう(#994 の症状)。
        """
        patterns = jest_test_match()
        self.assertIsNotNone(
            patterns,
            "jest.config.ts に testMatch が無い。既定パターンが rootDir 全体に及び "
            f"{GENERATED_DIR} の生成物を拾う(#994)",
        )
        self.assertTrue(patterns, "jest.config.ts の testMatch が空")
        for pattern in patterns:
            with self.subTest(pattern=pattern):
                self.assertTrue(
                    pattern.startswith("<rootDir>/"),
                    f"testMatch の {pattern} が <rootDir> 起点でなく、対象範囲が不定",
                )
                self.assertNotIn(GENERATED_DIR, pattern)
                # `<rootDir>/**/...` は rootDir 全体に及び、生成物を含んでしまう。
                self.assertFalse(
                    pattern.startswith("<rootDir>/**"),
                    f"testMatch の {pattern} が rootDir 全体に及んでいる",
                )

    def test_jest_still_collects_tests_outside_src(self):
        """範囲を狭めた結果、src/ の外にある単体テストを取りこぼしていない。

        next.config.ts は src/ の外にあるプロダクションコードで(#984、
        jest.config.ts の collectCoverageFrom もこれを明示的に含めている)、その単体テストは
        web 直下にある。`roots: ['<rootDir>/src']` で閉じるとこれが黙って実行されなくなる。
        """
        outside_src = "apps/web/next.config.test.ts"
        self.assertTrue(
            os.path.exists(os.path.join(REPO_ROOT, outside_src)),
            f"{outside_src} が無い。移動・削除したならこのテストの前提も見直すこと",
        )
        patterns = jest_test_match() or []
        self.assertTrue(
            any(
                p.startswith("<rootDir>/") and "/" not in p[len("<rootDir>/") :]
                for p in patterns
            ),
            "testMatch に web 直下(<rootDir>/*.test.ts 相当)を拾うパターンが無い。"
            f"{outside_src} が実行されなくなる",
        )

    def test_documented_mechanism_matches_jest_config(self):
        """文書・コメントが、jest が実際に使っている除外の仕組みを名指ししている。

        数とファイル名が合っていても、**仕組みの説明が実装とずれる**と害になる。
        レビューで実際に見つかった: 文書は `roots` で除外すると書いていたのに実装は
        `testMatch` を使っており、しかも `roots` は「採らなかった案」だった。文書どおりに
        直した将来の担当者は、`next.config.test.ts` が黙って落ちる回帰
        (test_jest_still_collects_tests_outside_src が防ぐもの)をそのまま再導入する。
        """
        config = read("apps/web/jest.config.ts")
        # jest.config.ts が実際に設定しているキー(コメント内の言及は数えない)。
        mechanism = None
        for key in ("testMatch", "roots"):
            if re.search(r"^\s*" + key + r"\s*:", config, re.M):
                mechanism = key
                break
        self.assertIsNotNone(
            mechanism, "jest.config.ts が testMatch も roots も設定していない"
        )
        for rel_path in COUNT_DOCS:
            with self.subTest(path=rel_path, mechanism=mechanism):
                self.assertIn(
                    mechanism,
                    read(rel_path),
                    f"{rel_path} が jest の除外の仕組み {mechanism} を名指ししていない",
                )

    def test_documented_count_matches_reality(self):
        """「N か所」と書いている文書・コメントの N が、実際の除外か所数と一致する。"""
        expected = len(EXCLUSION_SITES)
        for rel_path in COUNT_DOCS:
            with self.subTest(path=rel_path):
                counts = COUNT_RE.findall(read(rel_path))
                self.assertTrue(
                    counts, f"{rel_path} に「N か所」の記述が見つからない"
                )
                self.assertIn(
                    str(expected),
                    counts,
                    f"{rel_path} が書いている除外か所数 {counts} が実際の {expected} と違う",
                )

    def test_documented_sites_name_every_exclusion_file(self):
        """文書・コメントが、除外か所の**全て**をファイル名で挙げている。

        数だけ合っていても、どのファイルを直せばよいかが書かれていなければ次の漏れを防げない。
        """
        for rel_path in COUNT_DOCS:
            text = read(rel_path)
            for site in EXCLUSION_SITES:
                basename = os.path.basename(site)
                with self.subTest(path=rel_path, site=basename):
                    self.assertIn(
                        basename,
                        text,
                        f"{rel_path} が除外か所 {basename} を挙げていない",
                    )


if __name__ == "__main__":
    unittest.main()
