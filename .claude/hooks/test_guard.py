"""`.claude/hooks/guard.py` の単体テスト。

guard.py は Claude Code の PreToolUse フックであり、標準入力で受け取った JSON に対して
「拒否する / 何もしない」のどちらかを標準出力に書く。ここではそれを**プロセスとして起動して**
検証する。関数を直接呼ぶ形にしないのは、guard.py が `sys.exit()` で制御を返す設計であり、
実際の起動経路と同じ形で確かめたいため。

このテストが存在する理由(#1022):

GitLab への移行後、`check_merge_flags` と `check_pr_coverage` は `gh pr merge` /
`gh pr create` という文字列を探したままだった。`glab` を使う限りどちらも一致せず、
マージ方式ガードもカバレッジゲートも**発火しないのに何のエラーも出ない**状態になっていた。
ガードの空振りが無言で起きたのは、guard.py 自体の単体テストが無かったためである。

    python3 -m unittest discover -s .claude/hooks -t .claude/hooks -p 'test_*.py'
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "guard.py")


def run_hook(mode, payload):
    """guard.py を起動し、(拒否理由 or None) を返す。

    拒否のときは permissionDecision=deny の JSON が出る。許可のときは何も出さない。
    """
    proc = subprocess.run(
        [sys.executable, HOOK, mode],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=60,
    )
    if not proc.stdout.strip():
        return None
    out = json.loads(proc.stdout)
    decision = out.get("hookSpecificOutput", {})
    if decision.get("permissionDecision") != "deny":
        return None
    return decision.get("permissionDecisionReason", "")


def bash_payload(command, cwd=None):
    payload = {"tool_input": {"command": command}, "session_id": "test-session"}
    if cwd:
        payload["cwd"] = cwd
    return payload


class MergeMethodGuard(unittest.TestCase):
    """CLAUDE.md → Completion Definition: Issue の MR は squash のみ。

    `--squash` の明示を要求する理由(なぜプロジェクト設定だけに頼らないか)は CLAUDE.md →
    Enforcement → Where squash is enforced が単一の定義であり、ここはそれをテストとして
    検証しているだけ。理由の再掲はしない。`gh pr merge` は方式未指定だと対話的に尋ねる
    仕様だったので「禁止フラグの検出」で足りていたが、GitLab では**`--squash` の不在
    そのものが規約違反**になる。判定は「squash の要求」でなければならない(#1442)。
    """

    def test_squash_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab mr merge 42 --squash --remove-source-branch"))
        )

    def test_short_squash_flag_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge 42 -s -d")))

    def test_clustered_short_flags_are_allowed(self):
        """cobra は `-sd` のような短縮フラグの結合を受け付ける。"""
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge 42 -sd")))

    def test_rebase_is_denied(self):
        reason = run_hook("bash", bash_payload("glab mr merge 42 --rebase"))
        self.assertIsNotNone(reason, "glab mr merge --rebase が拒否されていない")
        self.assertIn("squash", reason)

    def test_short_rebase_flag_is_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("glab mr merge 42 -r")))

    def test_missing_merge_method_is_denied(self):
        """方式を指定しない `glab mr merge` は squash 指定漏れとして拒否する。"""
        reason = run_hook("bash", bash_payload("glab mr merge 42"))
        self.assertIsNotNone(reason, "方式未指定の glab mr merge が拒否されていない")
        self.assertIn("squash", reason)

    def test_missing_merge_method_denial_cites_where_squash_is_enforced(self):
        """拒否理由は CLAUDE.md → Where squash is enforced を根拠にし、実態と食い違う
        `squash_option: default_off` を持ち出さない(#1442)。"""
        reason = run_hook("bash", bash_payload("glab mr merge 42"))
        self.assertIsNotNone(reason, "方式未指定の glab mr merge が拒否されていない")
        self.assertNotIn("default_off", reason)
        self.assertIn("Where squash is enforced", reason)

    def test_squash_message_alone_does_not_count_as_squash(self):
        """`--squash-message` は方式の指定ではない。前方一致で誤判定しないこと。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab mr merge 42 --squash-message 'x'"))
        )

    def test_remove_source_branch_is_not_mistaken_for_rebase(self):
        """`--remove-source-branch` に含まれる r を短縮フラグと誤検知しないこと。"""
        self.assertIsNone(
            run_hook("bash", bash_payload("glab mr merge 42 --squash --remove-source-branch"))
        )

    def test_repo_flag_is_not_mistaken_for_rebase(self):
        """`-R`(--repo)は大文字であり、`-r`(--rebase)ではない。"""
        self.assertIsNone(
            run_hook("bash", bash_payload("glab mr merge 42 --squash -R seiji/lets_blog_server"))
        )

    def test_attached_repo_value_is_not_mistaken_for_rebase(self):
        """回帰(#1441): `-Rowner/repo`(値直結、値に小文字 r を含む)を -r と誤認しないこと。

        AC1: `glab -Rowner/repo mr merge --squash` は許可される。
        """
        self.assertIsNone(
            run_hook("bash", bash_payload("glab -Rowner/repo mr merge --squash"))
        )

    def test_attached_repo_value_without_squash_is_still_denied(self):
        """AC2: squash 無しは、値直結の -R があっても引き続き拒否される。

        誤って -r(rebase)と誤認されて拒否されるのではなく、squash 未指定として
        正しい理由で拒否されること。
        """
        reason = run_hook("bash", bash_payload("glab -Rowner/repo mr merge"))
        self.assertIsNotNone(reason, "glab -Rowner/repo mr merge が拒否されていない")
        self.assertIn(
            "マージ方式が指定されていません",
            reason,
            "squash 未指定としてではなく、誤って --rebase 相当として拒否されている",
        )

    def test_attached_repo_value_containing_s_does_not_fake_squash(self):
        """回帰(#1441): 逆方向の誤認。値に小文字 s を含んでいても squash 指定済みと

        誤認せず、squash 必須検査が空振りしないこと。
        """
        reason = run_hook("bash", bash_payload("glab -Rsss mr merge"))
        self.assertIsNotNone(
            reason,
            "値中の s を squash 済みと誤認し、squash 必須検査が空振りしている",
        )
        self.assertIn("マージ方式が指定されていません", reason)

    def test_real_short_rebase_flag_with_squash_present_is_denied(self):
        """AC3: 本物の -r 単体は、--squash が同時に指定されていても拒否される。"""
        reason = run_hook("bash", bash_payload("glab mr merge --squash -r"))
        self.assertIsNotNone(reason)
        self.assertIn("--rebase", reason)

    def test_ac4_squash_and_remove_source_branch_cluster_is_allowed(self):
        """AC4: 本物のブールクラスタ `-sd` は、値直結の誤認防止後も引き続き許可される。"""
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge -sd")))

    def test_unrelated_command_is_ignored(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab mr view 42")))

    def test_flags_of_a_later_command_do_not_leak_in(self):
        """`;` の後ろの別コマンドのフラグを、merge のフラグと混同しないこと。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab mr merge 42 ; grep -s squash file"))
        )

    def test_quoted_occurrence_is_not_treated_as_execution(self):
        """文字列の中に現れた `glab mr merge` を実行とみなさないこと(BOUNDARY の役割)。"""
        self.assertIsNone(
            run_hook("bash", bash_payload("echo 'run glab mr merge --rebase to merge'"))
        )


class CoverageGate(unittest.TestCase):
    """CLAUDE.md → Enforcement: MR 作成時に変更コードの C1/C2 を検査する。

    実際の判定は `scripts/check-changed-coverage.py` が行う。ここで確かめるのは
    **`glab mr create` でそのスクリプトが起動し、失敗が拒否に変換されること**。
    """

    def _project(self, exit_code):
        """`scripts/check-changed-coverage.py` が指定の終了コードを返す一時プロジェクト。"""
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write(
                "import sys\n"
                "print('coverage report placeholder')\n"
                "sys.exit(%d)\n" % exit_code
            )
        return root

    def _run(self, command, exit_code):
        root = self._project(exit_code)
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            return run_hook("bash", bash_payload(command, cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup

    def test_mr_create_is_denied_when_coverage_fails(self):
        reason = self._run("glab mr create --target-branch develop", 1)
        self.assertIsNotNone(reason, "カバレッジ不足でも glab mr create が拒否されていない")
        self.assertIn("C1/C2", reason)

    def test_mr_create_is_allowed_when_coverage_passes(self):
        self.assertIsNone(self._run("glab mr create --target-branch develop", 0))

    def test_unrelated_command_does_not_run_the_check(self):
        self.assertIsNone(self._run("glab mr list", 1))

    # ------------------------------------------------------------- #1229
    #
    # `check_pr_coverage` はツール呼び出しの cwd(ここでは payload の `cwd`)にある
    # 実際の git ブランチを判定対象にする。worktree かどうかを問わず、`root` を
    # 決めた後にそのブランチ名を読めることを、実際の git リポジトリで確認する。

    def _git_project(self, exit_code, branch="fix/1229-test"):
        """実際の git リポジトリの上に、指定コードで終了する偽の検査スクリプトを置く。"""
        root = tempfile.mkdtemp()
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
        subprocess.run(["git", "checkout", "-q", "-b", branch], cwd=root, check=True)
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write(
                "import sys\n"
                "print('coverage report placeholder')\n"
                "sys.exit(%d)\n" % exit_code
            )
        with open(os.path.join(root, "README.md"), "w") as f:
            f.write("x")
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=root, check=True)
        return root

    def _run_in_git_project(self, command, exit_code, branch="fix/1229-test"):
        root = self._git_project(exit_code, branch=branch)
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            return run_hook("bash", bash_payload(command, cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup

    def test_denial_message_names_the_branch_that_was_measured(self):
        """要件3: 拒否メッセージにカバレッジを測ったブランチ名が含まれる。"""
        reason = self._run_in_git_project(
            "glab mr create --target-branch develop", 1, branch="fix/1229-test"
        )
        self.assertIsNotNone(reason)
        self.assertIn("fix/1229-test", reason)

    def test_source_branch_mismatch_is_denied(self):
        """要件4: `--source-branch` が実際に計測したブランチと食い違うなら拒否する。"""
        reason = self._run_in_git_project(
            "glab mr create --source-branch other-branch --target-branch develop",
            0,
            branch="fix/1229-test",
        )
        self.assertIsNotNone(reason, "--source-branch の食い違いが素通りした")
        self.assertIn("fix/1229-test", reason)
        self.assertIn("other-branch", reason)

    def test_source_branch_matching_current_branch_is_allowed(self):
        """`--source-branch` が実際のブランチと一致していれば通常どおり通す。"""
        reason = self._run_in_git_project(
            "glab mr create --source-branch fix/1229-test --target-branch develop",
            0,
            branch="fix/1229-test",
        )
        self.assertIsNone(reason)


class NoStaleGitHubReferences(unittest.TestCase):
    """移行後に `gh` 前提の判定・文言が残っていないこと(#1022 の受入基準)。"""

    def test_the_guarded_program_is_glab(self):
        """ガードが見ているのが glab であること。

        このテストは当初 guard.py のソース全体に `gh pr merge` が現れないことを
        主張し、次に GLAB_MERGE / GLAB_MR_CREATE という正規表現定数の pattern を
        検査していた。#1029 でその定数自体が無くなった — 判定は正規表現ではなく
        `invokes(command, "glab", ("mr", "merge"))` というコマンド解析になったため。

        実装の形に依存しない形へ移した。主張する内容は変わっていない:
        **ガードは glab の操作を見ており、gh のそれではない。**
        """
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab mr merge 9 --rebase")),
            "glab mr merge --rebase が拒否されていない",
        )
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab mr merge 9")),
            "方式未指定の glab mr merge が拒否されていない",
        )

    def test_gh_commands_are_no_longer_guarded(self):
        """`gh` はこのリポジトリの操作対象ではなくなった。

        移行後 `gh` は GitHub 側(移行元)にしか届かない。ここで拒否しても
        このリポジトリを守ることにはならないので、判定の対象から外れている。
        """
        self.assertIsNone(run_hook("bash", bash_payload("gh pr merge 9 --rebase")))

    def test_read_only_denial_message_mentions_glab(self):
        """読み取り専用ステージの拒否文言が GitLab の操作を案内すること。"""
        root = tempfile.mkdtemp()
        state = os.path.join(root, ".claude", ".state")
        os.makedirs(state)
        with open(os.path.join(state, "readonly-test-session"), "w") as f:
            f.write("ready-issue")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook("bash", bash_payload("rm -rf build", cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(reason, "読み取り専用ステージで rm が拒否されていない")
        self.assertNotIn("gh ", reason)
        self.assertNotIn("GitHub", reason)
        self.assertIn("glab", reason)


class UnchangedGuards(unittest.TestCase):
    """移行で壊してはならない既存のガード。"""

    def test_no_verify_on_commit_is_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("git commit --no-verify -m x")))

    def test_no_verify_on_push_is_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("git push --no-verify")))

    def test_plain_commit_is_allowed(self):
        root = tempfile.mkdtemp()
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            # git リポジトリではないので staged の取得は失敗し、フェーズ判定はスキップされる。
            self.assertIsNone(run_hook("bash", bash_payload("git commit -m x", cwd=root)))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup


if __name__ == "__main__":
    unittest.main()


class WrapperPrefixBypass(unittest.TestCase):
    """#1029: 前置詞でガードが外れないこと。

    旧実装は `BOUNDARY = (?:^|[;&|]\\s*)` でコマンド先頭に固定して照合していた。
    そのため `timeout 60 git push --no-verify` のように**普通の書き方**をしただけで
    全てのガードが黙って外れた。故意の迂回ではなく、実際に #1022 の QA 中に
    `timeout` を付けたことで偶然踏んでいる。
    """

    WRAPPED_VIOLATIONS = [
        "timeout 60 git push --no-verify",
        "timeout 60 git push --no-verify 2>&1 | head -3",
        "env FOO=1 git push --no-verify",
        "FOO=1 git push --no-verify",
        "sudo -n git push --no-verify",
        "nice git push --no-verify",
        "nice -n 5 git push --no-verify",
        "time git commit --no-verify -m x",
        "command git push --no-verify",
        "nohup git push --no-verify",
        "xargs git push --no-verify",
        "timeout 60 glab mr merge 9 --rebase --yes",
        "nice glab mr merge 9 --rebase",
        "env GLAB_X=1 glab mr merge 9",
        "sudo -n glab mr merge 9 --squash-message x",
    ]

    def test_wrapped_violations_are_still_denied(self):
        for command in self.WRAPPED_VIOLATIONS:
            with self.subTest(command=command):
                self.assertIsNotNone(
                    run_hook("bash", bash_payload(command)),
                    "前置詞でガードが外れた: %s" % command,
                )

    WRAPPED_LEGITIMATE = [
        "timeout 60 glab mr merge 9 --squash --remove-source-branch",
        "env FOO=1 glab mr merge 9 -sd",
        "timeout 60 git push",
        "timeout 60 git push origin develop",
    ]

    def test_wrapped_legitimate_commands_are_allowed(self):
        for command in self.WRAPPED_LEGITIMATE:
            with self.subTest(command=command):
                self.assertIsNone(
                    run_hook("bash", bash_payload(command)),
                    "正当なコマンドが拒否された: %s" % command,
                )

    QUOTED = [
        "echo 'timeout 60 git push --no-verify'",
        'echo "glab mr merge 9 --rebase"',
        "grep -n 'git push --no-verify' docs/x.md",
    ]

    def test_quoted_occurrences_are_not_executions(self):
        """引用符の中に現れた違反コマンドは実行ではない。"""
        for command in self.QUOTED:
            with self.subTest(command=command):
                self.assertIsNone(
                    run_hook("bash", bash_payload(command)),
                    "引用符内の文字列を実行と誤認した: %s" % command,
                )

    def test_violation_after_a_separator_is_denied(self):
        """区切りの後ろに前置詞付きで置かれた場合も見逃さない。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("echo hi && timeout 60 git push --no-verify"))
        )


class ReadOnlyStageFalsePositives(unittest.TestCase):
    """#986: 読み取り専用ステージが正当な調査コマンドを誤って拒否しないこと。

    旧実装は引用を解釈せず生の文字列に正規表現をかけていたため、引用符の内側の
    `>` や `rm` / `patch` をシェルのリダイレクト・破壊的コマンドと誤認していた。
    読み取り専用ステージの目的はリポジトリを変更させないことであって、調査を
    妨げることではない。
    """

    def _in_stage(self, command):
        root = tempfile.mkdtemp()
        state = os.path.join(root, ".claude", ".state")
        os.makedirs(state)
        with open(os.path.join(state, "readonly-test-session"), "w") as f:
            f.write("ready-issue")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            return run_hook("bash", bash_payload(command, cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup

    LEGITIMATE = [
        """python3 -c 'b = open("x").read()
if len(b)>3000: print("big")'""",
        """python3 -c 'print("#%d -> Ready" % 5)'""",
        "grep -n 'patch' CHANGELOG.md",
        "cat docs/patch-notes.md",
        "awk '$1 > 5' file.txt",
        "jq '.x > 3' data.json",
        "grep -r 'rm -rf' docs/",
        "echo 'cp'",
        "git log --oneline -5",
        "git diff develop..HEAD",
        "cat scripts/issue-dependency-status.sh",
        "glab issue view 986",
    ]

    def test_investigation_commands_are_allowed(self):
        for command in self.LEGITIMATE:
            with self.subTest(command=command):
                self.assertIsNone(
                    self._in_stage(command),
                    "正当な調査コマンドが拒否された: %s" % command,
                )

    ACTUAL_MUTATIONS = [
        "rm -rf build",
        "mv a b",
        "cp a b",
        "sed -i 's/a/b/' file",
        "sed -i.bak 's/a/b/' file",
        "git commit -m x",
        "git checkout develop",
        "git push origin develop",
        "npm install",
        "npm ci",
        "echo x > file.txt",
        "echo x >> file.txt",
        "tee file.txt",
        "timeout 60 rm -rf build",
        "env FOO=1 git commit -m x",
    ]

    def test_actual_mutations_are_still_denied(self):
        for command in self.ACTUAL_MUTATIONS:
            with self.subTest(command=command):
                self.assertIsNotNone(
                    self._in_stage(command),
                    "リポジトリを変更するコマンドが許可された: %s" % command,
                )

    def test_devnull_redirect_is_allowed(self):
        """`2>/dev/null` はファイル書き込みではない。"""
        self.assertIsNone(self._in_stage("glab issue view 986 2>/dev/null"))

    # ---------------------------------------------------------------- #1034
    #
    # リダイレクト演算子は2種類あり、扱いが逆になる。
    #
    #   `>&` … ファイルディスクリプタの**複製**。ターゲットは fd 番号か `-`(クローズ)で、
    #          ファイルではない。`2>&1` はファイルを1バイトも作らない
    #   `&>` … stdout と stderr を**まとめてファイルへ**書く。ターゲットはファイル名
    #
    # 当初はどちらも扱えていなかった。`>&` はターゲットをファイル名として許可リストに
    # かけていたため偽陽性(`2>&1` の拒否)、`&>` は REDIRECTS に無く演算子として
    # 認識されなかったため偽陰性(実ファイルへの書き込みが素通り)になっていた。

    FD_DUPLICATIONS = [
        "bash scripts/x.sh 2>&1 | head",
        "glab issue view 1 2>&1",
        "cmd 1>&2",
        "cmd 2>&-",
    ]

    def test_fd_duplication_is_not_a_file_write(self):
        """fd 複製はファイルを作らない。拒否してはいけない(#1034)。"""
        for command in self.FD_DUPLICATIONS:
            with self.subTest(command=command):
                self.assertIsNone(
                    self._in_stage(command),
                    "fd 複製がファイル書き込みと誤判定された: %s" % command,
                )

    def test_ampersand_redirect_to_devnull_is_allowed(self):
        """`&>/dev/null` は許可リストのターゲットなので通る。"""
        self.assertIsNone(self._in_stage("glab issue view 1 &>/dev/null"))

    def test_ampersand_redirect_to_a_real_file_is_denied(self):
        """`&> 実ファイル` は本物のファイル書き込みである(#1034 の偽陰性)。

        当初 `&>` は REDIRECTS に無く、演算子として認識されていなかった。
        `&>/dev/null` が通っていたのは「fd 複製として正しく除外されていた」からでは
        なく、**オペレータ自体が見えていなかった偶然**である。
        """
        reason = self._in_stage("echo x &> real.txt")
        self.assertIsNotNone(reason, "&> による実ファイルへの書き込みが素通りした")
        self.assertIn("real.txt", reason)


class HeredocAndProcessSubstitution(unittest.TestCase):
    """#1035: ヒアドキュメント本文とプロセス置換の中身を正しく扱う。

    #1029 で導入した `split_commands()` は、シェルの構文をコマンド区切り・リダイレクト
    演算子のトークンとして解釈する。しかしヒアドキュメントの本文とプロセス置換の中身は
    その解析器では未対応で、逆向きの2つの欠陥が出ていた。

      * ヒアドキュメント本文中の `;` `>` が演算子として解釈され、無害な本文が
        破壊的コマンド・ファイル書き込みとして誤検知される(偽陽性)。
      * プロセス置換 `>(...)` / `<(...)` の中身は外側コマンドの引数トークンに
        埋もれ、`destructive_reason()` は `argv[0]` しか見ないため検知されない(偽陰性)。
    """

    def _in_stage(self, command):
        root = tempfile.mkdtemp()
        state = os.path.join(root, ".claude", ".state")
        os.makedirs(state)
        with open(os.path.join(state, "readonly-test-session"), "w") as f:
            f.write("ready-issue")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            return run_hook("bash", bash_payload(command, cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup

    def test_heredoc_body_semicolon_is_not_a_false_positive(self):
        """本文中の `; rm -rf ...` はコマンド区切りではない。"""
        command = "cat <<EOF\nsafe text; rm -rf /tmp/foo\nEOF"
        self.assertIsNone(
            self._in_stage(command),
            "ヒアドキュメント本文の `; rm -rf` が破壊的コマンドとして拒否された",
        )

    def test_heredoc_body_redirect_is_not_a_false_positive(self):
        """本文中の `> looks-like-redirect.txt` はリダイレクトではない。"""
        command = "cat <<EOF\nsome text > looks-like-redirect.txt\nEOF"
        self.assertIsNone(
            self._in_stage(command),
            "ヒアドキュメント本文の `>` がファイル書き込みとして拒否された",
        )

    def test_heredocs_own_redirect_is_still_denied(self):
        """ヒアドキュメント自身に付いたリダイレクトは実際のファイル書き込みである。"""
        command = "cat <<EOF > out.txt\nsome body\nEOF"
        reason = self._in_stage(command)
        self.assertIsNotNone(reason, "`cat <<EOF > out.txt` のリダイレクトが素通りした")
        self.assertIn("out.txt", reason)

    def test_dash_heredoc_body_is_skipped_too(self):
        """`<<-WORD` 形式(タブ除去)でも本文はコマンドとして解釈されない。"""
        command = "cat <<-EOF\n\tsafe; rm -rf /tmp/foo\n\tEOF"
        self.assertIsNone(
            self._in_stage(command),
            "`<<-EOF` の本文が破壊的コマンドとして拒否された",
        )

    def test_process_substitution_output_is_denied(self):
        """`>(...)` の中身は独立したコマンドとして検査対象になる。"""
        reason = self._in_stage("diff >(rm -rf build) /dev/null")
        self.assertIsNotNone(reason, "`diff >(rm -rf build) x` の中身が検知されなかった")

    def test_process_substitution_input_is_denied(self):
        """`<(...)` の中身も独立したコマンドとして検査対象になる。"""
        reason = self._in_stage("cat <(rm -rf build)")
        self.assertIsNotNone(reason, "`cat <(rm -rf build)` の中身が検知されなかった")

    def test_harmless_process_substitutions_are_allowed(self):
        """`diff <(sort a) <(sort b)` は日常的な調査コマンドであり、無害。"""
        self.assertIsNone(
            self._in_stage("diff <(sort a) <(sort b)"),
            "無害なプロセス置換が拒否された",
        )

    def test_explain_does_not_show_heredoc_body_as_a_command(self):
        """本文にたまたま含まれる語(`rm` など)を、解析結果に出さないこと。"""
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "cat <<EOF\nsafe text; rm -rf /tmp/foo\nEOF"],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("1 個のコマンド", proc.stdout, "ヒアドキュメント本文が別コマンドとして数えられた")
        self.assertNotIn("読み取り専用ステージ: 拒否", proc.stdout)


class ExplainSubcommand(unittest.TestCase):
    """#1029 Requirement 3: ガードの解釈を確認できること。

    このバグが温存されたのは、ガードが対象コマンドをどう読んだかを
    確認する手段が無かったためである。
    """

    def test_explain_does_not_call_an_fd_a_write_target(self):
        """`2>&1` の `1` を書き込み先として表示しないこと(#1034)。

        当初の拒否メッセージは `(1)` をファイル名として報告しており、
        そこにこの欠陥が出ていた。
        """
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "cmd 2>&1"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        for line in proc.stdout.splitlines():
            if "書き込み" in line:
                self.fail("fd 複製が書き込み先として表示された: %s" % line.strip())

    def test_explain_reports_an_ampersand_redirect_target(self):
        """`&>real.txt` の `real.txt` は書き込み先として表示されること(#1034)。"""
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "cmd &>real.txt"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("real.txt", proc.stdout)
        self.assertTrue(
            any("書き込み" in l and "real.txt" in l for l in proc.stdout.splitlines()),
            "real.txt が書き込み先として表示されていない:\n%s" % proc.stdout,
        )

    def test_explain_reports_the_parsed_commands(self):
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "timeout 60 git push --no-verify"],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        out = proc.stdout
        self.assertIn("git", out, "解析結果に実体コマンドが出ていない")
        self.assertIn("timeout", out, "剥がしたラッパーが示されていない")
        self.assertIn("deny", out.lower(), "判定結果が示されていない")

    def _explain(self, command):
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", command],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout

    def test_explain_denies_an_illegal_status_transition(self):
        """#1183: `cmd_bash` が拒否する遷移を、explain も DENY と表示すること。"""
        out = self._explain(
            'timeout 60 glab api "projects/:id/issues/1" --method PUT '
            '-f "remove_labels=status::Ready" -f "add_labels=status::Done"'
        )
        self.assertIn("判定: DENY", out)
        self.assertNotIn("判定: allow", out)

    def test_explain_denies_a_wholesale_label_overwrite(self):
        out = self._explain('glab api "projects/:id/issues/1" --method PUT -f "labels=bug"')
        self.assertIn("判定: DENY", out)

    def test_explain_keeps_denying_merge_flags_and_no_verify(self):
        self.assertIn("判定: DENY [マージ方式]", self._explain("glab mr merge 5"))
        self.assertIn("判定: DENY [--no-verify 禁止]", self._explain("git push --no-verify"))

    def test_explain_states_which_checks_it_cannot_run(self):
        """セッション状態・作業ツリーに依存するガードは、その旨を表示すること。"""
        out = self._explain("ls")
        self.assertIn("判定: allow", out)
        for name in ("check_read_only", "check_commit_phase", "check_pr_coverage"):
            self.assertIn(name, out)


class StatusLabelIntegrity(unittest.TestCase):
    """CLAUDE.md → How to change status: ステータスは常にちょうど1つ(#1023)。

    GitHub Projects の Status は単一選択フィールドで、2つ持つことは構造的に不可能だった。
    GitLab CE のラベルにその保証は無い(スコープ付きラベルは Premium)。
    ワークフローの選択ロジックはこの一意性に依拠しているので、規約で守るだけでは足りない。

    ここで防ぐのは、実際に起きる2つの壊し方である。

      1. `labels=` によるラベル集合の上書き。epic や bug が黙って消える。
         #1025 で実証済み: ["epic","priority::P0","status::Inbox"] → ["status::Inbox"]
      2. add_labels と remove_labels を別々の呼び出しに分けること。その間、
         ステータスが 0 個または 2 個になる。0 個の Issue はボードのどの列にも現れず、
         work-next からも triage からも見えなくなる。
    """

    PUT = "glab api projects/:id/issues/42 --method PUT "

    def test_paired_transition_is_allowed(self):
        """`In Progress` のような複数語のステータス名は、CLAUDE.md の例と同じく引用する。

        #1031 で明らかになった不備: 引用符無しの `add_labels=status::In Progress` は
        シェルの単語分割で `add_labels=status::In` と `Progress` という2トークンに
        割れてしまい、この呼び出しは実際には `status::In`(実在しないステータス名)
        への遷移しかテストしていなかった。#1023 の一意性チェックだけの頃は
        ステータス名の中身を見ていなかったので気づかれなかったが、#1031 で
        (from, to) の組を実際に検証するようになり、この不備が失敗として表面化した。
        テスト対象の振る舞いではなくテストコマンド自体の不備なので、引用を修正する。
        """
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    self.PUT
                    + '-f "remove_labels=status::Ready" -f "add_labels=status::In Progress"'
                ),
            )
        )

    def test_adding_a_status_without_removing_one_is_denied(self):
        reason = run_hook("bash", bash_payload(self.PUT + "-f add_labels=status::Review"))
        self.assertIsNotNone(reason, "ステータスの片側追加が拒否されていない")
        self.assertIn("remove_labels", reason)

    def test_removing_a_status_without_adding_one_is_denied(self):
        reason = run_hook("bash", bash_payload(self.PUT + "-f remove_labels=status::Review"))
        self.assertIsNotNone(reason, "ステータスの片側削除が拒否されていない")

    def test_wholesale_label_overwrite_is_denied(self):
        reason = run_hook("bash", bash_payload(self.PUT + "-f labels=status::Done"))
        self.assertIsNotNone(reason, "labels= による上書きが拒否されていない")
        self.assertIn("add_labels", reason)

    def test_wholesale_overwrite_is_denied_with_long_flag(self):
        self.assertIsNotNone(
            run_hook("bash", bash_payload(self.PUT + "--field labels=status::Done"))
        )

    def test_priority_only_change_is_allowed(self):
        """優先度だけを足すのは、ステータスの一意性とは無関係。"""
        self.assertIsNone(
            run_hook("bash", bash_payload(self.PUT + "-f add_labels=priority::P0"))
        )

    def test_unrelated_label_change_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload(self.PUT + "-f add_labels=bug")))

    def test_issue_creation_with_a_status_label_is_allowed(self):
        """新規作成は遷移ではない。最初のステータスはここで付く。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab issue create --title x --label status::Inbox,priority::P1 --yes"),
            )
        )

    def test_wrapped_violation_is_still_denied(self):
        """#1029 の教訓。前置詞で外れないこと。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("timeout 60 " + self.PUT + "-f labels=status::Done"))
        )

    def test_reading_an_issue_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab api projects/:id/issues/42"))
        )


class StatusTransitionValidity(unittest.TestCase):
    """CLAUDE.md → How to change status → Legal Transitions(#1031)。

    #1023 の `check_status_label_integrity` はステータスが常にちょうど1つであることしか
    見ていない。`status::Ready → status::Done` のように段を飛ばした遷移も「常に1つ」を
    満たすので、#1023 の検査は何も言わない。ここではその (from, to) の組が、
    CLAUDE.md が定義する正当な遷移の表に載っているかどうかを追加で検証する。

    表そのものは CLAUDE.md 側の単一定義であり、ここには再掲しない。
    """

    PUT = "glab api projects/:id/issues/42 --method PUT "

    @staticmethod
    def transition(frm, to):
        return (
            StatusTransitionValidity.PUT
            + '-f "remove_labels=status::%s" -f "add_labels=status::%s"' % (frm, to)
        )

    # CLAUDE.md → How to change status → Legal Transitions と一致させること。
    FORWARD_EDGES = [
        ("Inbox", "Backlog"),
        ("Backlog", "Ready"),
        ("Ready", "In Progress"),
        ("In Progress", "Review"),
        ("Review", "QA"),
        ("QA", "Done"),
    ]
    ROLLBACK_EDGES = [
        ("Review", "In Progress"),
        ("QA", "In Progress"),
        ("Ready", "Backlog"),
        ("Review", "Backlog"),
        ("In Progress", "Ready"),
    ]

    def test_ready_to_done_direct_transition_is_denied(self):
        reason = run_hook("bash", bash_payload(self.transition("Ready", "Done")))
        self.assertIsNotNone(reason, "Ready→Done の一足飛びが拒否されていない")

    def test_inbox_to_in_progress_direct_transition_is_denied(self):
        reason = run_hook("bash", bash_payload(self.transition("Inbox", "In Progress")))
        self.assertIsNotNone(reason, "Inbox→In Progress の一足飛びが拒否されていない")

    def test_every_forward_edge_is_allowed(self):
        for frm, to in self.FORWARD_EDGES:
            with self.subTest(frm=frm, to=to):
                self.assertIsNone(
                    run_hook("bash", bash_payload(self.transition(frm, to))),
                    "正当な前進の遷移 %s→%s が拒否された" % (frm, to),
                )

    def test_every_rollback_edge_is_allowed(self):
        for frm, to in self.ROLLBACK_EDGES:
            with self.subTest(frm=frm, to=to):
                self.assertIsNone(
                    run_hook("bash", bash_payload(self.transition(frm, to))),
                    "正当な差し戻しの遷移 %s→%s が拒否された" % (frm, to),
                )

    def test_wrapped_illegal_transition_is_still_denied(self):
        """#1029 の教訓。前置詞を付けても違法な遷移が素通りしないこと。"""
        command = "timeout 60 " + self.transition("Ready", "Done")
        reason = run_hook("bash", bash_payload(command))
        self.assertIsNotNone(reason, "前置詞付きの Ready→Done が素通りした")


class HotfixLabelImmutability(unittest.TestCase):
    """CLAUDE.md → Issue Provenance → hotfix: 付与・削除はユーザーのみ(#1433)。

    `hotfix` は選択順の第0キーで、`bug` と同じく Claude は読むだけである。GitLab CE の
    ラベルにこれを守らせる仕組みは無いので、既存 Issue への `hotfix` の付け外しは
    `check_status_label_integrity` とは**独立した**この検査で一律に拒否する
    (`status::` の遷移とは無関係な壊れ方なので、既存関数を拡張せず新規関数にする)。

    上限3件の判定はここではしない。ネットワークが要るため、事後検出は
    `scripts/check-issue-labels.sh` の役目(CLAUDE.md → Enforcement)。
    """

    PUT = "glab api projects/:id/issues/42 --method PUT "

    # --- `glab api ... --method PUT` の add_labels=/remove_labels= ---

    def test_adding_hotfix_via_api_put_is_denied(self):
        reason = run_hook("bash", bash_payload(self.PUT + "-f add_labels=hotfix"))
        self.assertIsNotNone(reason, "hotfix の付与が拒否されていない")
        self.assertIn("hotfix", reason)

    def test_removing_hotfix_via_api_put_is_denied(self):
        reason = run_hook("bash", bash_payload(self.PUT + "-f remove_labels=hotfix"))
        self.assertIsNotNone(reason, "hotfix の削除が拒否されていない")

    def test_adding_hotfix_combined_with_another_label_is_denied(self):
        """カンマ区切りの他ラベルに紛れていても検出すること。"""
        reason = run_hook(
            "bash", bash_payload(self.PUT + "-f add_labels=priority::P0,hotfix")
        )
        self.assertIsNotNone(reason, "他ラベルと同時の hotfix 付与が素通りした")

    def test_non_hotfix_label_change_via_api_put_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload(self.PUT + "-f add_labels=bug")))

    def test_label_containing_hotfix_as_a_substring_is_not_mistaken(self):
        """`hotfix` はラベル名の完全一致で判定する。部分一致で誤検知しないこと。"""
        self.assertIsNone(
            run_hook("bash", bash_payload(self.PUT + "-f add_labels=not-a-hotfix-label"))
        )

    def test_status_transition_without_hotfix_is_unaffected(self):
        """既存の status:: 遷移検査と衝突しないこと。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    self.PUT
                    + '-f "remove_labels=status::Ready" -f "add_labels=status::In Progress"'
                ),
            )
        )

    # --- `glab issue update` の --label/--unlabel ---

    def test_issue_update_label_hotfix_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("glab issue update 42 --label hotfix")
        )
        self.assertIsNotNone(reason, "glab issue update --label hotfix が拒否されていない")
        self.assertIn("hotfix", reason)

    def test_issue_update_unlabel_hotfix_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("glab issue update 42 --unlabel hotfix")
        )
        self.assertIsNotNone(reason, "glab issue update --unlabel hotfix が拒否されていない")

    def test_issue_update_short_flags_hotfix_is_denied(self):
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab issue update 42 -l hotfix"))
        )
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab issue update 42 -u hotfix"))
        )

    def test_issue_update_hotfix_combined_with_other_labels_is_denied(self):
        self.assertIsNotNone(
            run_hook(
                "bash", bash_payload("glab issue update 42 --label bug,hotfix")
            )
        )

    def test_issue_update_non_hotfix_label_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab issue update 42 --label bug"))
        )

    # --- pflag の短縮形は値を直結できる(`-lhotfix`)。実機 glab(1.116.0)で
    # 確認済み: `-lhotfix`/`-uhotfix`/`-l=hotfix`/`-u=hotfix` はいずれもパースエラーに
    # ならずネットワーク呼び出しに到達する(対照として `-zhotfix` は
    # `Unknown shorthand flag` になる)。この直結形を見逃すと AC3 の核心である
    # 「Claude は既存 Issue の hotfix に触れない」が破れる。

    def test_issue_update_short_flag_value_attached_hotfix_is_denied(self):
        """`-lhotfix` / `-uhotfix`(空白なしの直結形)を見逃さないこと。"""
        reason = run_hook("bash", bash_payload("glab issue update 42 -lhotfix"))
        self.assertIsNotNone(reason, "-lhotfix が拒否されていない")
        self.assertIn("hotfix", reason)
        reason = run_hook("bash", bash_payload("glab issue update 42 -uhotfix"))
        self.assertIsNotNone(reason, "-uhotfix が拒否されていない")

    def test_issue_update_short_flag_equals_hotfix_is_denied(self):
        """`-l=hotfix` / `-u=hotfix`(pflag が `=` を剥がす直結形)も見逃さないこと。"""
        reason = run_hook("bash", bash_payload("glab issue update 42 -l=hotfix"))
        self.assertIsNotNone(reason, "-l=hotfix が拒否されていない")
        reason = run_hook("bash", bash_payload("glab issue update 42 -u=hotfix"))
        self.assertIsNotNone(reason, "-u=hotfix が拒否されていない")

    def test_issue_update_short_flag_attached_hotfix_combined_with_other_label_is_denied(
        self,
    ):
        """直結形でも、他ラベルと並記されたカンマ区切りの中の hotfix を見逃さないこと。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab issue update 42 -lbug,hotfix"))
        )

    def test_issue_update_short_flag_attached_non_hotfix_label_is_allowed(self):
        """直結形で hotfix を含まないラベルは許可されること(`-lbug`)。"""
        self.assertIsNone(
            run_hook("bash", bash_payload("glab issue update 42 -lbug"))
        )

    def test_issue_update_short_flag_attached_substring_is_not_mistaken(self):
        """直結形でも `hotfix` は完全一致で判定する。部分一致で誤検知しないこと。"""
        self.assertIsNone(
            run_hook(
                "bash", bash_payload("glab issue update 42 -lhotfix-foo")
            )
        )
        self.assertIsNone(
            run_hook(
                "bash", bash_payload("glab issue update 42 -lnot-a-hotfix-label")
            )
        )

    def test_issue_create_with_hotfix_without_marker_is_now_denied(self):
        """#1434: 作成時の `hotfix` は `report-bug` 実行中だけ許可される。

        このテストは元々「新規作成は対象外(Out of Scope、#1434 で扱う。現状維持)」を
        検証していた。#1434 はまさにその隙間を埋める Issue であり、Requirement 5 は
        read-only stage マーカーが `report-bug` でない限り作成時の `hotfix` を拒否する
        ことを求めている。マーカーを何も立てていないこの呼び出しは、その「report-bug
        以外」に該当するので、期待する結果が allow から deny に変わる
        (`HotfixCreationGate` に、マーカーありの allow / 他スキルでの deny を追加した)。
        """
        reason = run_hook(
            "bash",
            bash_payload(
                "glab issue create --title x --label hotfix,priority::P0 --yes"
            ),
        )
        self.assertIsNotNone(reason, "マーカー無しでの hotfix 付き起票が拒否されていない")
        self.assertIn("hotfix", reason)

    def test_wrapped_hotfix_violation_is_still_denied(self):
        """#1029 の教訓。前置詞で外れないこと。"""
        self.assertIsNotNone(
            run_hook(
                "bash", bash_payload("timeout 60 glab issue update 42 --label hotfix")
            )
        )


# --------------------------------------------------------------------------- #1434


def _stage_root(skill, session="report-bug-test-session"):
    """`.claude/hooks/guard.py` の `cmd_stage` が書くマーカーファイルを、一時ディレクトリに
    直接作る(`ReadOnlyStageFalsePositives._in_stage` と同じ方式)。root と session id を返す。
    """
    root = tempfile.mkdtemp()
    state = os.path.join(root, ".claude", ".state")
    os.makedirs(state)
    with open(os.path.join(state, "readonly-%s" % session), "w", encoding="utf-8") as f:
        f.write(skill)
    return root, session


def _run_in_root(mode, tool_input, root, session):
    """`root` を `CLAUDE_PROJECT_DIR` にせず `cwd` として渡し、guard.py を起動する。

    `CLAUDE_PROJECT_DIR` が実際のセッション環境で設定されていると、そちらが
    `payload["cwd"]` より優先されてしまい、一時ディレクトリに立てたマーカーが
    見えなくなる(`project_dir()` の優先順位)。既存の `_in_stage` と同じ回避策。
    """
    env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
    try:
        return run_hook(mode, {"tool_input": tool_input, "session_id": session, "cwd": root})
    finally:
        if env_backup is not None:
            os.environ["CLAUDE_PROJECT_DIR"] = env_backup


class ReportBugReadOnlyStage(unittest.TestCase):
    """Requirement 3 / AC2: `report-bug` は read-only stage である(#1434)。

    #1433 までの `READ_ONLY_SKILLS` は `discover-issues` / `triage-backlog` / `ready-issue`
    の3つだけだった。`report-bug` が加わっていない限り、`Skill` フックの `cmd_stage` は
    マーカーを書かず、`Write`/`Edit` も、リポジトリ内へ書き込む `Bash` も止められない。

    **`_stage_root()` のようにマーカーファイルを直接書いてはいけない**。`read_stage()` は
    マーカーの中身が何であれ「読み取り専用ステージ中」として扱うため、直接書く方式では
    `report-bug` が `READ_ONLY_SKILLS` に入っているかどうかに関係なく常に拒否側になり、
    Requirement 3 が実際に満たされているかを検査したことにならない(検査したいのは
    「`cmd_stage` が `report-bug` のときにマーカーを書くかどうか」自体)。実際の呼び出し
    経路(`Skill` フック → `stage`、続けて `Write`/`Bash` フック)をそのままサブプロセスの
    連鎖として駆動する。スタブは使わない。
    """

    def _enter_stage(self, skill, root, session="report-bug-real-session"):
        """実際の `stage` サブコマンドを起動し、`cmd_stage` にマーカーの要否を判断させる。"""
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            proc = subprocess.run(
                [sys.executable, HOOK, "stage"],
                input=json.dumps(
                    {"tool_input": {"skill": skill}, "session_id": session, "cwd": root}
                ),
                capture_output=True,
                text=True,
                timeout=60,
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertEqual(0, proc.returncode, proc.stderr)
        return session

    def _new_root(self):
        root = tempfile.mkdtemp()
        os.makedirs(os.path.join(root, ".claude"))
        return root

    def test_report_bug_stage_marker_is_written(self):
        """`cmd_stage` が `report-bug` のマーカーを実際に書くこと。"""
        root = self._new_root()
        session = self._enter_stage("report-bug", root)
        marker = os.path.join(root, ".claude", ".state", "readonly-%s" % session)
        self.assertTrue(os.path.exists(marker), "report-bug のマーカーが書かれていない")
        with open(marker, encoding="utf-8") as f:
            self.assertEqual("report-bug", f.read().strip())

    def test_report_bug_stage_denies_writes_inside_the_repo(self):
        root = self._new_root()
        session = self._enter_stage("report-bug", root)
        target = os.path.join(root, "docs", "note.md")
        reason = _run_in_root("write", {"file_path": target, "content": "x"}, root, session)
        self.assertIsNotNone(reason, "report-bug 実行中の Write が拒否されていない")
        self.assertIn("report-bug", reason)

    def test_report_bug_stage_denies_edit_style_writes(self):
        """`Edit` も `file_path` を使うので同じ経路で拒否されること。"""
        root = self._new_root()
        session = self._enter_stage("report-bug", root)
        target = os.path.join(root, "src", "app.py")
        reason = _run_in_root(
            "write", {"file_path": target, "old_string": "a", "new_string": "b"}, root, session
        )
        self.assertIsNotNone(reason, "report-bug 実行中の Edit が拒否されていない")

    def test_report_bug_stage_denies_mutating_bash(self):
        root = self._new_root()
        session = self._enter_stage("report-bug", root)
        reason = _run_in_root("bash", {"command": "rm -rf build"}, root, session)
        self.assertIsNotNone(reason, "report-bug 実行中の破壊的コマンドが拒否されていない")

    def test_report_bug_stage_allows_investigation_bash(self):
        root = self._new_root()
        session = self._enter_stage("report-bug", root)
        reason = _run_in_root("bash", {"command": "git log --oneline -5"}, root, session)
        self.assertIsNone(reason, "report-bug 実行中の調査コマンドが拒否された")

    def test_other_skill_does_not_trigger_the_read_only_stage(self):
        """比較対照: read-only ではないスキルのマーカーは Write を拒否しない。"""
        root = self._new_root()
        session = self._enter_stage("implement-issue", root)
        target = os.path.join(root, "docs", "note.md")
        reason = _run_in_root("write", {"file_path": target, "content": "x"}, root, session)
        self.assertIsNone(reason, "read-only ではないスキルなのに Write が拒否された")


class HotfixCreationGate(unittest.TestCase):
    """Requirement 5 / AC3: 作成時の `hotfix` は `report-bug` の実行中だけ許可される(#1434)。

    `check_hotfix_label_immutability` は既存 Issue への付け外しだけを扱い、
    `check_status_label_integrity` は `glab issue create` を遷移ではないとして素通り
    させている(#1433 の Out of Scope)。ここで検証するのは、その隙間を埋める
    **独立した新規チェック**であり、既存の2つのチェックには影響しないこと。
    """

    def test_create_with_hotfix_is_denied_without_any_marker(self):
        reason = run_hook(
            "bash", bash_payload('glab issue create --label hotfix --title x')
        )
        self.assertIsNotNone(reason, "マーカー無しでの hotfix 付き起票が許可された")
        self.assertIn("hotfix", reason)

    def test_create_with_hotfix_is_denied_when_marker_is_another_read_only_skill(self):
        root, session = _stage_root("ready-issue")
        reason = _run_in_root(
            "bash", {"command": "glab issue create --label hotfix --title x"}, root, session
        )
        self.assertIsNotNone(reason, "ready-issue のマーカーで hotfix 付き起票が許可された")

    def test_create_with_hotfix_is_denied_when_marker_is_discover_issues(self):
        root, session = _stage_root("discover-issues")
        reason = _run_in_root(
            "bash", {"command": "glab issue create --label hotfix --title x"}, root, session
        )
        self.assertIsNotNone(reason, "discover-issues のマーカーで hotfix 付き起票が許可された")

    def test_create_with_hotfix_is_allowed_when_marker_is_report_bug(self):
        root, session = _stage_root("report-bug")
        reason = _run_in_root(
            "bash", {"command": "glab issue create --label hotfix --title x"}, root, session
        )
        self.assertIsNone(reason, "report-bug のマーカーがあるのに hotfix 付き起票が拒否された")

    def test_create_with_full_label_set_is_allowed_when_marker_is_report_bug(self):
        """Requirement 4 の5ラベル(user-request,bug,priority::P0,hotfix,status::Backlog)。"""
        root, session = _stage_root("report-bug")
        reason = _run_in_root(
            "bash",
            {
                "command": (
                    "glab issue create --title x "
                    "--label user-request,bug,priority::P0,hotfix,status::Backlog"
                ),
            },
            root,
            session,
        )
        self.assertIsNone(reason)

    def test_create_without_hotfix_is_allowed_regardless_of_marker(self):
        """AC3: `hotfix` を含まない起票は従来どおり許可される。"""
        self.assertIsNone(
            run_hook(
                "bash", bash_payload("glab issue create --label bug,priority::P0 --title x")
            )
        )
        root, session = _stage_root("ready-issue")
        reason = _run_in_root(
            "bash", {"command": "glab issue create --label bug,priority::P0 --title x"}, root, session
        )
        self.assertIsNone(reason)

    def test_create_short_flag_attached_hotfix_is_denied_without_marker(self):
        """`-lhotfix`(直結形)も見逃さないこと。既存の `_issue_update_label_args` を再利用する。"""
        reason = run_hook(
            "bash", bash_payload("glab issue create -lhotfix --title x")
        )
        self.assertIsNotNone(reason, "-lhotfix での起票が拒否されていない")

    def test_create_short_flag_attached_hotfix_is_allowed_with_report_bug_marker(self):
        root, session = _stage_root("report-bug")
        reason = _run_in_root(
            "bash", {"command": "glab issue create -lbug,hotfix --title x"}, root, session
        )
        self.assertIsNone(reason)

    def test_create_hotfix_substring_label_is_not_mistaken(self):
        """`hotfix` は完全一致で判定する。部分一致で誤検知しないこと。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab issue create --label not-a-hotfix-label --title x"),
            )
        )

    def test_wrapped_creation_violation_is_still_denied(self):
        """#1029 の教訓。前置詞で外れないこと。"""
        reason = run_hook(
            "bash", bash_payload("timeout 60 glab issue create --label hotfix --title x")
        )
        self.assertIsNotNone(reason)

    def test_hotfix_immutability_check_is_unaffected(self):
        """既存 Issue への付け外し検査(#1433)と衝突しないこと。"""
        reason = run_hook(
            "bash",
            bash_payload(
                'glab api projects/:id/issues/42 --method PUT -f add_labels=hotfix'
            ),
        )
        self.assertIsNotNone(reason, "既存 Issue への hotfix 付与が許可された(#1433 への回帰)")

    def test_status_transition_creation_is_unaffected(self):
        """作成時の `status::` ラベルは従来どおり素通りする(#1023 の Out of Scope)。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab issue create --label status::Backlog --title x"),
            )
        )


# --------------------------------------------------------------------------- #1435


class GlobalFlagBeforeSubcommand(unittest.TestCase):
    """`invokes()` は、値を取るグローバル永続フラグ(`--repo` など)がサブコマンドより前に
    置かれても、対象のサブコマンド呼び出しを検出しなければならない(#1435)。

    `glab`(cobra/pflag)は永続フラグをサブコマンドの前後どちらに置いても受け付けるが、
    旧実装は `positional = [a for a in rest if not a.startswith("-")]` という素朴な
    フィルタで判定していたため、`--repo owner/repo` のように値が独立したトークンに
    なるフラグが前置されると、値がサブコマンドの位置にずれ込んで一致しなくなっていた
    (`=` 結合形と短縮形の直結は1トークンで `-` 始まりなので、元から影響を受けない)。

    ここで確かめるのは、この抜け穴を継承した4つの利用者すべて
    (`check_merge_flags` / `check_hotfix_label_immutability` / `check_hotfix_creation` /
    `check_pr_coverage`)であり、あわせて Readiness Report が名指しした設計上の
    落とし穴(ブール型フラグの直後を消費しない、`=`/短縮直結を壊さない)への回帰を防ぐ。
    """

    # --- AC1/AC2: check_merge_flags ---

    def test_repo_prefixed_merge_without_squash_is_denied(self):
        """AC1: `--repo owner/repo` を前置しても squash 必須検査が発火すること。"""
        reason = run_hook(
            "bash", bash_payload("glab --repo owner/repo mr merge")
        )
        self.assertIsNotNone(reason, "--repo 前置の glab mr merge が拒否されていない")
        self.assertIn("squash", reason)

    def test_repo_prefixed_merge_with_squash_is_allowed(self):
        """AC2: `--repo owner/repo`(空白区切り)+ `--squash` は引き続き許可されること。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab --repo owner/repo mr merge --squash"),
            )
        )

    def test_repo_equals_prefixed_merge_with_squash_is_allowed(self):
        """AC2: `--repo=owner/repo`(`=` 結合形)+ `--squash` は引き続き許可されること。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab --repo=owner/repo mr merge --squash"),
            )
        )

    def test_repo_equals_prefixed_merge_without_squash_is_still_denied(self):
        """回帰: `=` 結合形は元々正しく動いていた分岐。壊れていないこと。"""
        reason = run_hook(
            "bash", bash_payload("glab --repo=owner/repo mr merge")
        )
        self.assertIsNotNone(reason, "--repo= 前置の glab mr merge が拒否されていない")

    # 値は #1441(`_has_flag` が短縮形の直結値に含まれる文字を無関係な短縮フラグの結合と
    # 誤認する既知の別バグ)を踏まないよう、小文字 `r` を含まない repo 名を使う
    # (`owner/repo` は `r` を含み、`_has_flag(..., "--rebase", "r")` を誤って満たす)。

    def test_short_repo_flag_attached_merge_without_squash_is_still_denied(self):
        """回帰: `-Racme/blog`(短縮形の直結)は元々正しく動いていた分岐。壊れていないこと。"""
        reason = run_hook(
            "bash", bash_payload("glab -Racme/blog mr merge")
        )
        self.assertIsNotNone(reason, "-Racme/blog 前置の glab mr merge が拒否されていない")

    def test_short_repo_flag_attached_merge_with_squash_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab -Racme/blog mr merge --squash"))
        )

    def test_boolean_flag_before_subcommand_does_not_swallow_it(self):
        """落とし穴1: `--help` はブール型。次のトークン `mr` を値として消費しないこと。

        消費すると `mr merge` が見えなくなり、逆に検出漏れになる(過検知に倒すべき
        という `invokes()` 自身の方針、#1029 に反する)。ここでは保守的に「サブコマンド
        は見えたまま」であることを、squash 必須検査が発火することで確かめる。
        """
        reason = run_hook("bash", bash_payload("glab --help mr merge"))
        self.assertIsNotNone(
            reason, "--help の次のトークンが誤って消費され、mr merge を見失っている"
        )

    def test_wrapped_repo_prefixed_merge_without_squash_is_denied(self):
        """回帰: `timeout` などの前置ラッパーとの併用でも見逃さないこと(#1029)。"""
        reason = run_hook(
            "bash", bash_payload("timeout 60 glab --repo owner/repo mr merge")
        )
        self.assertIsNotNone(reason, "ラッパー併用の --repo 前置 mr merge が拒否されていない")

    # --- AC3: check_hotfix_label_immutability ---

    def test_repo_prefixed_issue_update_hotfix_label_is_denied(self):
        """AC3: `--repo owner/repo` を前置しても既存 Issue の hotfix 付け外しが拒否されること。"""
        reason = run_hook(
            "bash",
            bash_payload("glab --repo owner/repo issue update 42 --label hotfix"),
        )
        self.assertIsNotNone(reason, "--repo 前置の issue update --label hotfix が拒否されていない")
        self.assertIn("hotfix", reason)

    def test_repo_prefixed_issue_update_non_hotfix_label_is_allowed(self):
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab --repo owner/repo issue update 42 --label bug"),
            )
        )

    # --- AC4: check_hotfix_creation ---

    def test_repo_prefixed_issue_create_hotfix_without_marker_is_denied(self):
        """AC4: `--repo owner/repo` を前置しても、マーカー無しの hotfix 付き起票が拒否されること。"""
        reason = run_hook(
            "bash",
            bash_payload(
                "glab --repo owner/repo issue create --title x --label hotfix"
            ),
        )
        self.assertIsNotNone(
            reason, "--repo 前置かつマーカー無しの hotfix 付き起票が拒否されていない"
        )
        self.assertIn("hotfix", reason)

    def test_repo_prefixed_issue_create_hotfix_is_allowed_with_report_bug_marker(self):
        """`report-bug` マーカーがあれば、`--repo` 前置でも従来どおり許可されること。"""
        root, session = _stage_root("report-bug")
        reason = _run_in_root(
            "bash",
            {
                "command": (
                    "glab --repo owner/repo issue create --title x --label hotfix"
                ),
            },
            root,
            session,
        )
        self.assertIsNone(reason)

    # --- AC5: check_pr_coverage(Coverage Gate) ---

    def _coverage_project(self, exit_code):
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write(
                "import sys\n"
                "print('coverage report placeholder')\n"
                "sys.exit(%d)\n" % exit_code
            )
        return root

    def _run_coverage(self, command, exit_code):
        root = self._coverage_project(exit_code)
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            return run_hook("bash", bash_payload(command, cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup

    def test_repo_prefixed_mr_create_triggers_coverage_gate(self):
        """AC5: `--repo owner/repo` を前置しても Coverage Gate が発火すること。

        素の `glab mr create` と同じ扱いになることを、カバレッジ不足(終了コード1)で
        拒否されることによって確かめる(`CoverageGate` と同じ検証方法)。
        """
        reason = self._run_coverage(
            "glab --repo owner/repo mr create --title x", 1
        )
        self.assertIsNotNone(
            reason, "--repo 前置の glab mr create で Coverage Gate が発火していない"
        )
        self.assertIn("C1/C2", reason)

    def test_repo_prefixed_mr_create_is_allowed_when_coverage_passes(self):
        self.assertIsNone(
            self._run_coverage("glab --repo owner/repo mr create --title x", 0)
        )


# --------------------------------------------------------------------------- #1440


class GitGlobalFlagBeforeSubcommand(unittest.TestCase):
    """`invokes()` は、値を取るグローバルオプション(`-C <path>` など)が `commit`/`push` より
    前に置かれても、`git` の対象サブコマンド呼び出しを検出しなければならない(#1440)。

    #1435 は同じ欠陥を `glab` 側について直した(`GlobalFlagBeforeSubcommand`)。`git` は
    値を取るフラグの集合が異なる(`-C`/`--git-dir`/`--work-tree`/`-c`/`--namespace`/
    `--config-env`)ため、`GLOBAL_VALUE_FLAGS["git"]` を別に埋める必要がある。program ごとに
    集合を分けているのは、`-p`/`-P` が `git` ではブール(`--paginate`/`--no-pager`)だが
    `glab` では値を取る(`--page`/`--per-page`)という食い違いがあるため — 共通集合に
    まとめると、一方にしか存在しない意味で他方のトークンを誤って消費する。
    """

    # --- AC1: check_no_verify(commit) ---

    def test_dash_C_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook("bash", bash_payload("git -C /tmp commit --no-verify -m x"))
        self.assertIsNotNone(reason, "-C 前置の git commit --no-verify が拒否されていない")
        self.assertIn("no-verify", reason)

    def test_git_dir_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git --git-dir /tmp/x/.git commit --no-verify -m x")
        )
        self.assertIsNotNone(reason, "--git-dir 前置の git commit --no-verify が拒否されていない")

    def test_work_tree_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook(
            "bash",
            bash_payload(
                "git --git-dir /tmp/x/.git --work-tree /tmp/x commit --no-verify -m x"
            ),
        )
        self.assertIsNotNone(
            reason, "--work-tree 前置の git commit --no-verify が拒否されていない"
        )

    def test_dash_c_config_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git -c user.name=x commit --no-verify -m x")
        )
        self.assertIsNotNone(reason, "-c 前置の git commit --no-verify が拒否されていない")

    def test_namespace_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git --namespace foo commit --no-verify -m x")
        )
        self.assertIsNotNone(
            reason, "--namespace 前置の git commit --no-verify が拒否されていない"
        )

    def test_config_env_prefixed_commit_no_verify_is_denied(self):
        reason = run_hook(
            "bash",
            bash_payload("git --config-env user.name=ENVVAR commit --no-verify -m x"),
        )
        self.assertIsNotNone(
            reason, "--config-env 前置の git commit --no-verify が拒否されていない"
        )

    # --- AC2: check_no_verify(push) ---

    def test_dash_C_prefixed_push_no_verify_is_denied(self):
        reason = run_hook("bash", bash_payload("git -C /tmp push --no-verify"))
        self.assertIsNotNone(reason, "-C 前置の git push --no-verify が拒否されていない")

    def test_git_dir_prefixed_push_no_verify_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git --git-dir /tmp/x/.git push --no-verify")
        )
        self.assertIsNotNone(reason, "--git-dir 前置の git push --no-verify が拒否されていない")

    # --- AC4: ブール型グローバルフラグを前置してもサブコマンド検出が壊れないこと ---

    def test_dash_p_boolean_prefixed_commit_no_verify_is_still_denied(self):
        """`-p` は git ではブール(`--paginate`)。直後のトークンを値として消費しないこと。

        `glab` では `-p` は値を取る(`--page`)ため、program 共通のテーブルにまとめると
        ここが誤って `commit` を飲み込み、検出漏れ(過検知の逆、#1029 が戒める失敗)になる。
        """
        reason = run_hook("bash", bash_payload("git -p commit --no-verify -m x"))
        self.assertIsNotNone(reason, "-p 前置の git commit --no-verify が拒否されていない")

    def test_dash_P_boolean_prefixed_commit_no_verify_is_still_denied(self):
        """`-P` も同様(git では `--no-pager`、glab では `--per-page`)。"""
        reason = run_hook("bash", bash_payload("git -P commit --no-verify -m x"))
        self.assertIsNotNone(reason, "-P 前置の git commit --no-verify が拒否されていない")

    def test_paginate_boolean_prefixed_commit_no_verify_is_still_denied(self):
        reason = run_hook(
            "bash", bash_payload("git --paginate commit --no-verify -m x")
        )
        self.assertIsNotNone(
            reason, "--paginate 前置の git commit --no-verify が拒否されていない"
        )

    def test_bare_boolean_prefixed_commit_no_verify_is_still_denied(self):
        reason = run_hook("bash", bash_payload("git --bare commit --no-verify -m x"))
        self.assertIsNotNone(reason, "--bare 前置の git commit --no-verify が拒否されていない")

    def test_exec_path_is_not_treated_as_value_taking(self):
        """`--exec-path` は `=` 無しでは値を取らない(bare form は値を表示して終了する)。

        誤って値取りの集合に入れると、次のトークン(ここでは `commit`)を値として飲み込み、
        `--no-verify` 禁止の検出漏れになる。
        """
        reason = run_hook(
            "bash", bash_payload("git --exec-path commit --no-verify -m x")
        )
        self.assertIsNotNone(
            reason, "--exec-path 前置の git commit --no-verify が拒否されていない"
        )

    def test_wrapped_dash_C_prefixed_commit_no_verify_is_denied(self):
        """回帰: `timeout` などの前置ラッパーとの併用でも見逃さないこと(#1029)。"""
        reason = run_hook(
            "bash", bash_payload("timeout 60 git -C /tmp commit --no-verify -m x")
        )
        self.assertIsNotNone(
            reason, "ラッパー併用の -C 前置 git commit --no-verify が拒否されていない"
        )


def _git_phase_project(*staged_rel_paths):
    """実際の git リポジトリを作り、渡したパスをステージ済みにして root を返す。

    `check_commit_phase` は `root = project_dir(payload)`(ツール呼び出しの実際の cwd)から
    `git diff --cached --name-only` を読む。`git -C <path>` 自身の対象がどこであるかは
    ここでは無関係 — 検査対象はあくまで cwd 側のリポジトリであり、それがこのテストの
    確認したいこと(`invokes()` が `-C` 越しでも `commit` を検出し、この検査自体が
    起動すること)そのものである。
    """
    root = tempfile.mkdtemp()
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    for rel in staged_rel_paths:
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("x")
    subprocess.run(["git", "add"] + list(staged_rel_paths), cwd=root, check=True)
    return root


def _run_in_git_phase_project(command, root):
    env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
    try:
        return run_hook("bash", bash_payload(command, cwd=root))
    finally:
        if env_backup is not None:
            os.environ["CLAUDE_PROJECT_DIR"] = env_backup


class GitDashCPhaseSeparation(unittest.TestCase):
    """AC3: `git -C <path> commit` でもフェーズ分離検査(`check_commit_phase`)が働くこと。"""

    def test_dash_C_prefixed_mixed_commit_is_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project("git -C /tmp commit -m x", root)
        self.assertIsNotNone(
            reason, "-C 前置の git commit がフェーズ分離検査をすり抜けている"
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_git_dir_prefixed_mixed_commit_is_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --git-dir /tmp/other/.git commit -m x", root
        )
        self.assertIsNotNone(
            reason, "--git-dir 前置の git commit がフェーズ分離検査をすり抜けている"
        )

    def test_dash_C_prefixed_test_only_commit_is_allowed(self):
        root = _git_phase_project("apps/web/e2e/features/a.feature")
        self.assertIsNone(_run_in_git_phase_project("git -C /tmp commit -m x", root))


class ShortValueFlagAttachedValueMisdetection(unittest.TestCase):
    """AC5(#1441): `_has_flag` の短縮フラグクラスタ判定が、値を取る短縮フラグに直結

    した値の文字を、別の無関係な短縮フラグと誤認しないこと。ここでは
    `check_commit_phase` の `--all`/`-a` 判定を対象にする。
    """

    def test_attached_message_value_containing_a_is_not_mistaken_for_all(self):
        """`-m'Add bar'`(値に小文字 a を含む直結形)を `-a` と誤認しないこと。

        誤認すると `check_commit_phase` が未ステージの変更(`git diff --name-only`)
        まで分類対象に加え、ステージ内容は test のみなのに production の未ステージ
        変更が混ざって「混在」と誤って拒否されうる。
        """
        root = tempfile.mkdtemp()
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)

        prod_path = os.path.join(root, "services/foo/src/Bar.java")
        os.makedirs(os.path.dirname(prod_path), exist_ok=True)
        with open(prod_path, "w") as f:
            f.write("x")
        subprocess.run(["git", "add", "services/foo/src/Bar.java"], cwd=root, check=True)
        subprocess.run(
            ["git", "commit", "-q", "-m", "seed"], cwd=root, check=True
        )
        # production ファイルの未ステージの変更(-a と誤認されると巻き込まれる)。
        with open(prod_path, "w") as f:
            f.write("y")

        test_path = os.path.join(root, "apps/web/e2e/features/a.feature")
        os.makedirs(os.path.dirname(test_path), exist_ok=True)
        with open(test_path, "w") as f:
            f.write("z")
        subprocess.run(["git", "add", "apps/web/e2e/features/a.feature"], cwd=root, check=True)

        reason = _run_in_git_phase_project("git commit -m'Add bar'", root)
        self.assertIsNone(
            reason,
            "-m の値中の a を --all と誤認し、未ステージの production 変更を"
            "混入させて誤って混在と判定している: %s" % reason,
        )


class WriteEditSilencerDenial(unittest.TestCase):
    """#1219: `cmd_write` の `SILENCERS` 拒否と、その免除パスを検査する。

    黙殺パターンを Write/Edit で追加しようとすると deny される経路には、これまで
    単体テストが無かった。ここが壊れても pre-commit 側(`FeatureSkipTagIsRejected`)は
    別経路なので、guard.py 側の空振りは誰にも検出されない。
    """

    # 黙殺パターンそのものをこのファイルに書くと pre-commit の SILENCERS に自分自身が
    # 引っかかるので、実行時に組み立てる。
    TEST_SKIP = "test" + ".skip('x', () => {});\n"
    IT_SKIP = "it" + ".skip('x', () => {});"
    SKIP_TAG = "@" + "skip\nシナリオ: x\n"

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.session = "silencer-test-session"

    def _write(self, rel_path, content, key="content"):
        target = os.path.join(self.root, rel_path)
        return _run_in_root("write", {"file_path": target, key: content}, self.root, self.session)

    def test_write_with_test_skip_in_test_file_is_denied(self):
        reason = self._write("apps/web/src/foo.test.ts", self.TEST_SKIP)
        self.assertIsNotNone(reason)
        self.assertIn("Never skip a test", reason)

    def test_edit_with_test_skip_is_denied(self):
        reason = self._write(
            "apps/web/src/foo.test.ts", self.IT_SKIP, key="new_string"
        )
        self.assertIsNotNone(reason)

    def test_gherkin_skip_tag_is_denied(self):
        reason = self._write("apps/web/e2e/features/a.feature", self.SKIP_TAG)
        self.assertIsNotNone(reason)

    def test_denial_applies_to_production_files_too(self):
        reason = self._write("apps/web/src/app.ts", self.TEST_SKIP)
        self.assertIsNotNone(reason)

    def test_exempt_paths_are_allowed(self):
        for rel in (
            ".claude/hooks/example.py",
            "docs/GUIDE.txt",
            "scripts/helper.sh",
            "apps/web/README.md",
        ):
            with self.subTest(path=rel):
                self.assertIsNone(self._write(rel, self.TEST_SKIP))

    def test_ordinary_change_is_allowed(self):
        self.assertIsNone(
            self._write("apps/web/src/foo.test.ts", "it('works', () => { expect(1).toBe(1); });\n")
        )

    def test_empty_payload_is_allowed(self):
        self.assertIsNone(self._write("apps/web/src/foo.test.ts", ""))

    def test_path_outside_repository_is_allowed(self):
        outside = os.path.join(tempfile.mkdtemp(), "foo.test.ts")
        self.assertIsNone(
            _run_in_root(
                "write",
                {"file_path": outside, "content": self.TEST_SKIP},
                self.root,
                self.session,
            )
        )
