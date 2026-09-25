#!/usr/bin/env python3
"""Playwright.create() が実行時に Firefox/WebKit を CDN からダウンロードしないことを検査する(#1048)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

media / content 両サービスの Dockerfile は chromium だけを焼き込んでいる
(`java -cp "runtime-libs/*" com.microsoft.playwright.CLI install --with-deps chromium`)。
しかし `PlaywrightConfig#playwright` の `Playwright.create()` は、アプリが実際に使う
chromium だけでなく firefox/webkit の存在も検査し、無ければ CDN からダウンロードする。
#1020/#1046 で `Browser`/`Playwright` の注入点が `@Lazy` になった結果、このダウンロードは
**起動時ではなく最初のユーザーリクエスト時**に走るようになり、12.5秒の初回レイテンシと、
外部ネットワークが無い環境での失敗を招く(#1048 Problem)。

`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` を実行時環境変数として設定すると、
`Playwright.create()` は未導入のブラウザをダウンロードせず、既に `ms-playwright` に
存在する chromium のパス解決はそのまま機能する。

## なぜ Docker/実コンテナ検証ではなく Dockerfile の静的検査なのか

このIssueの変更はプロダクションJavaコードではなく、Dockerfileのビルド設定そのもの
(`scripts/check-changed-coverage.py` の `MEASURABLE_PATTERNS` は `services/*/src/` 配下しか
計測しない。Dockerfile はそもそも計測対象外)。JaCoCo/jestが到達しないため、
`docs/COVERAGE_TARGETS.md` と同じ「計測不能なプロダクションコードは受け入れテスト相当で
検証する」方針に倣い、ここでは Dockerfile の内容そのものを固定する静的テストとする
(`test_compose_healthcheck_cwd.py` と同じ流儀)。実コンテナでのダウンロード有無・
初回応答時間・キャッシュ内容の確認は、本テストではなく実装報告に記載する手動/受け入れ検証
の対象とする(コンテナの再作成・時間計測・ネットワーク遮断を伴い、ユニットテストの実行環境
では再現できないため)。

## なぜ「chromium install の後」であることまで検査するのか

`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` をビルドステージの chromium install より**前**に
置くと、その install 自体がダウンロードをスキップしてしまい、イメージに chromium が
焼き込まれなくなる(#1048 Requirement「chromiumのパス解決は従来どおり効くこと」を壊す)。
実行時ステージ(2番目の `FROM`)の中で、かつ install の**後**に置かれていることを検査する。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

DOCKERFILES = (
    "services/media/Dockerfile",
    "services/content/Dockerfile",
)

ENV_LINE_RE = re.compile(r"^ENV\s+PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1\s*$", re.M)
CHROMIUM_INSTALL_RE = re.compile(
    r"CLI install --with-deps chromium"
)
RUNTIME_STAGE_FROM_RE = re.compile(r"^FROM\s+eclipse-temurin:21-jre\b", re.M)


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


class RuntimeBrowserDownloadIsSkipped(unittest.TestCase):
    def test_dockerfile_sets_skip_browser_download_env(self):
        """media/content の Dockerfile が実行時に PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 を持つ。"""
        for rel_path in DOCKERFILES:
            with self.subTest(dockerfile=rel_path):
                text = read(rel_path)
                self.assertRegex(
                    text,
                    ENV_LINE_RE,
                    f"{rel_path} に `ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` が無い。"
                    "実行時に Playwright.create() が未使用の Firefox/WebKit を"
                    "CDNからダウンロードしてしまう(#1048)",
                )

    def test_skip_env_is_in_runtime_stage_after_chromium_install(self):
        """ダウンロード抑止が、実行時ステージの chromium install より後に置かれている。

        chromium install より前に置くと、ビルド時の chromium 導入自体が抑止されてしまい、
        イメージに chromium が焼き込まれなくなる(#1048 Requirement)。
        """
        for rel_path in DOCKERFILES:
            with self.subTest(dockerfile=rel_path):
                text = read(rel_path)

                runtime_stage_match = RUNTIME_STAGE_FROM_RE.search(text)
                self.assertIsNotNone(
                    runtime_stage_match,
                    f"{rel_path} に実行時ステージ(`FROM eclipse-temurin:21-jre`)が無い",
                )
                runtime_stage_text = text[runtime_stage_match.start():]

                install_match = CHROMIUM_INSTALL_RE.search(runtime_stage_text)
                self.assertIsNotNone(
                    install_match,
                    f"{rel_path} の実行時ステージに chromium の install コマンドが無い",
                )

                env_match = ENV_LINE_RE.search(runtime_stage_text)
                self.assertIsNotNone(
                    env_match,
                    f"{rel_path} の実行時ステージに"
                    " `ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` が無い(#1048)",
                )

                self.assertGreater(
                    env_match.start(),
                    install_match.start(),
                    f"{rel_path} で PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 が"
                    " chromium install より前に置かれている。"
                    "ビルド時の chromium 導入自体がスキップされてしまう(#1048)",
                )


if __name__ == "__main__":
    unittest.main()
