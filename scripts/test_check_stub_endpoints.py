#!/usr/bin/env python3
"""`scripts/check-stub-endpoints.py` の単体テスト(#1703)。

## なぜ Gherkin ではないのか

対象は「DB(`lbs_platform.system_settings`)の `llm_ollama_base_url` / `comfyui_base_url` が
スタブを向いているか」という、受け入れテストの開始前提である。Web UI から観測できる振る舞いではなく、
`scripts/test_check_stub_overlay.py`(#1683)と同じ文書化された例外として、DB へ問い合わせる
`_query_settings` を差し替えて判定ロジックだけを検証する。暗号化は本物
(`scripts/e2e_encrypt_setting.py`)を使い、投入側と検査側の形式が食い違わないことも確かめる。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import base64
import importlib.util
import io
import os
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-stub-endpoints.py")

_spec = importlib.util.spec_from_file_location("check_stub_endpoints", SCRIPT)
cse = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cse)

_enc_spec = importlib.util.spec_from_file_location(
    "e2e_encrypt_setting", os.path.join(HERE, "e2e_encrypt_setting.py")
)
enc = importlib.util.module_from_spec(_enc_spec)
_enc_spec.loader.exec_module(enc)

KEY = base64.b64encode(b"k" * 32).decode()
OTHER_KEY = base64.b64encode(b"z" * 32).decode()
LLM_STUB = "http://llm-stub:8080"
COMFY_STUB = "http://comfyui-stub:8080"


def rows(llm=LLM_STUB, comfy=COMFY_STUB, key=KEY):
    """DB の行(キー → 暗号化した値の16進)。None は行が無いことを表す。"""
    out = {}
    if llm is not None:
        out["llm_ollama_base_url"] = enc.encrypt_hex(key, llm)
    if comfy is not None:
        out["comfyui_base_url"] = enc.encrypt_hex(key, comfy)
    return out


def patched(settings, key=KEY):
    return (
        mock.patch.object(cse, "_query_settings", lambda: settings),
        mock.patch.object(cse, "_app_encryption_key", lambda: key),
    )


def run_check(settings, key=KEY, env=None):
    p1, p2 = patched(settings, key)
    with p1, p2, mock.patch.dict(os.environ, env or {}, clear=False):
        os.environ.pop(cse.BYPASS_ENV, None) if not env else None
        return cse.check()


class FindMismatches(unittest.TestCase):
    def test_both_pointing_at_stubs_is_clean(self):
        p1, p2 = patched(rows())
        with p1, p2:
            self.assertEqual(cse.find_mismatches(), [])

    def test_production_default_is_reported_with_the_actual_value(self):
        p1, p2 = patched(rows(llm="http://ollama:11434/v1"))
        with p1, p2:
            self.assertEqual(
                cse.find_mismatches(),
                [("llm_ollama_base_url", "http://ollama:11434/v1", LLM_STUB)],
            )

    def test_comfyui_default_is_reported(self):
        p1, p2 = patched(rows(comfy="http://comfyui:8188"))
        with p1, p2:
            self.assertEqual(
                cse.find_mismatches(),
                [("comfyui_base_url", "http://comfyui:8188", COMFY_STUB)],
            )

    def test_missing_row_is_a_mismatch(self):
        p1, p2 = patched(rows(llm=None, comfy=None))
        with p1, p2:
            self.assertEqual(
                cse.find_mismatches(),
                [
                    ("llm_ollama_base_url", None, LLM_STUB),
                    ("comfyui_base_url", None, COMFY_STUB),
                ],
            )

    def test_value_encrypted_with_another_key_is_a_mismatch_not_a_crash(self):
        p1, p2 = patched(rows(key=OTHER_KEY))
        with p1, p2:
            got = cse.find_mismatches()
        self.assertEqual([m[0] for m in got], ["llm_ollama_base_url", "comfyui_base_url"])
        self.assertTrue(all(m[1] == cse.UNDECRYPTABLE for m in got))

    def test_trailing_slash_is_not_the_stub_url(self):
        # 厳密一致: スタブ投入の値と同じ形でなければ、投入されていないと見なす。
        p1, p2 = patched(rows(llm=LLM_STUB + "/"))
        with p1, p2:
            self.assertEqual([m[0] for m in cse.find_mismatches()], ["llm_ollama_base_url"])


class Check(unittest.TestCase):
    def setUp(self):
        self._env = mock.patch.dict(os.environ, {}, clear=False)
        self._env.start()
        os.environ.pop(cse.BYPASS_ENV, None)
        self.addCleanup(self._env.stop)

    def test_ok_when_pointing_at_stubs(self):
        ok, message = run_check(rows())
        self.assertTrue(ok)
        self.assertIn("スタブ", message)

    def test_refuses_with_cause_and_fix_command(self):
        ok, message = run_check(rows(llm="http://ollama:11434/v1", comfy=None))
        self.assertFalse(ok)
        self.assertIn("#1703", message)
        self.assertIn("llm_ollama_base_url", message)
        self.assertIn("http://ollama:11434/v1", message)
        self.assertIn("comfyui_base_url", message)
        self.assertIn("./scripts/e2e-clear-llm-db-overrides.sh --yes", message)
        self.assertIn(cse.BYPASS_ENV + "=1", message)

    def test_bypass_continues_and_records_it(self):
        os.environ[cse.BYPASS_ENV] = "1"
        ok, message = run_check(rows(llm="http://ollama:11434/v1"), env={cse.BYPASS_ENV: "1"})
        self.assertTrue(ok)
        self.assertIn("迂回", message)
        self.assertIn("llm_ollama_base_url", message)

    def test_bypass_with_nothing_to_bypass_says_so(self):
        os.environ[cse.BYPASS_ENV] = "1"
        ok, message = run_check(rows(), env={cse.BYPASS_ENV: "1"})
        self.assertTrue(ok)
        self.assertIn(cse.BYPASS_ENV, message)

    def test_bypass_value_other_than_one_does_not_bypass(self):
        os.environ[cse.BYPASS_ENV] = "0"
        ok, _ = run_check(rows(llm="http://ollama:11434/v1"), env={cse.BYPASS_ENV: "0"})
        self.assertFalse(ok)

    def test_skips_when_the_database_cannot_be_queried(self):
        def boom():
            raise cse.DatabaseUnavailable("no container")

        with mock.patch.object(cse, "_query_settings", boom), mock.patch.object(
            cse, "_app_encryption_key", lambda: KEY
        ):
            ok, message = cse.check()
        self.assertTrue(ok)
        self.assertIn("省略", message)


class Queries(unittest.TestCase):
    def test_query_settings_parses_key_and_hex(self):
        out = "llm_ollama_base_url\tAB12\ncomfyui_base_url\tCD34\n\n"
        with mock.patch.object(cse, "_docker", lambda args: (0, out, "")):
            self.assertEqual(
                cse._query_settings(),
                {"llm_ollama_base_url": "AB12", "comfyui_base_url": "CD34"},
            )

    def test_query_settings_raises_when_docker_fails(self):
        with mock.patch.object(cse, "_docker", lambda args: (1, "", "no such container")):
            with self.assertRaises(cse.DatabaseUnavailable):
                cse._query_settings()

    def test_query_settings_raises_when_root_password_is_unknown(self):
        with mock.patch.object(cse, "_env_value", lambda name: ""):
            with self.assertRaises(cse.DatabaseUnavailable):
                cse._query_settings()

    def test_env_value_reads_the_dotenv_file(self):
        import tempfile

        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("A=1\nAPP_ENCRYPTION_KEY=abc=\nB=2\n")
        self.addCleanup(os.remove, f.name)
        with mock.patch.object(cse, "ENV_FILE", f.name):
            self.assertEqual(cse._env_value("APP_ENCRYPTION_KEY"), "abc=")
            self.assertEqual(cse._env_value("MISSING"), "")

    def test_env_value_is_empty_without_a_dotenv_file(self):
        with mock.patch.object(cse, "ENV_FILE", "/nonexistent/.env"):
            self.assertEqual(cse._env_value("A"), "")

    def test_app_encryption_key_missing_is_unavailable(self):
        with mock.patch.object(cse, "_env_value", lambda name: ""):
            with self.assertRaises(cse.DatabaseUnavailable):
                cse._app_encryption_key()

    def test_docker_wrapper_reports_failure_when_docker_is_absent(self):
        with mock.patch.object(cse.subprocess, "run", side_effect=OSError("no docker")):
            code, _, err = cse._docker(["ps"])
        self.assertEqual(code, 1)
        self.assertIn("no docker", err)

    def test_docker_wrapper_returns_the_process_result(self):
        class R:
            returncode = 0
            stdout = "x"
            stderr = ""

        with mock.patch.object(cse.subprocess, "run", return_value=R()):
            self.assertEqual(cse._docker(["ps"]), (0, "x", ""))


class Main(unittest.TestCase):
    def setUp(self):
        self._env = mock.patch.dict(os.environ, {}, clear=False)
        self._env.start()
        os.environ.pop(cse.BYPASS_ENV, None)
        self.addCleanup(self._env.stop)

    def _run(self, argv, settings):
        p1, p2 = patched(settings)
        out, err = io.StringIO(), io.StringIO()
        with p1, p2, redirect_stdout(out), redirect_stderr(err):
            code = cse.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_exit_zero_when_pointing_at_stubs(self):
        code, out, _ = self._run(["check-stub-endpoints.py"], rows())
        self.assertEqual(code, 0)
        self.assertIn("スタブ", out)

    def test_exit_one_and_message_on_stderr_when_not(self):
        code, _, err = self._run(["check-stub-endpoints.py"], rows(llm="http://ollama:11434/v1"))
        self.assertEqual(code, 1)
        self.assertIn("e2e-clear-llm-db-overrides.sh --yes", err)

    def test_usage_error(self):
        code, _, err = self._run(["check-stub-endpoints.py", "extra"], rows())
        self.assertEqual(code, 2)
        self.assertIn("使い方", err)


class GlobalSetupIsWired(unittest.TestCase):
    """global-setup.ts は Playwright の起動前フックで実機の docker を要するため、配線を文面で確かめる。"""

    def setUp(self):
        with open(os.path.join(HERE, "..", "apps", "web", "e2e", "global-setup.ts"), encoding="utf-8") as f:
            self.text = f.read()

    def test_calls_the_check_after_the_overlay_check_and_the_health_wait(self):
        call = self.text.find("'check-stub-endpoints.py'")
        self.assertGreater(call, 0, "global-setup.ts が check-stub-endpoints.py を呼んでいない")
        self.assertGreater(call, self.text.find("'check-stub-overlay.py'"))
        self.assertGreater(call, self.text.find("waitForServicesHealthy(undefined"))

    def test_documents_the_bypass_variable(self):
        self.assertIn(cse.BYPASS_ENV, self.text)

    def test_refuses_to_start_when_the_check_fails(self):
        i = self.text.find("'check-stub-endpoints.py'")
        self.assertIn("throw new Error", self.text[i : i + 900])


if __name__ == "__main__":
    unittest.main()
