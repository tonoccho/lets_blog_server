#!/usr/bin/env python3
"""Playwright ブラウザの導入手順が、文書化された手順だけで完結することを検証する(#1045)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

#1045 の症状は「受け入れテストが1本目で落ち、残り 72 本が did not run」だった。原因は
ホストに Playwright のブラウザ(または実行に必要な OS 共有ライブラリ)が無いことで、
製品にもテストにも欠陥は無い。にもかかわらず、この状態は**文書のどこにも書かれていない
暗黙の手作業**(`npx playwright install` を各自が打つ)に依存していたため、新規クローンの
開発者は同じ穴に落ちる。

導入手順は3か所に散る:

  1. `apps/web/package.json` の scripts  — 実行の入口
  2. `apps/web/e2e/browser-prerequisite.ts` — 未導入を検知したときに**利用者へ示す**コマンド
  3. README / docs                        — 新規クローンが読む手順

**3つがずれても誰も落ちない。** 前提確認の文面だけ直して文書が古いままでも、文書だけ直して
npm script が無くても、テストは緑のままである。ずれた瞬間に落とすためにここで固定する。

## sudo を要する手順を自動実行しない、という約束も検査する

共有ライブラリの導入は root 権限を要する(#1045 Requirement 4)。`playwright install
--with-deps` はこれを内部で apt に投げるため、npm script がこのフラグを持っていないことを
検査する。導入コマンドは「文書に明記して、利用者が自分で打つ」ものに留める。
"""

import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

WEB_PACKAGE_JSON = "apps/web/package.json"
PREREQUISITE_TS = "apps/web/e2e/browser-prerequisite.ts"

#: ブラウザ本体を入れる唯一の入口。文書はこれを案内し、他の打ち方を案内しない。
INSTALL_SCRIPT_NAME = "playwright:install"
INSTALL_COMMAND = f"npm run {INSTALL_SCRIPT_NAME}"

#: root 権限を要する側。自動実行せず、文書とエラーメッセージに正確に書く。
INSTALL_DEPS_COMMAND = "sudo npx playwright install-deps"

#: ブラウザ導入に触れる文書。ここに挙げた全てが同じ入口を案内していなければならない。
SETUP_DOCS = (
    "README.md",
    "docs/ACCEPTANCE_TESTING.md",
    "docs/e2e-testing.md",
    "docs/TEST_DOCUMENTATION.md",
)

#: 導入方針の理由(#1045 Requirement 4 / AC4)を記録する文書。
POLICY_DOC = "docs/e2e-testing.md"

#: 素の `npx playwright install`(= 暗黙の手作業)。`install-deps` は別物なので除く。
BARE_INSTALL_RE = re.compile(r"npx playwright install(?!-deps)")


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


def web_scripts():
    return json.loads(read(WEB_PACKAGE_JSON)).get("scripts", {})


def apt_packages_from_source():
    """`browser-prerequisite.ts` の `CHROMIUM_APT_PACKAGES` を文字列のリストで返す。

    前提確認のエラーメッセージが示す apt パッケージ一覧が唯一の正であり、文書はそれを
    書き写している。書き写しがずれていないことを下の test が検査する。
    """
    text = read(PREREQUISITE_TS)
    m = re.search(r"CHROMIUM_APT_PACKAGES\s*=\s*\[(.*?)\]", text, re.S)
    if m is None:
        return None
    return re.findall(r"['\"]([^'\"]+)['\"]", m.group(1))


class PlaywrightInstallEntryPoint(unittest.TestCase):
    def test_package_json_has_install_script(self):
        """ブラウザ導入が npm script として存在する(手で `npx playwright install` を打たせない)。"""
        scripts = web_scripts()
        self.assertIn(
            INSTALL_SCRIPT_NAME,
            scripts,
            f"{WEB_PACKAGE_JSON} に {INSTALL_SCRIPT_NAME} が無い。"
            "導入が暗黙の手作業のままになる(#1045 AC3)",
        )
        self.assertIn("playwright install", scripts[INSTALL_SCRIPT_NAME])

    def test_install_script_does_not_pull_in_sudo(self):
        """`--with-deps` は内部で apt を root 実行する。自動実行しない(#1045 Requirement 4)。"""
        scripts = web_scripts()
        self.assertIn(INSTALL_SCRIPT_NAME, scripts)
        command = scripts[INSTALL_SCRIPT_NAME]
        self.assertNotIn(
            "--with-deps",
            command,
            "playwright install --with-deps は OS パッケージの導入を root で自動実行する。"
            "root を要する手順は文書に明記して利用者に委ねること(#1045 Requirement 4)",
        )
        self.assertNotIn("sudo", command)

    def test_install_script_covers_every_browser_the_config_declares(self):
        """`playwright.config.ts` が宣言する全ブラウザを過不足なく入れる(#1045 Requirement 1)。

        受け入れテストの段階プロジェクトは chromium だけだが、`CROSS_BROWSER_SPECS` 用に
        firefox / webkit のプロジェクトも宣言されている。ブラウザを絞った引数を付けると
        `npm run test:e2e` が別の形で落ちる。
        """
        scripts = web_scripts()
        self.assertIn(INSTALL_SCRIPT_NAME, scripts)
        command = scripts[INSTALL_SCRIPT_NAME]
        self.assertIn("playwright install", command)
        arguments = command.split("playwright install", 1)[-1].strip()
        self.assertEqual(
            "",
            arguments,
            "playwright install にブラウザを絞る引数が付いている。"
            f"playwright.config.ts は firefox / webkit も宣言している(残り引数: {arguments!r})",
        )


class DocumentedSetupProcedure(unittest.TestCase):
    def test_every_setup_doc_names_the_npm_script(self):
        """ブラウザ導入に触れる文書が、全て同じ入口を案内している。"""
        for rel_path in SETUP_DOCS:
            with self.subTest(path=rel_path):
                self.assertIn(
                    INSTALL_COMMAND,
                    read(rel_path),
                    f"{rel_path} が {INSTALL_COMMAND} を案内していない",
                )

    def test_no_doc_still_instructs_the_bare_command(self):
        """素の `npx playwright install` が手順として残っていない(#1045 AC3)。

        残っていると、共有ライブラリの不足という**もう一段深い**状態に触れないまま
        「入れたのに動かない」で止まる。#1045 の実測がまさにこれだった。
        """
        for rel_path in SETUP_DOCS:
            with self.subTest(path=rel_path):
                found = BARE_INSTALL_RE.findall(read(rel_path))
                self.assertEqual(
                    [],
                    found,
                    f"{rel_path} に素の `npx playwright install` が残っている。"
                    f"{INSTALL_COMMAND} に置き換えること",
                )

    def test_sudo_step_is_documented_with_the_exact_command(self):
        """root 権限を要する手順が、正確なコマンド付きで書かれている(#1045 Requirement 4)。"""
        text = read(POLICY_DOC)
        self.assertIn(
            INSTALL_DEPS_COMMAND,
            text,
            f"{POLICY_DOC} に {INSTALL_DEPS_COMMAND} が無い",
        )

    def test_apt_fallback_matches_the_prerequisite_check(self):
        """文書の apt パッケージ一覧が、前提確認が示す一覧と一致する。

        `install-deps` が対応しないディストリビューション向けの逃げ道として apt の一覧を
        書いているが、書き写しなので放っておくとずれる。唯一の正は
        `browser-prerequisite.ts` の `CHROMIUM_APT_PACKAGES` である。
        """
        packages = apt_packages_from_source()
        self.assertIsNotNone(
            packages, f"{PREREQUISITE_TS} に CHROMIUM_APT_PACKAGES が無い"
        )
        self.assertTrue(packages, "CHROMIUM_APT_PACKAGES が空")
        text = read(POLICY_DOC)
        for pkg in packages:
            with self.subTest(package=pkg):
                self.assertIn(
                    pkg, text, f"{POLICY_DOC} が apt パッケージ {pkg} を挙げていない"
                )

    def test_install_policy_is_recorded_with_reasons(self):
        """導入方針が理由付きで残っている(#1045 AC4)。

        判断したこと自体は実装から読み取れない。「なぜバージョンを別途固定しないのか」
        「なぜ install-deps を第一手にするのか」を、後から読む人が辿れる形で残す。
        """
        text = read(POLICY_DOC)
        for marker in ("バージョン固定", "install-deps", "@playwright/test"):
            with self.subTest(marker=marker):
                self.assertIn(
                    marker,
                    text,
                    f"{POLICY_DOC} が導入方針の論点 {marker} に触れていない(#1045 AC4)",
                )

    def test_escape_hatch_for_browserless_hosts_is_documented(self):
        """ブラウザを起動できないホストで `@api` シナリオだけ回す逃げ道が文書化されている。

        前提確認は global-setup で落ちるため、放っておくとブラウザを使わないシナリオまで
        道連れになる。既存の `E2E_SKIP_HEALTH_WAIT` と同じ形の環境変数を用意し、
        存在と用途を文書に残す。
        """
        self.assertIn("E2E_SKIP_BROWSER_CHECK", read(POLICY_DOC))


if __name__ == "__main__":
    unittest.main()
