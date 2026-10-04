#!/usr/bin/env python3
"""`.env` の SAFETY_NEGATIVE_PROMPT_* が media サービスへ渡ることの検証(#1605)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象は `docker-compose.yml` の media サービスの `environment:` であり、
`docker compose config` の出力でしか観測できない運用設定である。Web UI からは
到達できないため、`CLAUDE.md` → Test-First Implementation が認める
サービス/スクリプトレベルのテストで表現する(`test_comfyui_gpu_profile.py` と同じ理由)。

未設定時は application.yml の既定値を効かせる必要がある。compose の `${VAR}` は
未設定を空文字にして既定値を潰すため、`${VAR-既定値}`(コロン無し: 未設定のときだけ既定値)
で渡す。空文字を明示したときは空文字のまま渡り、連結されない(SafetyNegativePromptService)。
"""

import os
import re
import subprocess
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")
APPLICATION_YML = os.path.join(
    REPO_ROOT, "services/media/src/main/resources/application.yml"
)

CATEGORIES = ("SEXUAL", "VIOLENT", "DISCRIMINATORY")
KEYS = tuple("SAFETY_NEGATIVE_PROMPT_" + c for c in CATEGORIES)


def application_yml_defaults():
    """application.yml の `${SAFETY_NEGATIVE_PROMPT_X:既定値}` から既定値を読む。"""
    with open(APPLICATION_YML, encoding="utf-8") as f:
        text = f.read()
    return {
        k: re.search(r"\$\{" + k + r":([^}]*)\}", text).group(1) for k in KEYS
    }


class MediaSafetyNegativePromptEnv(unittest.TestCase):
    def media_env(self, env_lines):
        with tempfile.TemporaryDirectory(prefix="safety-neg-") as tmp:
            env_file = os.path.join(tmp, ".env")
            with open(ENV_EXAMPLE, encoding="utf-8") as src:
                kept = [
                    ln for ln in src.read().splitlines()
                    if not ln.startswith(KEYS)
                ]
            with open(env_file, "w", encoding="utf-8") as dst:
                dst.write("\n".join(kept + env_lines) + "\n")
            env = dict(os.environ)
            env.pop("COMPOSE_PROFILES", None)
            for k in KEYS:
                env.pop(k, None)
            r = subprocess.run(
                ["docker", "compose", "--env-file", env_file, "-f", COMPOSE, "config"],
                cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env,
            )
            self.assertEqual(0, r.returncode, r.stdout + r.stderr)
            return yaml.safe_load(r.stdout)["services"]["media"]["environment"]

    def test_value_in_dotenv_reaches_media(self):
        """AC1: .env の値が media の環境変数に出る。"""
        env = self.media_env(['SAFETY_NEGATIVE_PROMPT_SEXUAL="custom, words"'])
        self.assertEqual("custom, words", env.get("SAFETY_NEGATIVE_PROMPT_SEXUAL"))

    def test_unset_falls_back_to_application_yml_default(self):
        """AC2: 未設定なら application.yml の既定値と同じ値になる(従来どおり)。"""
        env = self.media_env([])
        for k, default in application_yml_defaults().items():
            self.assertEqual(default, env.get(k), k)

    def test_empty_value_is_passed_through_as_empty(self):
        """AC3: 空文字は既定値に戻らず空文字のまま渡る(連結されない)。"""
        env = self.media_env(["SAFETY_NEGATIVE_PROMPT_VIOLENT="])
        self.assertEqual("", env.get("SAFETY_NEGATIVE_PROMPT_VIOLENT"))
        self.assertNotEqual("", env.get("SAFETY_NEGATIVE_PROMPT_SEXUAL"))


if __name__ == "__main__":
    unittest.main()
