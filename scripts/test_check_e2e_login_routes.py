#!/usr/bin/env python3
"""`scripts/check-e2e-login-routes.sh` の単体テスト(#1295 Requirement 3)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

このスクリプトが検査するのは `apps/web/e2e/` 配下のソースコードの静的な形——
新しいログイン送信(`#kc-login` のクリック/フォーカス、`grant_type: 'password'`)が
`e2e-login-guard:` 注釈を伴っているか——であって、製品の画面には一切現れない。
`scripts/test_check_issue_labels.py` と同じ文書化された例外
(CLAUDE.md → Test-First Implementation)として、ここでスクリプトレベルの
テストとして表現する。

実際の `apps/web/e2e/` を対象に走らせるのではなく、一時ディレクトリに最小限の
fixtureファイルを作って対象ディレクトリだけ差し替える(`CHECK_E2E_LOGIN_ROUTES_DIR`)。
本物のツリーを対象にした回帰確認は、実装コミット側で本番同様に実行して記録する。
"""

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-e2e-login-routes.sh")


def run_against(fixtures: dict) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory() as tmp:
        for relpath, content in fixtures.items():
            path = os.path.join(tmp, relpath)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        env = dict(os.environ)
        env["CHECK_E2E_LOGIN_ROUTES_DIR"] = tmp
        return subprocess.run(
            ["bash", SCRIPT], capture_output=True, text=True, env=env
        )


class KcLoginSubmission(unittest.TestCase):
    def test_unannotated_kc_login_click_is_flagged(self):
        """注釈の無い新規の #kc-login クリックは検出される(RED相当の確認)。"""
        r = run_against(
            {
                "steps/newFeature.steps.ts": (
                    "async function doLogin(page) {\n"
                    "  await page.locator('#username').fill(email);\n"
                    "  await page.locator('#password').fill(password);\n"
                    "  await page.locator('#kc-login').click();\n"
                    "}\n"
                )
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("newFeature.steps.ts", r.stdout + r.stderr)

    def test_annotated_kc_login_click_with_locked_is_ok(self):
        r = run_against(
            {
                "steps/ok.steps.ts": (
                    "async function doLogin(page) {\n"
                    "  // e2e-login-guard:locked\n"
                    "  await withAccountLock(email, async () => {\n"
                    "    await page.locator('#kc-login').click();\n"
                    "  });\n"
                    "}\n"
                )
            }
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_annotated_kc_login_focus_with_disposable_is_ok(self):
        r = run_against(
            {
                "steps/ok.steps.ts": (
                    "async function doLogin(page) {\n"
                    "  // e2e-login-guard:disposable\n"
                    "  await page.locator('#kc-login').focus();\n"
                    "}\n"
                )
            }
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_multi_selector_consent_button_is_not_flagged(self):
        """デバイス認可の同意ボタン(複数セレクタの一部としての #kc-login)は対象外。"""
        r = run_against(
            {
                "steps/deviceCode.steps.ts": (
                    "const approve = page.locator('#kc-login, input[name=\"accept\"]').first();\n"
                    "await approve.click();\n"
                )
            }
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_annotation_further_than_five_lines_away_does_not_count(self):
        lines = ["// e2e-login-guard:locked"] + ["// filler"] * 6 + [
            "await page.locator('#kc-login').click();"
        ]
        r = run_against({"steps/far.steps.ts": "\n".join(lines) + "\n"})
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)

    def test_unannotated_double_quoted_kc_login_click_is_flagged(self):
        """issue #1295 レビュー指摘1: クォートの種類を変えただけで素通りしてはならない。"""
        r = run_against(
            {
                "steps/doubleQuoted.steps.ts": (
                    "async function doLogin(page) {\n"
                    '  await page.locator("#kc-login").click();\n'
                    "}\n"
                )
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("doubleQuoted.steps.ts", r.stdout + r.stderr)

    def test_unannotated_click_shorthand_is_flagged(self):
        """issue #1295 レビュー指摘1: Playwrightの短縮形 page.click() も検出する。"""
        r = run_against(
            {
                "steps/shorthand.steps.ts": "await page.click('#kc-login');\n"
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("shorthand.steps.ts", r.stdout + r.stderr)

    def test_unannotated_double_quoted_click_shorthand_is_flagged(self):
        r = run_against(
            {
                "steps/shorthandDq.steps.ts": 'await page.click("#kc-login");\n'
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)

    def test_double_quoted_multi_selector_consent_button_is_not_flagged(self):
        """パターンを広げても、同意ボタン(複数セレクタ)は対象外のままであること。"""
        r = run_against(
            {
                "steps/deviceCodeDq.steps.ts": (
                    "const approve = page.locator(\"#kc-login, input[name='accept']\").first();\n"
                    "await approve.click();\n"
                )
            }
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_unannotated_kc_login_in_tsx_is_flagged(self):
        """issue #1295 レビュー指摘3: .tsx を黙って検査対象から外さない。"""
        r = run_against(
            {
                "helpers/LoginWidget.tsx": "await page.locator('#kc-login').click();\n"
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("LoginWidget.tsx", r.stdout + r.stderr)


class GrantTypePassword(unittest.TestCase):
    def test_unannotated_raw_password_grant_is_flagged(self):
        r = run_against(
            {
                "steps/rawGrant.steps.ts": (
                    "await request.post(url, { form: {\n"
                    "  grant_type: 'password',\n"
                    "  username: E2E_TEST_EMAIL,\n"
                    "} });\n"
                )
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("rawGrant.steps.ts", r.stdout + r.stderr)

    def test_annotated_already_disposable_is_ok(self):
        r = run_against(
            {
                "steps/disposable.steps.ts": (
                    "// e2e-login-guard:already-disposable\n"
                    "await request.post(url, { form: {\n"
                    "  grant_type: 'password',\n"
                    "} });\n"
                )
            }
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_unannotated_double_quoted_password_grant_is_flagged(self):
        """issue #1295 レビュー指摘1: grant_type のクォート違いで素通りしてはならない。"""
        r = run_against(
            {
                "steps/rawGrantDq.steps.ts": (
                    "await request.post(url, { form: {\n"
                    '  grant_type: "password",\n'
                    "} });\n"
                )
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("rawGrantDq.steps.ts", r.stdout + r.stderr)

    def test_unannotated_spaced_password_grant_is_flagged(self):
        """issue #1295 レビュー指摘1: コロン前後の空白や引用符付きキーでも検出する。"""
        r = run_against(
            {
                "steps/rawGrantSpaced.steps.ts": "  'grant_type' : 'password',\n"
            }
        )
        self.assertNotEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("rawGrantSpaced.steps.ts", r.stdout + r.stderr)


class CleanTreePasses(unittest.TestCase):
    def test_no_matches_at_all_passes(self):
        r = run_against({"steps/noop.steps.ts": "export const x = 1;\n"})
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
