#!/usr/bin/env python3
"""同梱 Ollama の廃止と、GPU PC の Ollama へ URL でつなぐ手順の文書(#1745)の検証。

    python3 -m unittest scripts.test_docs_external_ollama

文書と設定例は Web UI から到達できないため、Gherkin ではなく契約テストで表す
(CLAUDE.md → Test-First Implementation)。

- `docs/EXTERNAL_OLLAMA.md` に、導入・LAN 内限定の公開・モデル取得・推奨設定・URL の設定・既存環境の移行が書かれている
- `README.md` と `docs/DOCKER_COMPOSE_ARCHITECTURE.md` から同梱 Ollama の記述が無くなっている
- `.env.example` に Ollama の同梱コンテナ用の設定(`OLLAMA_*`、`GPU_RUNTIME`)が無い
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))


def read(rel):
    with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
        return f.read()


GUIDE = "docs/EXTERNAL_OLLAMA.md"

# 同梱 Ollama を前提にした語。文書と設定例に残してはならない。
BUNDLED_TOKENS = (
    "ollama-model-init",
    "ollama-cpu",
    "lbs-ollama",
    "GPU_RUNTIME",
    "OLLAMA_MEM_LIMIT",
    "OLLAMA_CPU_MEM_LIMIT",
    "OLLAMA_IMAGE",
)


class ExternalOllamaGuide(unittest.TestCase):
    def setUp(self):
        self.text = read(GUIDE)

    def test_covers_every_required_topic(self):
        required = {
            "導入": "ollama.com",
            "待ち受け": "OLLAMA_HOST=0.0.0.0:11434",
            "認証が無い": "認証",
            "LAN 内限定(ファイアウォール)": "ファイアウォール",
            "モデル取得": "ollama pull",
            "推奨 KEEP_ALIVE": "OLLAMA_KEEP_ALIVE",
            "推奨 CONTEXT_LENGTH": "OLLAMA_CONTEXT_LENGTH",
            "推奨 MAX_LOADED_MODELS": "OLLAMA_MAX_LOADED_MODELS",
            "URL の設定": "http://192.168.1.71:11434/v1",
            "システム設定の項目": "llm_ollama_base_url",
            "既存環境の移行(ボリューム)": "ollama_models",
            "ボリュームの手動削除": "docker volume rm",
        }
        missing = [name for name, needle in required.items() if needle not in self.text]
        self.assertEqual([], missing, "手順書に無い項目: %s" % missing)

    def test_recommended_values_are_the_ones_compose_used(self):
        """削除前の docker-compose.yml の値(5m / 8192 / 1)を転記している。"""
        self.assertRegex(self.text, r"OLLAMA_KEEP_ALIVE=5m")
        self.assertRegex(self.text, r"OLLAMA_CONTEXT_LENGTH=8192")
        self.assertRegex(self.text, r"OLLAMA_MAX_LOADED_MODELS=1")

    def test_default_model_is_named(self):
        self.assertIn("qwen2.5:7b-instruct", self.text)


class NoBundledOllamaInDocs(unittest.TestCase):
    def test_readme_does_not_mention_bundled_ollama_or_its_gpu_requirement(self):
        text = read("README.md")
        for token in BUNDLED_TOKENS + ("ollama_models",):
            self.assertNotIn(token, text)
        section = text.split("## ハードウェア要件", 1)[1].split("## 前提ソフトウェア要件", 1)[0]
        self.assertNotRegex(section, r"Ollama[^\n|]*GPU|GPU[^\n|]*Ollama")
        self.assertIn("docs/EXTERNAL_OLLAMA.md", text, "README から別 PC の Ollama の手順へ辿れない")

    def test_architecture_doc_has_no_bundled_ollama(self):
        text = read("docs/DOCKER_COMPOSE_ARCHITECTURE.md")
        for token in BUNDLED_TOKENS + ("ollama_models",):
            self.assertNotIn(token, text)
        self.assertIsNone(re.search(r"^\| `ollama`", text, re.M), "サービス一覧に ollama が残っている")
        self.assertIn("EXTERNAL_OLLAMA.md", text, "アーキテクチャ文書から別 PC の Ollama の手順へ辿れない")


class EnvExample(unittest.TestCase):
    def test_no_bundled_ollama_settings(self):
        text = read(".env.example")
        for token in BUNDLED_TOKENS:
            self.assertNotIn(token, text)
        self.assertIsNone(re.search(r"(?m)^\s*#?\s*OLLAMA_[A-Z_]+=", text), "OLLAMA_* が .env.example に残っている")

    def test_default_model_name_is_kept(self):
        self.assertRegex(read(".env.example"), r"(?m)^LLM_OLLAMA_MODEL=")


if __name__ == "__main__":
    unittest.main()
