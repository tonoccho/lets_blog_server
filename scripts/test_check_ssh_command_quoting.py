#!/usr/bin/env python3
"""`scripts/check-ssh-command-quoting.py` の単体テスト(issue #1416 Requirement 3)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

この検査器が見るのは Java ソースコードの静的な形——SSH経由のwp-cliコマンド文字列へ
埋め込む動的な値が `ShellQuote.single(...)` を通っているか——であって、製品の画面には
一切現れない。`scripts/test_check_e2e_login_routes.py` と同じ文書化された例外
(CLAUDE.md → Test-First Implementation)として、スクリプトレベルのテストで表現する。

本物の `WordPressSshOperations.java` を対象にした確認は、実装コミット側で記録する。
ここでは一時ファイルの fixture を `CHECK_SSH_COMMAND_QUOTING_FILE` で差し替える。
"""

import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-ssh-command-quoting.py")


def run_on(source: str):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "WordPressSshOperations.java")
        with open(path, "w", encoding="utf-8") as f:
            f.write(source)
        env = dict(os.environ)
        env["CHECK_SSH_COMMAND_QUOTING_FILE"] = path
        return subprocess.run(
            [sys.executable, SCRIPT], capture_output=True, text=True, env=env
        )


class DetectsUnquotedValues(unittest.TestCase):
    def test_unquoted_post_id_is_reported(self):
        r = run_on('exec(creds, wpCli(creds, "post delete " + postId));\n')
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("postId", r.stderr)

    def test_quoted_post_id_passes(self):
        r = run_on('exec(creds, wpCli(creds, "post delete " + ShellQuote.single(postId)));\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_mixed_line_reports_only_the_unquoted_one(self):
        r = run_on(
            'exec(creds, wpCli(creds, "post meta update " + postId'
            ' + " _thumbnail_id " + ShellQuote.single(mediaId)));\n'
        )
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("postId", r.stderr)
        self.assertNotIn("ShellQuote.single(mediaId)", r.stderr.split("\n      ")[0])


class IgnoresNonCommandStrings(unittest.TestCase):
    def test_log_message_concat_is_not_reported(self):
        """コマンド組み立てではないただの文字列連結(ログ等)は対象外。"""
        r = run_on('log.info("existingPostId=" + targetPostId + " は存在しません");\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_comment_line_is_skipped(self):
        r = run_on('// "post delete " + postId をここで組み立てていた\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_javadoc_line_is_skipped(self):
        r = run_on('     * {@code "post delete " + postId} の形だった\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


class SafeComposedAllowList(unittest.TestCase):
    def test_fields_is_allowed_because_it_is_already_quoted_inside(self):
        """`postFieldsArgs` は各値を内部で ShellQuote 済み。再クォートすると壊れる。"""
        r = run_on('String sub = "post update " + ShellQuote.single(id) + " - " + fields + " --porcelain";\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


class BlindSpotsFoundInReview(unittest.TestCase):
    """レビューで見つかった検査器の盲点(issue #1416 の3周目)。

    1周目は `"literal " + expr` の形だけ、2周目は `StringBuilder.append` を追加した。
    3周目で、独立レビューが次の2つを見つけた。どちらも実際に漏れを隠していた。
    """

    def test_expression_first_concat_is_reported(self):
        """`<式> + "literal"` の順。`type + " list --fields=..."` を見逃していた。"""
        r = run_on('return type + " list --fields=name,status --format=json";\n')
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("type", r.stderr)

    def test_expression_first_with_flag_literal_is_reported(self):
        r = run_on('runWpCli(creds, wpType + " install " + ShellQuote.single(p) + " --force", label);\n')
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("wpType", r.stderr)

    def test_plain_shell_command_is_in_scope(self):
        """wp-cli を介さない素のシェルコマンド。`tar ... + dirName` を見逃していた。"""
        r = run_on('exec(creds, "tar -czf " + ShellQuote.single(remote) + " " + dirName);\n')
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("dirName", r.stderr)

    def test_path_building_is_not_reported(self):
        """パス文字列の組み立ては対象外。区切りの `"-"` をフラグと誤認しないこと。

        `"/tmp/letsblog-" + dirName + "-" + UUID.randomUUID() + ".tar.gz"` は
        コマンドではなく、結果は使うときに ShellQuote を通る。
        """
        r = run_on('String remotePath = "/tmp/letsblog-" + dirName + "-" + UUID.randomUUID() + ".tar.gz";\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


class RealSourceIsClean(unittest.TestCase):
    """本物の `WordPressSshOperations.java` に違反が無いこと(issue #1416 の本題)。"""

    def test_repository_source_has_no_unquoted_values(self):
        r = subprocess.run([sys.executable, SCRIPT], capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
