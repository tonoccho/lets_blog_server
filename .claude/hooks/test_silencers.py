"""`SILENCERS`(テストを黙らせる手段のパターン)が単一の出所にあることの検証(#1055)。

`.claude/hooks/guard.py` と `scripts/git-hooks/pre-commit` は、以前はそれぞれが
`SILENCERS` を**書き写して**持っていた。片方だけを更新すれば静かにずれる構造で、
実際に `pre-commit` 側だけ `@skip` / `@fixme` パターンが欠落していた。

`paths.py` が両フックから import される唯一の分類器であるのと同じく、`SILENCERS` も
`.claude/hooks/silencers.py` を唯一の出所とし、両フックはそこから import する。

このテストは、guard.py と pre-commit(python として動的 import する)がそれぞれ
`silencers.SILENCERS` と**同一のオブジェクト**を参照していることを確認する。
どちらかが再び独自のリストを書き写せば、この等価性は崩れて検出される。
"""

import importlib.util
import os
import sys
import unittest

HOOKS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HOOKS_DIR, "..", ".."))
PRE_COMMIT_PATH = os.path.join(REPO_ROOT, "scripts", "git-hooks", "pre-commit")

sys.path.insert(0, HOOKS_DIR)


def _load_module(name, path):
    spec = importlib.util.spec_from_loader(
        name, importlib.machinery.SourceFileLoader(name, path)
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SingleSourceOfTruth(unittest.TestCase):
    def test_canonical_module_exists_and_defines_silencers(self):
        import silencers  # noqa

        self.assertTrue(hasattr(silencers, "SILENCERS"))
        self.assertGreater(len(silencers.SILENCERS), 0)

    def test_canonical_list_includes_the_skip_fixme_tag_pattern(self):
        import silencers

        labels = [label for _, label in silencers.SILENCERS]
        self.assertIn("@skip / @fixme タグ", labels)

    def test_guard_py_uses_the_canonical_silencers(self):
        import silencers

        guard = _load_module("guard_under_test", os.path.join(HOOKS_DIR, "guard.py"))
        self.assertEqual(silencers.SILENCERS, guard.SILENCERS)

    def test_pre_commit_uses_the_canonical_silencers(self):
        import silencers

        pre_commit = _load_module("pre_commit_under_test", PRE_COMMIT_PATH)
        self.assertEqual(silencers.SILENCERS, pre_commit.SILENCERS)

    def test_guard_py_and_pre_commit_reference_the_same_pattern_set(self):
        """将来の再発防止:どちらか一方だけを直しても、この一致検査が落ちる。"""
        guard = _load_module("guard_under_test2", os.path.join(HOOKS_DIR, "guard.py"))
        pre_commit = _load_module("pre_commit_under_test2", PRE_COMMIT_PATH)
        self.assertEqual(guard.SILENCERS, pre_commit.SILENCERS)


if __name__ == "__main__":
    unittest.main()
