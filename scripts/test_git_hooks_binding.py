#!/usr/bin/env python3
"""`scripts/git-hooks/` が実際に束縛されていることの検証(#1039)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、同時に
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルの
テストで表現する」ことを明示的な例外として認めている。

本Issueの対象は `core.hooksPath` という **git の設定**と、それを束縛・点検する
開発者向けスクリプトである。製品の画面には一切現れず、利用者が観測できる振る舞いでも
ない。したがって Web UI から到達する経路が構造的に存在しない。黙って省略しているのでは
なく、`scripts/test_check_env.py` / `scripts/test_check_issue_labels.py` と同じ
文書化された例外として、ここで表現する。

## なぜ機械的に検査するのか

`core.hooksPath` は **git の設定であってリポジトリの内容ではない**。クローンにも
チェックアウトにも含まれず、コミットもされない。したがって:

  - 設定漏れは差分に現れず、レビューで見つからない
  - フックが動いていないことの唯一の症状は「**何も起きない**」こと
  - `.claude/hooks/guard.py` は動いているため、エージェント経由のコミットでは違反が
    捕まることがある。これが「強制は効いている」という誤った確信を与える

実際 #976 で `scripts/git-hooks/pre-commit` が追加されて以降、この層は一度も
動いていなかった。にもかかわらず `README.md` と `.claude/CLAUDE.md` は
「このチェックアウトでは設定済み」と断言しており、誰も疑わなかった。

症状が「何も起きない」である以上、**検知が無ければ同じことが再発する**。
このテストがその検知である。新規クローンでまだ束縛していなければ落ちるが、それは
誤検知ではなく、まさに検知したい状態である(直し方はメッセージが示す)。
"""

import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

HOOKS_DIR = "scripts/git-hooks"
SETUP_SCRIPT = os.path.join(HERE, "setup-git-hooks.sh")
PRE_COMMIT = os.path.join(REPO_ROOT, HOOKS_DIR, "pre-commit")

FIX_HINT = "bash scripts/setup-git-hooks.sh"


def git(args, cwd, **kwargs):
    env = dict(os.environ)
    # 呼び出し元の git 環境変数が temp リポジトリに漏れると、別のリポジトリを
    # 操作してしまう。明示的に外す。
    for key in list(env):
        if key.startswith("GIT_"):
            del env[key]
    return subprocess.run(
        ["git"] + args, cwd=cwd, capture_output=True, text=True, env=env, timeout=60, **kwargs
    )


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


class ThisCheckoutIsBound(unittest.TestCase):
    """受入基準1: このチェックアウトで `core.hooksPath` が `scripts/git-hooks` を返す。

    受入基準4の検知手段でもある。束縛が外れていればここが落ちる。
    """

    def test_core_hooks_path_is_bound(self):
        r = git(["config", "--get", "core.hooksPath"], cwd=REPO_ROOT)
        self.assertEqual(
            0,
            r.returncode,
            "core.hooksPath が未設定。git フックが一切動いていない(#1039)。\n"
            "  %s" % FIX_HINT,
        )
        configured = r.stdout.strip()
        resolved = os.path.realpath(os.path.join(REPO_ROOT, configured))
        self.assertEqual(
            os.path.realpath(os.path.join(REPO_ROOT, HOOKS_DIR)),
            resolved,
            "core.hooksPath が %s ではなく %s を指している。\n  %s"
            % (HOOKS_DIR, configured, FIX_HINT),
        )

    def test_pre_commit_hook_is_executable(self):
        """実行ビットが落ちていると git はフックを黙って無視する。"""
        self.assertTrue(os.path.isfile(PRE_COMMIT), "%s が無い" % PRE_COMMIT)
        self.assertTrue(
            os.access(PRE_COMMIT, os.X_OK),
            "%s に実行ビットが無い。git はフックを黙って飛ばす" % PRE_COMMIT,
        )


class TempRepo(unittest.TestCase):
    """新規クローン相当の作業ツリーを作る。

    束縛スクリプトは自分の位置からリポジトリ根を割り出すので、コピー先の
    temp リポジトリに対して動く。実物のクローンを毎回作らずに、同じ導線を試せる。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.tmp, "scripts", "git-hooks"))
        os.makedirs(os.path.join(self.tmp, ".claude", "hooks"))
        shutil.copy(SETUP_SCRIPT, os.path.join(self.tmp, "scripts", "setup-git-hooks.sh"))
        shutil.copy(PRE_COMMIT, os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"))
        os.chmod(os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"), 0o755)
        shutil.copy(
            os.path.join(REPO_ROOT, ".claude", "hooks", "paths.py"),
            os.path.join(self.tmp, ".claude", "hooks", "paths.py"),
        )
        git(["init", "-q"], cwd=self.tmp)
        git(["config", "user.email", "t@example.com"], cwd=self.tmp)
        git(["config", "user.name", "t"], cwd=self.tmp)
        # フックを束縛する前に初期コミットを作る(`git diff --cached` に HEAD を与える)。
        git(["add", "-A"], cwd=self.tmp)
        git(["commit", "-q", "-m", "init"], cwd=self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_setup(self, *args):
        return subprocess.run(
            ["bash", os.path.join(self.tmp, "scripts", "setup-git-hooks.sh")] + list(args),
            capture_output=True,
            text=True,
            timeout=60,
            cwd=self.tmp,
        )

    def hooks_path(self):
        return git(["config", "--get", "core.hooksPath"], cwd=self.tmp).stdout.strip()

    def write(self, rel_path, text):
        full = os.path.join(self.tmp, rel_path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
        return rel_path


class Binding(TempRepo):
    """受入基準3: 文書化された手順を踏むだけで束縛される。"""

    def test_binds_a_fresh_clone(self):
        r = self.run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(HOOKS_DIR, self.hooks_path())

    def test_binding_is_idempotent(self):
        self.run_setup()
        r = self.run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(HOOKS_DIR, self.hooks_path())

    def test_rebinds_when_pointing_elsewhere(self):
        git(["config", "core.hooksPath", ".git/hooks"], cwd=self.tmp)
        r = self.run_setup()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(HOOKS_DIR, self.hooks_path())

    def test_refuses_to_bind_when_the_hook_directory_is_missing(self):
        """存在しない場所へ束縛すると、以後 git のあらゆる操作が落ちる。"""
        shutil.rmtree(os.path.join(self.tmp, "scripts", "git-hooks"))
        r = self.run_setup()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertEqual("", self.hooks_path(), "壊れた場所へ束縛してしまった")

    def test_unknown_option_is_a_usage_error(self):
        r = self.run_setup("--wat")
        self.assertEqual(2, r.returncode, r.stdout + r.stderr)


class Detection(TempRepo):
    """受入基準4: 束縛が外れている状態を検知でき、外した状態で実行すると失敗する。"""

    def test_check_fails_when_unbound(self):
        r = self.run_setup("--check")
        self.assertNotEqual(0, r.returncode, "未束縛なのに成功した: " + r.stdout)

    def test_check_names_how_to_fix_it(self):
        r = self.run_setup("--check")
        self.assertIn("setup-git-hooks.sh", r.stdout + r.stderr)

    def test_check_passes_when_bound(self):
        self.run_setup()
        r = self.run_setup("--check")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_check_does_not_write_config(self):
        """点検が黙って直してしまうと、検知として機能しない。"""
        self.run_setup("--check")
        self.assertEqual("", self.hooks_path(), "--check が設定を書き換えた")

    def test_check_fails_when_pointing_elsewhere(self):
        git(["config", "core.hooksPath", ".git/hooks"], cwd=self.tmp)
        r = self.run_setup("--check")
        self.assertNotEqual(0, r.returncode, r.stdout)

    def test_check_fails_when_the_hook_is_not_executable(self):
        """実行ビットが落ちたフックを git は黙って飛ばす。束縛だけ見ても足りない。"""
        self.run_setup()
        os.chmod(os.path.join(self.tmp, "scripts", "git-hooks", "pre-commit"), 0o644)
        r = self.run_setup("--check")
        self.assertNotEqual(0, r.returncode, r.stdout)


class PlainGitEnforcesPhaseSeparation(TempRepo):
    """受入基準2: guard.py を経由しない素の git コマンドでも拒否される。

    `.claude/hooks/guard.py` はエージェントのツール呼び出ししか見られない。
    人手のコミットと `bash -c` 等で素通りしたコミットを捕まえるのがこの層である。
    ここでは Claude Code を一切介さず、subprocess から素の `git commit` を叩く。
    """

    def commit(self, message):
        return git(["commit", "-m", message], cwd=self.tmp)

    def test_mixed_commit_is_rejected(self):
        self.run_setup()
        git(
            [
                "add",
                self.write("apps/web/src/foo.ts", "export const foo = 1\n"),
                self.write("apps/web/src/foo.test.ts", "test('foo', () => {})\n"),
            ],
            cwd=self.tmp,
        )
        r = self.commit("feat: mixed")
        self.assertNotEqual(0, r.returncode, "混在コミットが通ってしまった")
        self.assertIn("混在", r.stdout + r.stderr)

    def test_test_only_commit_is_accepted(self):
        """常に落ちるのでは検査になっていない。通るべきものが通ることも見る。"""
        self.run_setup()
        git(["add", self.write("apps/web/src/foo.test.ts", "test('foo', () => {})\n")], cwd=self.tmp)
        r = self.commit("test: add")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_mixed_commit_passes_while_unbound(self):
        """束縛が無ければ素通りする — これが #1039 で起きていたことそのもの。"""
        git(
            [
                "add",
                self.write("apps/web/src/foo.ts", "export const foo = 1\n"),
                self.write("apps/web/src/foo.test.ts", "test('foo', () => {})\n"),
            ],
            cwd=self.tmp,
        )
        self.assertEqual(0, self.commit("feat: mixed").returncode)


def paragraph_starting_with(text, prefix):
    """`prefix` で始まる段落(空行で区切られた塊)を返す。無ければ None。

    「`core.hooksPath` を含む最初の段落」では順序に依存し、後から段落が増えると
    黙って別の段落を検査してしまう。書き出しで名指しする。
    """
    for block in text.split("\n\n"):
        if block.startswith(prefix):
            return block
    return None


def section(text, heading):
    """`heading` 行から次の同レベル見出しまでを返す。"""
    lines = text.splitlines()
    level = heading.split(" ")[0]
    try:
        start = lines.index(heading)
    except ValueError:
        return None
    for i in range(start + 1, len(lines)):
        if lines[i].startswith(level + " "):
            return "\n".join(lines[start:i])
    return "\n".join(lines[start:])


class Documentation(unittest.TestCase):
    """受入基準3・5: 文書が実態と一致し、通常のセットアップ手順が束縛を含む。"""

    def test_readme_startup_procedure_binds_the_hooks(self):
        """新規クローンの手順そのものに束縛が入っていること。

        「品質の担保」節に `git config` の呪文を書いておくだけでは同じ失敗を繰り返す。
        現に書いてあったが、誰も打たなかった(#1039)。
        """
        body = section(read("README.md"), "## アプリケーションの起動(Docker)")
        self.assertIsNotNone(body, "README の起動手順の節が見つからない")
        self.assertIn(
            "setup-git-hooks.sh",
            body,
            "新規クローンの手順に git フックの束縛が入っていない",
        )

    def test_docs_name_the_check_command(self):
        """束縛が外れたことに気づく手段が、文書から辿れること。"""
        for rel_path in ("README.md", ".claude/CLAUDE.md"):
            with self.subTest(path=rel_path):
                self.assertIn("setup-git-hooks.sh --check", read(rel_path))

    def test_docs_do_not_claim_the_binding_is_already_done(self):
        """「設定済み」という検証されない断言を残さないこと。

        この断言こそが #1039 を見えなくしていた。設定はリポジトリの内容ではないので、
        文書が「済んでいる」と言い切れる根拠はどこにも無い。

        検査するのは**束縛を定義している段落**であって、「かつてそう書いてあり、
        それは誤りだった」と経緯を説明する記述ではない。前者は断言、後者は記録である。
        したがって段落を出現順ではなく書き出しで名指しする。
        """
        for rel_path, heading, prefix, claim in (
            ("README.md", None, "git フックは", "設定済み"),
            (
                ".claude/CLAUDE.md",
                "## Git hook — `scripts/git-hooks/pre-commit`",
                "Bound ",
                "already set",
            ),
        ):
            text = read(rel_path)
            if heading is not None:
                text = section(text, heading)
                self.assertIsNotNone(text, "%s に %s の節が無い" % (rel_path, heading))
            block = paragraph_starting_with(text, prefix)
            with self.subTest(path=rel_path):
                self.assertIsNotNone(
                    block, "%s に「%s」で始まる束縛の説明が無い" % (rel_path, prefix)
                )
                self.assertIn("core.hooksPath", block, "束縛を説明していない段落を見ている")
                self.assertNotIn(claim, block, "%s が未検証の断言を残している" % rel_path)


if __name__ == "__main__":
    unittest.main()
