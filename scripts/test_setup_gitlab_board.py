#!/usr/bin/env python3
"""`scripts/setup-gitlab-board.sh` と GitLab セットアップ手順の検証(#1026)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜスクリプトとドキュメントを同じ場所で検査するのか

このIssueが解こうとしているのは「GitLab 環境が再現できない」という一つの問題で、
スクリプトと手順書はその両輪である。ラベルの定義だけコードにあっても、
`glab` を入れて認証を通すところで詰まれば再現できない。

とくに非自明なのは3点で、知らなければ確実に詰まる。手順書がこれらに触れて
いることを機械的に固定する。

  1. GitLab がサブパス(`/gitlab`)配下にある → `subfolder` 設定が要る
  2. 自己署名証明書 → `ca_cert` 設定が要る
  3. `sudo` が使えない → ユーザー領域へ導入する

そして `skip_tls_verify` で握り潰す手順を**書かない**ことも同じく重要である。
一度書かれると、それが既定のやり方として引き継がれる。
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "setup-gitlab-board.sh")
DOC = os.path.join(REPO_ROOT, "docs", "GITLAB_WORKFLOW_SETUP.md")

# スタブは呼ばれた引数で応答を選び、書き込み系の呼び出しを記録する。
# 「2回目の実行で何も書かない」ことを、記録の有無で判定できるようにするため。
STUB = r"""#!/bin/bash
args="$*"
d="$GLAB_STUB_DIR"
case "$args" in
  *--method\ POST*|*-X\ POST*)
      echo "$args" >> "$d/writes.log"
      echo '{"id": 999, "name": "created"}'
      exit 0 ;;
  *lists*)   cat "$d/lists" ;;
  *labels*)  cat "$d/labels" ;;
  *boards/*) cat "$d/board" ;;
  *boards*)  cat "$d/boards" ;;
  *user*)    echo '{"username":"tester"}' ;;
  *)         cat "$d/project" ;;
esac
"""


class Harness(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        stub = os.path.join(self.bin, "glab")
        with open(stub, "w") as f:
            f.write(STUB)
        os.chmod(stub, 0o755)
        self.write("project", {"id": 9, "path_with_namespace": "seiji/lets_blog_server",
                               "issues_access_level": "enabled"})

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, name, payload):
        with open(os.path.join(self.tmp, name), "w") as f:
            json.dump(payload, f)

    def writes(self):
        path = os.path.join(self.tmp, "writes.log")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [line for line in f.read().splitlines() if line.strip()]

    def run_script(self):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["GLAB_STUB_DIR"] = self.tmp
        return subprocess.run(
            ["bash", SCRIPT], capture_output=True, text=True, env=env, timeout=90
        )

    def existing_everything(self):
        """ラベル10種・ボード・列7つが全て揃った状態。"""
        names = [
            "status::Inbox", "status::Backlog", "status::Ready", "status::In Progress",
            "status::Review", "status::QA", "status::Done",
            "priority::P0", "priority::P1", "priority::P2",
        ]
        self.write("labels", [{"id": i, "name": n} for i, n in enumerate(names, 1)])
        self.write("boards", [{"id": 1, "name": "Development"}])
        board_lists = [{"id": i, "position": i, "label": {"id": i, "name": n}}
                       for i, n in enumerate(names[:7], 1)]
        self.write("board", {"id": 1, "name": "Development", "lists": board_lists})
        # `boards/<id>/lists` は配列を返す。board オブジェクトを流用しないこと。
        self.write("lists", board_lists)


class FreshProject(Harness):
    def test_creates_labels_and_lists(self):
        self.write("labels", [])
        self.write("boards", [{"id": 1, "name": "Development"}])
        self.write("board", {"id": 1, "name": "Development", "lists": []})
        self.write("lists", [])
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        writes = "\n".join(self.writes())
        for name in ("status::Inbox", "status::Done", "priority::P0", "priority::P2"):
            with self.subTest(label=name):
                self.assertIn(name, writes, "%s が作成されていない" % name)

    def test_uses_the_default_board_instead_of_creating_a_second_one(self):
        """GitLab が自動作成する既定ボードに列を足す。

        2枚目を作ると、利用者が最初に開くボードが空のままになる。
        """
        self.write("labels", [])
        self.write("boards", [{"id": 1, "name": "Development"}])
        self.write("board", {"id": 1, "name": "Development", "lists": []})
        self.write("lists", [])
        self.run_script()
        board_creates = [w for w in self.writes() if "boards" in w and "lists" not in w]
        self.assertEqual([], board_creates, "既定ボードがあるのに新規作成した")


class Idempotency(Harness):
    def test_second_run_writes_nothing(self):
        self.existing_everything()
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual([], self.writes(), "既に整った状態で書き込みが発生した")

    def test_only_the_missing_label_is_recreated(self):
        self.existing_everything()
        with open(os.path.join(self.tmp, "labels")) as f:
            labels = json.load(f)
        labels = [l for l in labels if l["name"] != "priority::P0"]
        self.write("labels", labels)
        self.run_script()
        writes = "\n".join(self.writes())
        self.assertIn("priority::P0", writes)
        self.assertNotIn("status::Inbox", writes, "既存ラベルまで作り直している")


class SetupDocument(unittest.TestCase):
    """手順書が、知らなければ詰まる3点に触れていること。"""

    def setUp(self):
        self.assertTrue(os.path.exists(DOC), "docs/GITLAB_WORKFLOW_SETUP.md が無い")
        with open(DOC, encoding="utf-8") as f:
            self.text = f.read()

    def test_covers_the_subfolder_install(self):
        self.assertIn("subfolder", self.text)

    def test_covers_the_self_signed_ca(self):
        self.assertIn("ca_cert", self.text)

    def test_does_not_recommend_disabling_tls_verification(self):
        """`skip_tls_verify` で握り潰す手順を既定にしない。

        一度書かれると、それが標準のやり方として引き継がれる。
        """
        for line in self.text.splitlines():
            if "skip_tls_verify" in line:
                self.assertTrue(
                    any(w in line for w in ("しない", "使わない", "避け", "не", "Do not", "避ける")),
                    "skip_tls_verify が否定の文脈なしに書かれている: %s" % line.strip(),
                )

    def test_covers_installing_without_sudo(self):
        self.assertIn("sudo", self.text)
        self.assertIn(".local/bin", self.text)

    def test_states_the_required_token_scope(self):
        self.assertIn("api", self.text)

    def test_tells_the_reader_to_verify_the_ca_fingerprint(self):
        """CA は検証前の接続から取る(TOFU)。突き合わせ手順が要る。"""
        self.assertIn("フィンガープリント", self.text)

    def test_points_at_the_board_script(self):
        self.assertIn("setup-gitlab-board.sh", self.text)


if __name__ == "__main__":
    unittest.main()
