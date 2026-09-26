#!/usr/bin/env python3
"""Ollama の演算デバイス選択が意図的な設定項目であることの検証(#1396)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

受入基準は `docker compose config` の解決結果と `.env.example` / 文書の記述であり、
Web UI から到達できない。`CLAUDE.md` → Test-First Implementation が認める
「Web UI から到達できない基準は、その旨を明示してサービスレベルのテストで表現する」に当たる。
`docker compose config` はホストの GPU 有無を見ないので、GPU もイメージの pull も要らない。
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")
ARCH_DOC = os.path.join(REPO_ROOT, "docs", "DOCKER_COMPOSE_ARCHITECTURE.md")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def env_example_comment_above(name):
    """`.env.example` で `name=` の行の直上に連なるコメントブロックを返す。"""
    lines = read(ENV_EXAMPLE).splitlines()
    idx = next(i for i, l in enumerate(lines) if l.startswith(name + "="))
    block = []
    for line in reversed(lines[:idx]):
        if not line.startswith("#"):
            break
        block.append(line)
    return "\n".join(reversed(block))


def doc_section(heading_fragment):
    text = read(ARCH_DOC)
    m = re.search(r"^## [^\n]*" + re.escape(heading_fragment) + r"[^\n]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return m.group(1) if m else ""


class ComposeHarness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ollama-device-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.env_file = os.path.join(self.tmp, ".env")
        shutil.copy(ENV_EXAMPLE, self.env_file)

    def compose(self, gpu_runtime, *args):
        env = dict(os.environ)
        env.pop("COMPOSE_PROFILES", None)
        env["GPU_RUNTIME"] = gpu_runtime
        r = subprocess.run(
            ["docker", "compose", "--env-file", self.env_file, "-f", COMPOSE, "config", *args],
            cwd=REPO_ROOT, capture_output=True, text=True, timeout=60, env=env,
        )
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return r.stdout


class EnvExampleExplainsDeviceChoice(unittest.TestCase):
    """AC1: GPU / CPU を選ぶ理由がコメントから読み取れる。"""

    def test_comment_covers_speed_priority_gpu(self):
        c = env_example_comment_above("GPU_RUNTIME")
        self.assertIn("nvidia", c)
        self.assertIn("速度優先", c)

    def test_comment_covers_large_model_priority_cpu(self):
        c = env_example_comment_above("GPU_RUNTIME")
        self.assertIn("大規模モデル優先", c)
        self.assertIn("システムRAM", c)

    def test_comment_keeps_startup_compatibility_purpose(self):
        c = env_example_comment_above("GPU_RUNTIME")
        self.assertIn("GPUを持たないホスト", c)

    def test_comment_points_to_procedure_doc(self):
        self.assertIn("DOCKER_COMPOSE_ARCHITECTURE.md", env_example_comment_above("GPU_RUNTIME"))


class CpuSelection(ComposeHarness):
    """AC2: CPU を選ぶと ollama に nvidia ランタイムが指定されない。"""

    def test_ollama_has_no_nvidia_runtime(self):
        cfg = yaml.safe_load(self.compose(""))
        self.assertNotEqual("nvidia", cfg["services"]["ollama"].get("runtime"))
        self.assertIn("ollama", cfg["services"])


class GpuSelection(ComposeHarness):
    """AC3: GPU を選ぶと nvidia ランタイムが指定され、既定の起動対象に含まれる。"""

    def test_ollama_uses_nvidia_runtime(self):
        cfg = yaml.safe_load(self.compose("nvidia"))
        self.assertEqual("nvidia", cfg["services"]["ollama"]["runtime"])

    def test_ollama_and_model_init_are_default_services(self):
        services = self.compose("nvidia", "--services").split()
        self.assertIn("ollama", services)
        self.assertIn("ollama-model-init", services)

    def test_env_example_default_is_gpu(self):
        self.assertRegex(read(ENV_EXAMPLE), r"(?m)^GPU_RUNTIME=nvidia$")


class ArchitectureDocHasCpuLargeModelProcedure(unittest.TestCase):
    """AC4: VRAM に載らないモデルを CPU で動かす手順が文書にある。"""

    def setUp(self):
        self.section = doc_section("CPU")
        self.assertNotEqual("", self.section, "CPU 実行の節が無い")

    def test_section_covers_switching_procedure(self):
        for word in ("GPU_RUNTIME", "LLM_OLLAMA_MODEL", "ollama-model-init", "docker compose up -d"):
            self.assertIn(word, self.section)

    def test_section_covers_time_and_disk(self):
        self.assertIn("GB", self.section)
        self.assertIn("所要時間", self.section)

    def test_section_covers_latency_and_timeout(self):
        self.assertIn("LLM_REQUEST_TIMEOUT_SECONDS", self.section)

    def test_section_covers_gpu_assumed_defaults(self):
        for word in ("OLLAMA_KEEP_ALIVE", "OLLAMA_MAX_LOADED_MODELS", "OLLAMA_CONTEXT_LENGTH"):
            self.assertIn(word, self.section)

    def test_section_covers_how_to_verify_device(self):
        self.assertIn("ollama ps", self.section)


if __name__ == "__main__":
    unittest.main()
