#!/usr/bin/env python3
"""バージョン管理下のテキストソースに生の NUL バイトが無いことの検査(#1496)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

対象はソースファイルのバイト表現であり、製品の画面から観測できる振る舞いではない。
`CLAUDE.md` → Test-First Implementation が認める、Web UI から到達できない基準の
スクリプトレベルのテストとして、`scripts/test_git_hooks_binding.py` と同じ流儀で表現する。

## なぜ検査するのか

生の NUL を含むソースは GNU grep に「バイナリ」と判定され、一致0件・警告なしで
読み飛ばされる。git は先頭8000バイトしか見ないので気付けない。
Java の char リテラルは `'\\0'` と書くこと。
"""

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

TEXT_EXTENSIONS = (
    ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".sql", ".py",
    ".md", ".feature", ".sh", ".json", ".yml", ".yaml", ".xml", ".properties",
    ".css", ".html", ".gradle", ".toml", ".txt",
)


def find_files_with_nul(paths, root):
    found = []
    for rel in paths:
        if not rel.endswith(TEXT_EXTENSIONS):
            continue
        full = os.path.join(root, rel)
        if not os.path.isfile(full):
            continue
        with open(full, "rb") as f:
            if b"\x00" in f.read():
                found.append(rel)
    return found


def tracked_files():
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO_ROOT, check=True, capture_output=True
    ).stdout
    return [p.decode("utf-8") for p in out.split(b"\x00") if p]


class NoRawNulInSources(unittest.TestCase):
    def test_tracked_text_sources_have_no_raw_nul(self):
        offenders = find_files_with_nul(tracked_files(), REPO_ROOT)
        self.assertEqual(
            [], offenders,
            "生の NUL バイトを含むテキストソース(Java は '\\0' と書く): %s" % offenders,
        )

    def test_detects_a_file_containing_nul(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "Bad.java"), "wb") as f:
                f.write(b"char c = '\x00';\n")
            with open(os.path.join(tmp, "Good.java"), "wb") as f:
                f.write(b"char c = '\\0';\n")
            with open(os.path.join(tmp, "image.png"), "wb") as f:
                f.write(b"\x89PNG\x00\x00")
            found = find_files_with_nul(["Bad.java", "Good.java", "image.png", "gone.java"], tmp)
        self.assertEqual(["Bad.java"], found)


if __name__ == "__main__":
    unittest.main()
