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
import shlex
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

    def test_squash_consumed_as_message_value_is_denied(self):
        """#1463 AC1: `-m --squash` の `--squash` は -m の値であり方式指定ではない。"""
        for cmd in (
            "glab mr merge -m --squash",
            "glab mr merge --message --squash",
            "glab mr merge -R --squash",
            "glab mr merge --sha --squash",
            "glab mr merge --squash-message --squash",
            "glab mr merge -dm --squash",
        ):
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(run_hook("bash", bash_payload(cmd)))

    def test_squash_after_message_value_is_allowed(self):
        """#1463 AC1: 値を取るフラグに値が渡されていれば、その後の --squash は有効。"""
        for cmd in (
            "glab mr merge -m msg --squash",
            "glab mr merge --squash -m msg",
            "glab mr merge --remove-source-branch --squash",
            "glab mr merge --yes -d --squash 42",
            "glab mr merge -m=msg --squash",
            "glab mr merge -mmsg -s",
        ):
            with self.subTest(cmd=cmd):
                self.assertIsNone(run_hook("bash", bash_payload(cmd)))

    def test_squash_as_value_of_unknown_flag_is_denied(self):
        """#1463 AC2: 未知のフラグの次の --squash は値かもしれないので fail-closed。"""
        for cmd in ("glab mr merge --zz --squash", "glab mr merge -z --squash"):
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(run_hook("bash", bash_payload(cmd)))

    def test_squash_message_before_rebase_still_denied(self):
        """#1463 AC3。"""
        for cmd in (
            "glab mr merge --squash -m --rebase",
            "glab mr merge",
            "glab mr merge 42 -m x",
        ):
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(run_hook("bash", bash_payload(cmd)))

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

    def test_no_verify_on_merge_is_denied(self):
        """#1465: git merge も --no-verify で pre-merge-commit を外せる。"""
        for cmd in (
            "git merge --no-verify origin/develop",
            "timeout 60 env FOO=1 git merge --no-verify origin/develop",
            "git pull --no-verify",
        ):
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(run_hook("bash", bash_payload(cmd)))

    def test_merge_short_n_and_no_stat_are_allowed(self):
        """`git merge -n` は --no-stat の短縮で、フック回避ではない。"""
        for cmd in ("git merge -n origin/develop", "git merge --no-stat origin/develop"):
            with self.subTest(cmd=cmd):
                self.assertIsNone(run_hook("bash", bash_payload(cmd)))

    def test_plain_commit_is_allowed(self):
        root = tempfile.mkdtemp()
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            # git リポジトリではないので staged の取得は失敗し、フェーズ判定はスキップされる。
            self.assertIsNone(run_hook("bash", bash_payload("git commit -m x", cwd=root)))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup


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


class EnvWrapperValueFlags(unittest.TestCase):
    """#1462: `WRAPPERS["env"]` が値取りフラグを持たず、`env -u`/`env -C` を前置する
    だけでガードが全て素通りしていたこと。

    `env -u FOO glab mr merge --rebase` は `-u` の値 `FOO` が読み飛ばされないまま
    実体コマンドの検出に混入し、`glab mr merge --rebase` が検出できなくなっていた
    (実機: `env -u FOO git --version` は rc 0 で実際に動く)。`env -C DIR` も同様。
    """

    VALUE_FLAG_BYPASSES_MUST_DENY = [
        "env -u FOO glab mr merge --rebase",
        "env -C /tmp glab mr merge --rebase",
    ]

    def test_env_value_flag_bypass_is_denied(self):
        for command in self.VALUE_FLAG_BYPASSES_MUST_DENY:
            with self.subTest(command=command):
                self.assertIsNotNone(
                    run_hook("bash", bash_payload(command)),
                    "env の値取りフラグの値混入でガードが外れた: %s" % command,
                )

    REGRESSIONS_MUST_STAY_DENIED = [
        "env -i glab mr merge --rebase",
        "timeout 60 git push --no-verify",
    ]

    def test_existing_denials_still_deny(self):
        for command in self.REGRESSIONS_MUST_STAY_DENIED:
            with self.subTest(command=command):
                self.assertIsNotNone(
                    run_hook("bash", bash_payload(command)),
                    "既存の拒否が緩んだ: %s" % command,
                )

    def test_plain_env_prefixed_squash_merge_is_allowed(self):
        """`env glab mr merge --squash --remove-source-branch` は許可のまま。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("env glab mr merge --squash --remove-source-branch"),
            )
        )

    def test_unknown_wrapper_flag_fails_closed(self):
        """Requirement 4: 未知のフラグを前置した形は、値取りかどうか判定できないので
        安全側(fail-closed)で拒否する。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("env --zz 1 glab mr merge --rebase")),
            "未知のラッパーフラグが安全側に倒れず許可された",
        )

    GLUED_SHORT_VALUE_FLAG_COMPLIANT_COMMANDS = [
        # `-C/tmp` は `env -C /tmp` の POSIX 短縮直結形。値は同じトークンに埋め込まれて
        # いるので次のトークンを消費しない。実機: `env -C/tmp pwd` は rc 0 で実際に動く。
        "env -C/tmp glab mr merge --squash --remove-source-branch",
        # `-uFOO` も同様(`env -u FOO` の直結形)。実機: `env -uFOO printenv FOO` は動く。
        "env -uFOO glab mr merge --squash --remove-source-branch",
    ]

    def test_glued_short_value_flag_with_compliant_command_is_allowed(self):
        """#1462 レビュー2回目: `-C/tmp`/`-uFOO` のような POSIX 短縮直結形の値取り
        フラグを、`=` 付き直結形(`--chdir=/tmp`)としか比較していなかったために
        「未知のラッパーフラグ」と誤判定し、コンプライアントな squash マージまで
        fail-closed で拒否していた回帰。"""
        for command in self.GLUED_SHORT_VALUE_FLAG_COMPLIANT_COMMANDS:
            with self.subTest(command=command):
                self.assertIsNone(
                    run_hook("bash", bash_payload(command)),
                    "短縮直結形の値取りフラグが未知フラグと誤判定され拒否された: %s"
                    % command,
                )

    def test_genuinely_unknown_short_flag_still_fails_closed(self):
        """`-z`(env に実在しないフラグ)は短縮直結形の救済対象にせず、引き続き
        fail-closed で拒否する — AC4 の回帰防止。"""
        self.assertIsNotNone(
            run_hook(
                "bash",
                bash_payload("env -z 1 glab mr merge --squash --remove-source-branch"),
            ),
            "実在しないフラグまで許可されてしまった",
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
            "bash",
            {"command": "glab issue create --label hotfix,status::Backlog --title x"},
            root,
            session,
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
                "bash",
                bash_payload(
                    "glab issue create --label bug,priority::P0,status::Inbox --title x"
                ),
            )
        )
        root, session = _stage_root("ready-issue")
        reason = _run_in_root(
            "bash",
            {"command": "glab issue create --label bug,priority::P0,status::Inbox --title x"},
            root,
            session,
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
            "bash",
            {"command": "glab issue create -lbug,hotfix,status::Backlog --title x"},
            root,
            session,
        )
        self.assertIsNone(reason)

    def test_create_hotfix_substring_label_is_not_mistaken(self):
        """`hotfix` は完全一致で判定する。部分一致で誤検知しないこと。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    "glab issue create --label not-a-hotfix-label,status::Inbox --title x"
                ),
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


# --------------------------------------------------------------------------- #1444


class IssueCreationRequiresStatus(unittest.TestCase):
    """CLAUDE.md → How to change status: `status::` の無い Issue はどの列にも現れない
    (「ゼロは危険な方」)。予防層(guard.py)にはこれを止める仕組みが無かった(#1444)。

    #1441 はこの経路(`status::` 無しの `glab issue create`)で起票され、約10時間
    ボードのどの列にも現れなかった。ここで検証するのは、その隙間を埋める
    **独立した新規チェック**であり、`check_status_label_integrity`(#1023、遷移専用)にも
    `check_hotfix_creation`(#1434、hotfix 専用)にも影響しないこと。
    """

    def test_create_without_status_label_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("glab issue create --title x --label priority::P2")
        )
        self.assertIsNotNone(reason, "status:: 無しの起票が許可された(#1441 の再発)")
        self.assertIn("status::", reason)

    def test_create_with_status_label_is_allowed(self):
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    "glab issue create --title x --label status::Inbox,priority::P2"
                ),
            )
        )

    def test_create_with_short_flag_attached_status_is_allowed(self):
        """`-lstatus::Inbox`(値直結)。既存の `_issue_update_label_args` をそのまま再利用する。"""
        self.assertIsNone(
            run_hook(
                "bash", bash_payload("glab issue create --title x -lstatus::Inbox")
            )
        )

    def test_create_with_equals_joined_status_is_allowed(self):
        """`--label=status::Inbox`(`=` 結合)。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab issue create --title x --label=status::Inbox"),
            )
        )

    def test_create_with_repeated_label_flag_is_allowed(self):
        """`--label` の複数回指定。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    "glab issue create --title x --label status::Inbox "
                    "--label priority::P2"
                ),
            )
        )

    def test_create_with_two_status_labels_is_denied(self):
        """Requirement 3: `status::` を2つ含む作成も拒否する。"""
        reason = run_hook(
            "bash",
            bash_payload(
                "glab issue create --title x --label status::Inbox,status::Backlog"
            ),
        )
        self.assertIsNotNone(reason, "status:: を2つ含む起票が許可された")
        self.assertIn("status::", reason)

    def test_create_without_label_flag_at_all_is_denied(self):
        """`--label` 自体が無い起票も status:: 0個として拒否する。"""
        reason = run_hook("bash", bash_payload("glab issue create --title x"))
        self.assertIsNotNone(reason, "--label 自体が無い起票が許可された")

    def test_wrapped_creation_without_status_is_still_denied(self):
        """#1029 の教訓。前置詞で外れないこと。"""
        reason = run_hook(
            "bash",
            bash_payload("timeout 60 glab issue create --title x --label priority::P2"),
        )
        self.assertIsNotNone(reason)

    def test_hotfix_creation_gate_is_unaffected(self):
        """既存の hotfix 作成ゲート(#1434)と衝突しないこと。マーカー無しは hotfix 側で拒否。"""
        reason = run_hook(
            "bash",
            bash_payload("glab issue create --title x --label hotfix,status::Backlog"),
        )
        self.assertIsNotNone(reason, "マーカー無しの hotfix 付き起票が許可された(#1434 への回帰)")
        self.assertIn("hotfix", reason)

    def test_hotfix_creation_with_status_and_marker_is_allowed(self):
        """report-bug マーカー付きなら、hotfix + status:: の組み合わせも従来どおり許可される。"""
        root, session = _stage_root("report-bug")
        reason = _run_in_root(
            "bash",
            {
                "command": (
                    "glab issue create --title x --label hotfix,status::Backlog"
                ),
            },
            root,
            session,
        )
        self.assertIsNone(reason)

    def test_status_label_integrity_transition_check_is_unaffected(self):
        """既存 Issue への正当な status:: 遷移(#1023)は従来どおり許可される。"""
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload(
                    'glab api projects/:id/issues/42 --method PUT '
                    '-f "remove_labels=status::Ready" -f "add_labels=status::In Progress"'
                ),
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
        という `invokes()` 自身の方針、#1029 に反する)。

        当初(#1435)は「サブコマンドは見えたまま」であることを、squash 必須検査が
        発火することで間接的に確かめていた — 当時 `--help` はここでは特別扱いされて
        おらず、消費されていれば見逃し(allow)、されていなければ検出(deny)という
        違いがそのまま信号になっていたため。

        #1446 で `--help`/`-h` はサブコマンドの前後どちらにあっても正しくヘルプ表示
        として allow されるようになった(cobra は永続フラグと同じくサブコマンド解決を
        済ませてからヘルプフラグを見るため、`glab --help mr merge` も実際には
        `mr merge` を実行せずヘルプを表示するだけで、AC1 の「サブコマンド一致で判定
        するどのガードにとっても操作ではない」という Goal に合致する)。そのため
        squash 必須検査の発火はもはや「トークンが消費されていない」ことの信号として
        使えない — 消費されていてもいなくても、正しい実装では同じく allow になる。

        ここでは `explain` の解析結果で `mr`/`merge` がそのままトークンとして残って
        いること(= 消費されていないこと)を直接確認し、最終判定が意図どおり allow に
        なることも合わせて確認する。
        """
        out = subprocess.run(
            [sys.executable, HOOK, "explain", "glab --help mr merge"],
            capture_output=True, text=True, timeout=60,
        ).stdout
        self.assertIn(
            "['glab', '--help', 'mr', 'merge']",
            out,
            "--help の次のトークンが消費され、mr/merge が解析結果に残っていない: %s" % out,
        )
        self.assertIn(
            "判定: allow",
            out,
            "--help によるヘルプ表示のはずなのに allow になっていない(#1446): %s" % out,
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
                    "glab --repo owner/repo issue create --title x "
                    "--label hotfix,status::Backlog"
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

    引数無しで呼ぶと、ステージ内容が空のクリーンなリポジトリを作る(#1443のクロス
    リポジトリ検証で「一方はクリーン」を作るのに使う)。

    `check_commit_phase` は本来 `root = project_dir(payload)`(ツール呼び出しの実際の
    cwd)から `git diff --cached --name-only` を読むが、#1443 以降は `git -C <path>` /
    `--git-dir` / `--work-tree` が指定されていればその対象を読む。どちらの root から
    読むかはテストごとに変わるため、ここでは「実際のリポジトリを作る」ことだけを担う。
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
    if staged_rel_paths:
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
    """AC3(#1440): `git -C <path> commit` でもフェーズ分離検査(`check_commit_phase`)が
    働くこと(=検査自体が起動すること)。

    #1440 の時点では `-C` の対象がどこであるかまでは反映していなかった(検査は常に
    cwd 側を見ていた)。ここでは `-C` の対象を自分自身(同じリポジトリ)にして、
    #1443 で対象解決が正しく行われるようになった後も自己参照の `-C` が壊れないことを
    確かめる。cwd とは異なるリポジトリを対象にする場合の検証は
    `GitDashCCrossRepoPhaseSeparation`(#1443)が担う。
    """

    def test_dash_C_prefixed_mixed_commit_is_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project("git -C %s commit -m x" % root, root)
        self.assertIsNotNone(
            reason, "-C 前置の git commit がフェーズ分離検査をすり抜けている"
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_git_dir_prefixed_mixed_commit_is_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --git-dir %s commit -m x" % os.path.join(root, ".git"), root
        )
        self.assertIsNotNone(
            reason, "--git-dir 前置の git commit がフェーズ分離検査をすり抜けている"
        )

    def test_dash_C_prefixed_test_only_commit_is_allowed(self):
        root = _git_phase_project("apps/web/e2e/features/a.feature")
        self.assertIsNone(
            _run_in_git_phase_project("git -C %s commit -m x" % root, root)
        )


class MergeCommitPhaseExemption(unittest.TestCase):
    """#1546: MERGE_HEAD があるリポジトリの `git commit` は競合解消コミットなので、

    フェーズ分離検査(`check_commit_phase`)の対象外にする。
    """

    MIXED = ("apps/web/e2e/features/a.feature", "services/foo/src/Bar.java")

    def _mark_merging(self, repo):
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True
        ).stdout.strip() or "0" * 40
        with open(os.path.join(repo, ".git", "MERGE_HEAD"), "w") as f:
            f.write(head + "\n")

    def test_mixed_stage_with_merge_head_is_allowed(self):
        repo = _git_phase_project(*self.MIXED)
        self._mark_merging(repo)
        reason = _run_in_git_phase_project("git commit --no-edit", repo)
        self.assertIsNone(reason, "MERGE_HEAD があるのに混在を拒否している: %s" % reason)

    def test_mixed_stage_without_merge_head_is_denied(self):
        repo = _git_phase_project(*self.MIXED)
        reason = _run_in_git_phase_project("git commit -m x", repo)
        self.assertIsNotNone(reason)

    def test_dash_C_target_merge_head_decides(self):
        cwd_repo = _git_phase_project(*self.MIXED)  # cwd 側: MERGE_HEAD なし
        target = _git_phase_project(*self.MIXED)
        self._mark_merging(target)
        reason = _run_in_git_phase_project("git -C %s commit --no-edit" % target, cwd_repo)
        self.assertIsNone(reason, "-C 対象の MERGE_HEAD を見ていない: %s" % reason)

    def test_dash_C_target_without_merge_head_denied_even_if_cwd_merging(self):
        cwd_repo = _git_phase_project(*self.MIXED)
        self._mark_merging(cwd_repo)
        target = _git_phase_project(*self.MIXED)
        reason = _run_in_git_phase_project("git -C %s commit -m x" % target, cwd_repo)
        self.assertIsNotNone(reason)


class GitDashCCrossRepoPhaseSeparation(unittest.TestCase):
    """#1443: `check_commit_phase` は `-C`/`--git-dir`/`--work-tree` が指す実際の対象

    リポジトリのステージ内容を検査しなければならない — cwd 側(`project_dir(payload)`)
    ではなく。cwd 側と対象側を別々の実リポジトリにして、判定が対象側の内容だけで
    決まることを確認する。
    """

    # --- AC1 ---

    def test_ac1_dash_c_target_mixed_cwd_clean_is_denied(self):
        repo_a = _git_phase_project()  # cwd 側: クリーン
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git -C %s commit -m x" % repo_b, repo_a
        )
        self.assertIsNotNone(
            reason,
            "-C の対象(repoB)が混在ステージなのに、cwd(repoA)がクリーンだからと"
            "見逃している",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_ac1_relative_dash_c_target_mixed_is_denied(self):
        """相対パスの `-C` も cwd(repoA)基準で解決され、対象(repoB)を正しく指すこと。"""
        parent = tempfile.mkdtemp()
        repo_a = os.path.join(parent, "repoA")
        os.makedirs(repo_a)
        subprocess.run(["git", "init", "-q"], cwd=repo_a, check=True)
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        rel = os.path.relpath(repo_b, repo_a)
        reason = _run_in_git_phase_project("git -C %s commit -m x" % rel, repo_a)
        self.assertIsNotNone(
            reason,
            "相対パスの -C 対象(repoB)が混在ステージなのに見逃している(cwd 基準の"
            "解決に問題がある可能性)",
        )

    # --- AC2 ---

    def test_ac2_dash_c_target_clean_cwd_mixed_is_allowed(self):
        repo_a = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        repo_b = _git_phase_project()  # -C の対象: クリーン
        reason = _run_in_git_phase_project(
            "git -C %s commit -m x" % repo_b, repo_a
        )
        self.assertIsNone(
            reason,
            "-C の対象(repoB)はクリーンなのに、無関係な cwd(repoA)の混在ステージで"
            "誤って拒否している: %s" % reason,
        )

    # --- AC3: --git-dir / --work-tree の組み合わせでも同様 ---

    def test_ac3_git_dir_work_tree_target_mixed_is_denied(self):
        repo_a = _git_phase_project()
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --git-dir %s --work-tree %s commit -m x"
            % (os.path.join(repo_b, ".git"), repo_b),
            repo_a,
        )
        self.assertIsNotNone(
            reason,
            "--git-dir/--work-tree の対象(repoB)が混在ステージなのに見逃している",
        )

    def test_ac3_git_dir_work_tree_target_clean_is_allowed(self):
        repo_a = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        repo_b = _git_phase_project()
        reason = _run_in_git_phase_project(
            "git --git-dir %s --work-tree %s commit -m x"
            % (os.path.join(repo_b, ".git"), repo_b),
            repo_a,
        )
        self.assertIsNone(
            reason,
            "--git-dir/--work-tree の対象(repoB)はクリーンなのに誤って拒否している: "
            "%s" % reason,
        )

    def test_ac3_multiple_dash_c_accumulate_relative_to_previous(self):
        """`-C` を複数指定した場合、git 自身の解釈どおり前の `-C` の結果に対して次を

        解決すること(自前でパスを組み立てて累積を再実装していないことの確認)。
        """
        parent = tempfile.mkdtemp()
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        repo_a = _git_phase_project()
        # 1つ目の -C は repoB の親ディレクトリへ、2つ目は repoB の basename への相対指定。
        # 累積して解決されて初めて repoB を指す。
        cmd = "git -C %s -C %s commit -m x" % (
            os.path.dirname(repo_b),
            os.path.basename(repo_b),
        )
        reason = _run_in_git_phase_project(cmd, repo_a)
        self.assertIsNotNone(
            reason,
            "複数の -C の累積解決(前の -C の結果基準)が効いておらず、repoB の混在を"
            "見逃している",
        )

    # --- AC4: `-C` 等の指定が無い通常の `git commit` の挙動に回帰が無いこと ---

    def test_ac4_plain_commit_no_prefix_mixed_is_still_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project("git commit -m x", root)
        self.assertIsNotNone(reason, "通常の git commit の混在検出に回帰がある")
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_ac4_plain_commit_no_prefix_test_only_is_allowed(self):
        root = _git_phase_project("apps/web/e2e/features/a.feature")
        reason = _run_in_git_phase_project("git commit -m x", root)
        self.assertIsNone(reason, "通常の git commit の許可判定に回帰がある: %s" % reason)


class LeadingGlobalGitFlagsBooleanAndEqualsForm(unittest.TestCase):
    """#1448: `_leading_global_git_flags()`(#1443)のうち、`GLOBAL_VALUE_FLAGS["git"]` に

    一致しない `-` 始まりトークン(ブール型のグローバルフラグ、`--git-dir=path` のような
    `=` 結合形)を「値を読み飛ばさずに1トークンだけ消費してプレフィックスに含める」分岐
    を通る回帰テスト。この分岐は #1443 の時点では手動確認のみで、committed なテストが
    無かった。

    `--bare` は #1443 の QA が「`-C` と併用すると probe と本物の commit が同一に fail-open
    する」と評価しているため、ブール型の例には副作用の無い `--paginate` を使う。

    `=` 結合形は2つ(`--git-dir=<path> --work-tree=<path>`)を同時に渡すシナリオに加え、
    単独の1トークンだけを渡すシナリオも用意する(#1448 レビュー、2026-09-27): フォール
    スルー分岐の `i += 1` を `i += 2` に食い過ぎさせるミューテーションは、`=` 結合形
    トークンがちょうど2つ並ぶケースでは正しい境界に landing してしまい見逃されるが、
    単独の1トークンなら見逃しが再現する。
    """

    def test_ac1_boolean_flag_prefixed_dash_c_target_mixed_cwd_clean_is_denied(self):
        """ブール型グローバルフラグ(`--paginate`)前置でも、`-C` の対象(cwdとは別の

        repoB)の混在ステージが正しく検出されること。cwd(repoA)はクリーンにしておき、
        cwd 側を見てしまう退行(あるいは `--paginate` の次のトークン `-C` を値として
        読み飛ばしてしまう退行)なら見逃されて許可されてしまう。
        """
        repo_a = _git_phase_project()  # cwd 側: クリーン
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --paginate -C %s commit -m x" % repo_b, repo_a
        )
        self.assertIsNotNone(
            reason,
            "ブール型グローバルフラグ(--paginate)前置の -C 対象(repoB)が混在ステージ"
            "なのに、cwd(repoA)がクリーンだからと見逃している",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_ac2_git_dir_equals_form_target_mixed_cwd_clean_is_denied(self):
        """`--git-dir=<path>` `--work-tree=<path>`(`=` 結合形)でも、対象(cwdとは別の

        repoB)の混在ステージが正しく検出されること。`=` 結合形は
        `GLOBAL_VALUE_FLAGS["git"]` の要素(空白区切り形)とは文字列として一致しない
        ため、誤って境界(サブコマンド)と扱って break すると cwd(repoA、クリーン)を
        見てしまい、混在を見逃して許可してしまう。
        """
        repo_a = _git_phase_project()  # cwd 側: クリーン
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --git-dir=%s --work-tree=%s commit -m x"
            % (os.path.join(repo_b, ".git"), repo_b),
            repo_a,
        )
        self.assertIsNotNone(
            reason,
            "--git-dir=<path>(= 結合形)の対象(repoB)が混在ステージなのに、"
            "cwd(repoA)がクリーンだからと見逃している",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_ac2_git_dir_equals_form_alone_target_mixed_cwd_clean_is_denied(self):
        """`--git-dir=<path>` を(`--work-tree=` を伴わず)単独で使っても、対象

        (cwdとは別のrepoB)の混在ステージが正しく検出されること。

        `test_ac2_git_dir_equals_form_target_mixed_cwd_clean_is_denied` は
        `--git-dir=<path> --work-tree=<path>` を常に2つ同時に渡しているため、
        フォールスルー分岐の `i += 1` を `i += 2` に食い過ぎさせるミューテーションを
        見逃す(#1448 レビュー、2026-09-27): `=` 結合形トークンがちょうど2つ並ぶと、
        最初のトークンで index を2進めても2トークン分正しく進めたのと同じ境界
        (`commit` の手前)に landing してしまうため。単独の `=` 結合形(このトークン
        1つだけを消費してから `commit` に到達する)なら、食い過ぎが1トークン分の
        ずれとして現れ、`commit` そのものをプレフィックスに巻き込んで
        `git diff --cached` が失敗し、`reason` が `None` になって見逃しが再現する。

        `--work-tree=<path>` 単独ではなく `--git-dir=<path>` 単独を選んだ理由:
        `--git-dir` を指定しない場合、git は起動時の cwd から `.git` を discovery
        するため、`--work-tree=<path>` だけを cwd(repoA)から渡しても参照される
        リポジトリは repoA の `.git` のままで、repoB の staged 内容を全く検出できず
        (実地確認: cwd=repoA で `git --work-tree=repoB diff --cached --name-only`
        は空を返す)、そもそもクロスリポジトリ検出のテストとして成立しない。
        `--git-dir=<path>` 単独なら GIT_DIR が明示され、work tree の状態に関係なく
        `diff --cached --name-only` は repoB の索引を正しく読む(実地確認済み)。
        """
        repo_a = _git_phase_project()  # cwd 側: クリーン
        repo_b = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --git-dir=%s commit -m x" % os.path.join(repo_b, ".git"),
            repo_a,
        )
        self.assertIsNotNone(
            reason,
            "--git-dir=<path>(= 結合形、単独)の対象(repoB)が混在ステージなのに、"
            "cwd(repoA)がクリーンだからと見逃している",
        )
        self.assertIn("テストコードとプロダクションコード", reason)


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


def _repo_with_staged_test_and_unstaged_prod_change():
    """テストファイルのみをステージし、production ファイルには未ステージの変更を
    残したリポジトリを作る(#1445)。

    本物の `-a`/`-am` はこの未ステージ変更を拾うので混在として拒否されるべきで
    (AC3)、値直結の短縮フラグ(`-uall`/`-tfeature-a.txt`)がこれと誤認されなければ
    拾われず、ステージ済みのテストのみのコミットとして許可されるべき(AC1/AC2)。
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
    subprocess.run(["git", "commit", "-q", "-m", "seed"], cwd=root, check=True)
    # production ファイルの未ステージの変更(本物の -a / -am で拾われる)。
    with open(prod_path, "w") as f:
        f.write("y")

    test_path = os.path.join(root, "apps/web/e2e/features/a.feature")
    os.makedirs(os.path.dirname(test_path), exist_ok=True)
    with open(test_path, "w") as f:
        f.write("z")
    subprocess.run(["git", "add", "apps/web/e2e/features/a.feature"], cwd=root, check=True)
    return root


class GitCommitShortValueFlagsCoverage(unittest.TestCase):
    """#1445: `SHORT_VALUE_FLAGS[("git","commit")]` は `git commit -h` が挙げる、値を
    取る短縮フラグ8つ(`m`/`F`/`c`/`C`/`S`/`t`/`U`/`u`)を網羅しなければならない。

    欠けているフラグの値に `a` が含まれると `_has_flag(args, "--all", "a", ...)` が
    `-a` と誤認し、`check_commit_phase` が未ステージの変更まで巻き込んで正当な
    コミットを誤って「混在」と拒否する(fail-closed)。`-t`/`-U`/`-u` の3つが
    修正前の集合 `{m, F, c, C, S}` に無かった。
    """

    def test_dash_u_all_attached_value_is_not_mistaken_for_all(self):
        """AC1: `-uall`(--untracked-files=all の直結形)を `-a` と誤認しないこと。"""
        root = _repo_with_staged_test_and_unstaged_prod_change()
        reason = _run_in_git_phase_project("git commit -uall -m x", root)
        self.assertIsNone(
            reason,
            "-u の値 'all' 中の a を --all と誤認し、未ステージの production 変更を"
            "混入させて誤って混在と判定している: %s" % reason,
        )

    def test_dash_t_attached_value_containing_a_is_not_mistaken_for_all(self):
        """AC2: `-tfeature-a.txt`(--template の直結形、値に a を含む)を `-a` と
        誤認しないこと。"""
        root = _repo_with_staged_test_and_unstaged_prod_change()
        reason = _run_in_git_phase_project("git commit -tfeature-a.txt -m x", root)
        self.assertIsNone(
            reason,
            "-t の値中の a を --all と誤認し、未ステージの production 変更を"
            "混入させて誤って混在と判定している: %s" % reason,
        )

    def test_real_dash_a_flag_mixed_is_still_denied(self):
        """AC3: 本物の `-a`(--all)は引き続き検出され、混在として拒否されること。"""
        root = _repo_with_staged_test_and_unstaged_prod_change()
        reason = _run_in_git_phase_project("git commit -a -m x", root)
        self.assertIsNotNone(
            reason, "本物の -a の検出に回帰があり、混在が見逃されている"
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_real_dash_am_cluster_mixed_is_still_denied(self):
        """AC3: 本物の `-am`(-a と -m のクラスタ結合)は引き続き検出され、混在として
        拒否されること。"""
        root = _repo_with_staged_test_and_unstaged_prod_change()
        reason = _run_in_git_phase_project("git commit -am x", root)
        self.assertIsNotNone(
            reason, "-am クラスタでの -a 検出に回帰があり、混在が見逃されている"
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_short_value_flags_for_git_commit_covers_all_eight(self):
        """AC4: 集合が `git commit -h` の値を取る短縮フラグ8つを網羅していること。"""
        hooks_dir = os.path.dirname(HOOK)
        if hooks_dir not in sys.path:
            sys.path.insert(0, hooks_dir)
        import guard

        self.assertEqual(
            guard.SHORT_VALUE_FLAGS[("git", "commit")],
            {"m", "F", "c", "C", "S", "t", "U", "u"},
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


# --------------------------------------------------------------------------- #1446


class HelpInvocationIsNotAnOperation(unittest.TestCase):
    """`invokes()` にサブコマンドを渡すどのガードも、`--help`/`-h` だけの呼び出しを

    操作として扱わないこと(#1446)。

    `invokes(command, program, subcommands)` はサブコマンドの一致だけを見ており、
    `--help`/`-h` を特別扱いしない。その結果、ヘルプ表示だけの呼び出しが実際の操作と
    同じ理由で拒否されたり(`mr merge`/`issue create`)、カバレッジ計測を実際に
    走らせたり(`mr create`)していた。ここで直すのは `invokes()` 自身であり、
    `subcommands` を渡す呼び出し元すべてに一律に効く。
    """

    # --- AC1: `glab mr merge --help` / `-h` は許可される ---

    def test_mr_merge_help_long_flag_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge --help")))

    def test_mr_merge_help_short_flag_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge -h")))

    # --- AC2: `glab issue create --help` は許可される(status:: 必須チェック不発火) ---

    def test_issue_create_help_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab issue create --help")))

    # --- AC3: `glab mr create --help` で Coverage Gate が発火しない ---

    def test_mr_create_help_does_not_trigger_coverage_check(self):
        """カバレッジ検査スクリプトが実際に起動しないこと。

        スクリプトを「必ず拒否する」ものにしておき、それでも許可されることで
        `check_pr_coverage` が early return し、スクリプトを一切起動していないことを
        確かめる(#1446 が問題にしているのは「カバレッジ計測が実際に走る」こと自体)。
        """
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write("import sys\nsys.exit(1)\n")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook("bash", bash_payload("glab mr create --help", cwd=root))
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNone(
            reason, "glab mr create --help でカバレッジ検査スクリプトが起動し拒否された"
        )

    def test_mr_create_help_explain_does_not_report_coverage_check(self):
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "glab mr create --help"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertNotIn("カバレッジ検査が走る", proc.stdout)
        self.assertIn("判定: allow", proc.stdout)

    # --- AC4: --help 無しの既存判定は変わらない ---

    def test_bare_mr_merge_is_still_denied(self):
        reason = run_hook("bash", bash_payload("glab mr merge"))
        self.assertIsNotNone(reason, "方式未指定の glab mr merge が拒否されていない")
        self.assertIn("squash", reason)

    def test_mr_merge_squash_and_remove_source_branch_is_still_allowed(self):
        self.assertIsNone(
            run_hook(
                "bash",
                bash_payload("glab mr merge --squash --remove-source-branch"),
            )
        )

    # --- AC5: status:: 無しの issue create は引き続き拒否される ---

    def test_issue_create_without_status_and_without_help_is_still_denied(self):
        reason = run_hook(
            "bash", bash_payload("glab issue create --title x --label priority::P2")
        )
        self.assertIsNotNone(reason, "status:: 無しの起票が誤って許可された")
        self.assertIn("status::", reason)

    # --- fail-open の防止(必須): -h の誤検知は許されない ---

    def test_attached_repo_value_starting_with_h_is_not_mistaken_for_help(self):
        """`-Rh/x`(--repo の値が `h/x`)を `-h` と誤認しないこと。

        誤認すると squash 必須検査そのものがスキップされ、方式未指定のマージが
        通ってしまう(fail-open)。#1441 の `_cluster_has_flag` と同じ「値を取る
        短縮フラグに出会ったらそこで走査を打ち切る」方式で防ぐ。
        """
        reason = run_hook("bash", bash_payload("glab mr merge -Rh/x"))
        self.assertIsNotNone(
            reason, "-Rh/x がヘルプと誤認され、squash 必須検査がスキップされた(fail-open)"
        )
        self.assertIn("squash", reason)

    def test_attached_repo_value_containing_help_is_not_mistaken_for_help(self):
        """`-Rhelp/repo`(--repo の値が `help/repo`)も同様。"""
        reason = run_hook("bash", bash_payload("glab mr merge -Rhelp/repo"))
        self.assertIsNotNone(
            reason,
            "-Rhelp/repo がヘルプと誤認され、squash 必須検査がスキップされた(fail-open)",
        )
        self.assertIn("squash", reason)

    def test_message_value_flag_key_is_actually_looked_up(self):
        """`SHORT_VALUE_FLAGS[("glab", "mr merge")]` の `m` エントリが実際に引かれること。

        `-mhelp`(`-m` の値直結形、値が `help`)は `-R` を経由しないので、
        `GLOBAL_VALUE_FLAGS` 由来のフォールバックだけでは守られない。ここで拒否される
        (squash 未指定)ことは、`invokes()` が `SHORT_VALUE_FLAGS` の**サブコマンド固有の
        エントリ**を実際に引いていることの証拠になる — 引けずに空集合だと `m` が
        ブールとして走査され、直後の `h` を `-h` と誤認して許可されてしまう。
        """
        reason = run_hook("bash", bash_payload("glab mr merge -mhelp"))
        self.assertIsNotNone(
            reason,
            "-mhelp がヘルプと誤認された。SHORT_VALUE_FLAGS[('glab','mr merge')] の "
            "'m' エントリが invokes() 内で引けていない(fail-open)",
        )
        self.assertIn("squash", reason)

    # --- 既存判定の無傷確認(回帰) ---

    def test_rebase_is_still_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("glab mr merge --rebase")))

    def test_repo_flag_before_subcommand_is_still_denied_without_squash(self):
        """#1435: グローバルフラグの前置は引き続き検出される。"""
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab --repo o/r mr merge"))
        )

    def test_attached_repo_value_with_squash_is_still_allowed(self):
        """#1441: 値直結の -R は squash 指定を隠さない。"""
        self.assertIsNone(
            run_hook("bash", bash_payload("glab -Rowner/repo mr merge --squash"))
        )

    def test_attached_repo_value_containing_s_still_denied(self):
        """#1441: 値中の s を squash 済みと誤認しない。"""
        reason = run_hook("bash", bash_payload("glab -Rsss mr merge"))
        self.assertIsNotNone(reason)

    def test_git_dash_c_no_verify_commit_still_denied(self):
        """#1440: git 側のグローバルフラグ前置も引き続き検出される。"""
        reason = run_hook("bash", bash_payload("git -C /tmp commit --no-verify -m x"))
        self.assertIsNotNone(reason)

    def test_hotfix_creation_gate_without_marker_still_denied(self):
        """#1434: hotfix ゲートは --help と無関係にそのまま働く。"""
        reason = run_hook(
            "bash",
            bash_payload("glab issue create --title x --label hotfix,status::Backlog"),
        )
        self.assertIsNotNone(reason)
        self.assertIn("hotfix", reason)

    # --- Out of Scope 側(git の --help)。中央修正が効くかどうかの実測記録であり、
    #     受入基準ではない(Issue #1446 の Out of Scope)。

    def test_git_commit_help_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("git commit --help")))

    def test_git_push_help_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("git push --help")))

    def test_issue_update_with_extra_positional_and_help_is_not_treated_as_help_by_design(
        self,
    ):
        """`glab issue update 42 --label hotfix --help` の実測結果を記録する。

        レビュー2回目(#1446)が確定させた規則は「`rest` の全トークンが `--help`/`-h`
        であること」を要求する。この呼び出しは位置引数 `42` と `--label hotfix` が
        `--help` と共存しており、`rest` が `--help` だけにならないため、この実装では
        ヘルプ呼び出しと認識されない(= 通常の `issue update` として扱われ、
        `hotfix` ラベルの付与が検出されて拒否される)。

        当初(#1446 初回対応時点)は「ヘルプ表示は実際には hotfix を付け外ししない
        はずなので許可を期待値として固定する」としていたが、#1446 レビュー2回目が
        `glab mr merge 42 --help` について明示的に受け入れた「位置引数が残る形は
        意図的に誤拒否する(fail-closed)」というトレードオフは、この呼び出しにも
        同じ理由で一様に適用される。個々の呼び出し文脈ごとに例外を設けると、結局
        「この文脈は安全に見えるから」という curated な判断へ逆戻りしてしまうため、
        本テストの期待値を拒否に更新する。
        """
        reason = run_hook(
            "bash",
            bash_payload("glab issue update 42 --label hotfix --help"),
        )
        self.assertIsNotNone(
            reason,
            "42 --label hotfix --help が意図に反してヘルプ扱いされ、"
            "hotfix 不変性検査がスキップされた",
        )
        self.assertIn("hotfix", reason)


# ------------------------------------------------------------- #1446 Review差し戻し


class HelpDetectionDoesNotFailOpenOnAttachedShortFlagValues(unittest.TestCase):
    """#1446 レビュー(2026-09-27、1回目、BLOCKING)への対応。

    curated な `SHORT_VALUE_FLAGS` を土台にした `_is_help_invocation` のクラスタ判定は、
    値直結の短縮フラグ(`-l<label>`/`-m<message>`/`-t<template>` 等)がリストに無い
    呼び出し文脈では、値の中の `h` を `-h` と誤認して allow に倒れていた(fail-open)。
    `invokes()` を呼ぶ8箇所の呼び出し元それぞれについて、これが起きないことを確認する。
    `glab mr merge`(呼び出し元1/8)は既存の
    `test_attached_repo_value_starting_with_h_is_not_mistaken_for_help` /
    `test_attached_repo_value_containing_help_is_not_mistaken_for_help` /
    `test_message_value_flag_key_is_actually_looked_up` が担っており、ここでは残り
    7箇所を確認する。
    """

    # --- 呼び出し元2/8: check_hotfix_label_immutability(`glab issue update`) ---
    # レビュー本文の再現コマンドそのもの。

    def test_issue_update_hotfix_label_with_attached_h_value_is_denied(self):
        reason = run_hook(
            "bash",
            bash_payload("glab issue update 42 --label hotfix -mhelp"),
        )
        self.assertIsNotNone(
            reason,
            "-mhelp がヘルプと誤認され、既存 Issue への hotfix 付与が許可された"
            "(fail-open)",
        )
        self.assertIn("hotfix", reason)

    # --- 呼び出し元3/8: check_hotfix_creation(`glab issue create` の hotfix ゲート) ---

    def test_issue_create_hotfix_label_with_attached_h_value_is_denied(self):
        reason = run_hook(
            "bash",
            bash_payload("glab issue create --title x --label hotfix -mhelp"),
        )
        self.assertIsNotNone(
            reason,
            "-mhelp がヘルプと誤認され、hotfix 付きの Issue 作成ゲートが"
            "スキップされた(fail-open)",
        )
        self.assertIn("hotfix", reason)

    # --- 呼び出し元4/8: check_issue_creation_requires_status(`glab issue create`) ---
    # レビュー本文の再現コマンドそのもの。

    def test_issue_create_without_status_with_attached_h_value_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("glab issue create --title x -mhelp")
        )
        self.assertIsNotNone(
            reason,
            "-mhelp がヘルプと誤認され、status:: 必須検査がスキップされた(fail-open)",
        )
        self.assertIn("status::", reason)

    # --- 呼び出し元5/8: check_no_verify(`git commit`) ---
    # レビュー本文の再現コマンドそのもの。

    def test_git_commit_no_verify_with_attached_h_value_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git commit --no-verify -thelp")
        )
        self.assertIsNotNone(
            reason,
            "-thelp がヘルプと誤認され、--no-verify 禁止検査がスキップされた"
            "(fail-open)",
        )
        self.assertIn("no-verify", reason)

    # --- 呼び出し元6/8: check_no_verify(`git push`) ---
    # `git push` には値を取る短縮フラグの専用エントリが無く、`-u`(--set-upstream、
    # ブール)の直後の `h` を拾う経路がある。

    def test_git_push_no_verify_with_attached_h_value_is_denied(self):
        reason = run_hook(
            "bash", bash_payload("git push --no-verify -uhelp")
        )
        self.assertIsNotNone(
            reason,
            "-uhelp がヘルプと誤認され、--no-verify 禁止検査がスキップされた"
            "(fail-open)",
        )
        self.assertIn("no-verify", reason)

    # --- 呼び出し元7/8: check_commit_phase(`git commit`) ---
    # `invokes()` がヘルプと誤認して空リストを返すと、`check_commit_phase` は
    # コミットが無いものとして早期returnし、フェーズ分離の混在検査自体が丸ごと
    # スキップされる。

    def test_git_commit_phase_separation_with_attached_h_value_is_denied(self):
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project("git commit -m x -thelp", root)
        self.assertIsNotNone(
            reason,
            "-thelp がヘルプと誤認され、フェーズ分離検査(check_commit_phase)自体が"
            "スキップされた(fail-open)",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    # --- 呼び出し元8/8: check_pr_coverage(`glab mr create`)、および explain の同判定 ---
    # レビュー本文の再現コマンドそのもの。

    def test_mr_create_with_attached_h_value_triggers_coverage_check(self):
        """カバレッジ検査スクリプトが実際に起動すること(必ず拒否するスクリプトで確認)。"""
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write("import sys\nsys.exit(1)\n")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash", bash_payload("glab mr create --title x -lhotfix", cwd=root)
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(
            reason,
            "-lhotfix がヘルプと誤認され、カバレッジ検査スクリプトが起動しなかった"
            "(fail-open)",
        )
        self.assertIn("カバレッジ", reason)

    def test_mr_create_with_attached_h_value_explain_reports_coverage_check(self):
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", "glab mr create --title x -lhotfix"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn(
            "カバレッジ検査が走る",
            proc.stdout,
            "-lhotfix がヘルプと誤認され、explain がカバレッジ検査の発火を"
            "報告しなかった(fail-open)",
        )


# ------------------------------------------------------------- #1446 Review差し戻し(2回目)


class HelpDetectionDoesNotMistakeAValueTokenForARealHelpFlag(unittest.TestCase):
    """#1446 レビュー(2026-09-27、2回目、BLOCKING)への対応。

    #1446 レビュー1回目の対応で `_is_help_invocation` は「クラスタ内の文字一致」から「独立したトークン
    としての完全一致」に切り替わったが、それでもなお `"--help" in rest or "-h" in rest`
    という **いずれか1つ含まれていれば真** の判定だったため、`-h` が「直前の値取り
    フラグの値」として独立トークンで渡された形(`git commit -m -h`、`--title -h` 等)を、
    本物の `-h` と区別できなかった(レビュー本文より)。

    呼び出し元が確定させた規則: ヘルプ呼び出しと見なすのは、`rest` の**すべての**
    トークンが `--help` または `-h` であり、かつ1つ以上存在するときだけ。ここでは、
    レビュー本文が示した表の「ヘルプでない → DENY / 発火」の8行すべてを確認する。
    """

    # --- 呼び出し元5/8 相当: check_no_verify(`git commit`)。レビュー本文の再現コマンド。

    def test_dash_m_value_h_with_no_verify_is_denied(self):
        """`-m -h`: `-h` は `-m` の値であって本物のヘルプフラグではない。"""
        reason = run_hook(
            "bash", bash_payload("git commit -m -h --no-verify")
        )
        self.assertIsNotNone(
            reason,
            "-m の値である -h がヘルプと誤認され、--no-verify 禁止検査がスキップ"
            "された(fail-open)",
        )
        self.assertIn("no-verify", reason)

    def test_dash_dash_message_value_h_with_no_verify_is_denied(self):
        """`--message -h`: 長いフラグの値としての `-h` も同様。"""
        reason = run_hook(
            "bash", bash_payload("git commit --message -h --no-verify")
        )
        self.assertIsNotNone(
            reason,
            "--message の値である -h がヘルプと誤認され、--no-verify 禁止検査が"
            "スキップされた(fail-open)",
        )
        self.assertIn("no-verify", reason)

    # --- 呼び出し元2/8 相当: check_hotfix_label_immutability(`glab issue update`)

    def test_issue_update_hotfix_label_with_title_value_h_is_denied(self):
        """`--title -h`: `-h` は `--title` の値。既存 Issue への hotfix 付与は拒否対象。"""
        reason = run_hook(
            "bash",
            bash_payload("glab issue update 42 --label hotfix --title -h"),
        )
        self.assertIsNotNone(
            reason,
            "--title の値である -h がヘルプと誤認され、既存 Issue への hotfix 付与が"
            "許可された(fail-open)",
        )
        self.assertIn("hotfix", reason)

    # --- 呼び出し元3/8 相当: check_hotfix_creation(`glab issue create` の hotfix ゲート)

    def test_issue_create_hotfix_label_with_title_value_h_is_denied(self):
        reason = run_hook(
            "bash",
            bash_payload("glab issue create --title -h --label hotfix"),
        )
        self.assertIsNotNone(
            reason,
            "--title の値である -h がヘルプと誤認され、hotfix 付きの Issue 作成"
            "ゲートがスキップされた(fail-open)",
        )
        self.assertIn("hotfix", reason)

    # --- 呼び出し元8/8 相当: check_pr_coverage(`glab mr create`)

    def test_mr_create_title_value_h_with_short_label_triggers_coverage_check(self):
        """`--title -h -lhotfix`: `-h` は `--title` の値であって、カバレッジゲートは
        引き続き発火しなければならない。"""
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write("import sys\nsys.exit(1)\n")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab mr create --title -h -lhotfix", cwd=root),
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(
            reason,
            "--title の値である -h がヘルプと誤認され、カバレッジ検査スクリプトが"
            "起動しなかった(fail-open)",
        )
        self.assertIn("カバレッジ", reason)

    # --- `--` 以降の pathspec 位置にある `-h`(レビュー本文の再現コマンド)

    def test_no_verify_with_pathspec_looking_like_h_after_dashdash_is_denied(self):
        """`-- -h`: `--` 以降なので pathspec の可能性すらあり、なおのことヘルプではない。"""
        reason = run_hook(
            "bash", bash_payload("git commit --no-verify -m x -- -h")
        )
        self.assertIsNotNone(
            reason,
            "-- 以降の -h がヘルプと誤認され、--no-verify 禁止検査がスキップされた"
            "(fail-open)",
        )
        self.assertIn("no-verify", reason)

    # --- 本物の -h と他のフラグが同居する形(独立トークンとしての -h 自体は本物)

    def test_short_help_flag_coexisting_with_rebase_is_not_treated_as_help(self):
        """`-h --rebase`: `-h` 自体は本物のヘルプフラグだが、`--rebase` という他の
        トークンが残っている以上、この呼び出し全体は「ヘルプだけ」ではない。
        `--rebase` の禁止検査がそのまま働くこと。"""
        reason = run_hook("bash", bash_payload("glab mr merge -h --rebase"))
        self.assertIsNotNone(
            reason, "-h --rebase がヘルプ全体として扱われ、--rebase の禁止検査が"
            "スキップされた"
        )
        self.assertIn("squash", reason)

    # --- 意図的に受け入れる誤拒否(fail-closed側のトレードオフ) ---

    def test_positional_arg_with_help_is_not_treated_as_help_by_design(self):
        """`glab mr merge 42 --help`: 位置引数 `42`(MR番号)が残っている以上、
        `rest` の全トークンが `--help`/`-h` ではなくなるため、この実装ではヘルプ
        呼び出しと認識されない(=squash 未指定の通常のマージ操作として拒否される)。

        実際の cobra の挙動では `--help` があればヘルプだけを表示し `42` の
        マージは実行されないはずなので、これは無害な過拒否(fail-closed)である。
        値を取る短縮フラグを curated に列挙したリストの網羅性に依存する設計へ
        戻らないためのトレードオフとして、呼び出し元(2026-09-27 #1446 レビュー2回目)が
        明示的に受け入れた。fail-open ではなく、実害はない。
        """
        reason = run_hook("bash", bash_payload("glab mr merge 42 --help"))
        self.assertIsNotNone(
            reason,
            "42 --help が意図に反してヘルプ扱いされ、squash 必須検査がスキップ"
            "された",
        )
        self.assertIn("squash", reason)


# --------------------------------------------------------------------------- #1450


class PerSubcommandGlobalValueFlagConflict(unittest.TestCase):
    """`invokes()` の残余(`residual`)計算は、`GLOBAL_VALUE_FLAGS[program]` をプログラム単位
    (`glab`/`git`)で一律に適用し、トークンが「値を取る」かどうかがサブコマンドごとに違う
    ケースを区別しない(#1450)。

    `glab issue update` の `-p` は実際には `--public`(値を取らないブール、実測は
    `glab issue update --help`)だが、`GLOBAL_VALUE_FLAGS["glab"]` は `-p` を常に
    `--page`(値取り、`glab issue list --help` で実測)の短縮形として扱う。この食い違いに
    より、`-p` の直後の本物のトークンが「-p の値」として読み飛ばされ、`residual` に残らなく
    なる。残った `residual` がたまたま `--help`/`-h` だけになると、`_is_help_invocation`
    が真を返し、`invokes()` はこの呼び出しを丸ごと `continue` で捨てる — 個々のチェック
    関数(`check_hotfix_label_immutability` など)は一切呼ばれない。

    再現には、読み飛ばされる「値」が単一トークンの短縮結合形(`-lhotfix`)であることが要る。
    `--label`/`hotfix` のように2トークンに分かれていると、`-p` 1個につき1トークンしか
    飲み込めないため、`--label` と `hotfix` を両方隠すには `-p` が2回要り、その場合
    `_issue_update_label_args` 自身が(`--label` の直後のトークンを無条件に値として読む、
    これも「値を取ると分かっている」前提の実装のため)2個目の `-p` を値として拾ってしまい、
    結果的に元から `hotfix` を検出できない — これが #1450 Issue 本文の実測例
    (`glab issue update 42 -p --label -p hotfix --help`)が「今回突いた具体例では実害は
    無かった」と書いている理由。`-lhotfix` は1トークンなので `-p` 1個で丸ごと隠れ、かつ
    `_issue_update_label_args` は独立に `-l` 接頭の直結値として正しく `hotfix` を読み取れる
    ため、両者の判定が食い違う実例になる。
    """

    def test_attached_label_flag_swallowed_by_boolean_public_flag_bypasses_hotfix_check(self):
        """AC1: `-p`(--public、ブール)の直後の `-lhotfix`(値直結)が `-p` の値として
        読み飛ばされ、残余が `--help` だけになって help 誤判定 → hotfix 付与の検査が
        まるごとスキップされないこと。"""
        reason = run_hook(
            "bash", bash_payload("glab issue update -p -lhotfix --help")
        )
        self.assertIsNotNone(
            reason,
            "-p の直後の -lhotfix が値として読み飛ばされ、残余が --help だけになって"
            "help と誤判定され、hotfix 付与の検査がまるごとスキップされた(#1450)",
        )
        self.assertIn("hotfix", reason)

    def test_attached_unlabel_flag_swallowed_by_boolean_public_flag_bypasses_hotfix_check(self):
        """AC1 の対称形: `-u`(--unlabel)の直結値でも同じ穴が開くこと。"""
        reason = run_hook(
            "bash", bash_payload("glab issue update -p -uhotfix --help")
        )
        self.assertIsNotNone(
            reason,
            "-p の直後の -uhotfix が値として読み飛ばされ、hotfix 剥奪の検査が"
            "まるごとスキップされた(#1450)",
        )
        self.assertIn("hotfix", reason)

    def test_duplicate_subcommand_token_after_match_does_not_wrap_around_into_help(self):
        """AC2 の別形(#1450 QA(2026-09-27)が変異注入で発見): サブコマンド一致完了
        **後**に、対象の先頭トークンと同じ綴りの位置引数(`mr`)が重ねて置かれた形。
        `invokes()` の整列ループの `not match_complete and a == target[matched]` から
        `not match_complete` のゲートを落とし `target[matched % len(target)]` へ
        index を折り返す変異を入れると、この余分な `mr` が再び `target[0]` に一致した
        ことにされて `residual` に積まれず、残余が `--help` だけになって help と
        誤判定される(mutant では allow)。正しい実装ではこの `mr` は `match_complete`
        により `residual` に積まれるため `--help` 単独ではなくなり、help 誤判定を
        免れて `check_merge_flags` が通常どおり deny する。"""
        reason = run_hook("bash", bash_payload("glab mr merge mr --help"))
        self.assertIsNotNone(
            reason,
            "サブコマンド一致後の余分な mr トークンが match_complete ゲートを"
            "回避して読み飛ばされ、残余が --help だけになって help と誤判定され、"
            "マージ方式必須検査(check_merge_flags)がスキップされた",
        )
        self.assertIn("squash", reason)

    def test_status_label_integrity_is_not_reachable_via_subcommand_help_misdetection(self):
        """AC1 の残り半分(`check_status_label_integrity`): この関数は
        `invokes(command, "glab", ())` -- 空の `subcommands` -- しか使わず、`invokes()`
        の `if subcommands:` 分岐(値フラグの読み飛ばしと `_is_help_invocation` 判定)を
        一切経由しない(guard.py の `invokes()` docstring、570-573行)。したがってこの
        #1450 の欠陥の影響を構造的に受けない。ここでは、この関数が(修正の前後を問わず)
        今までどおり正しく発火することを確かめる回帰テストとして残す。
        """
        command = (
            "glab api projects/:id/issues/42 --method PUT -p "
            '-f "add_labels=status::Ready" --help'
        )
        reason = run_hook("bash", bash_payload(command))
        self.assertIsNotNone(
            reason,
            "status:: を追加するだけの PUT が -p/--help 併記で誤って見逃された",
        )


# --------------------------------------------------------------------------- #1450 レビュー1回目


class GlobalValueFlagBeforeSubcommandBypassesAllGuards(unittest.TestCase):
    """#1450 レビュー1回目 BLOCKING: `-p` をプログラム全体で外した実装は
    `GLOBAL_VALUE_FLAGS["glab"]` から `-p` そのものを除いてしまっていた。この結果、
    `-p <値>` が**サブコマンドより前**に置かれると、値(例: `2`)が読み飛ばされずに**位置引数として
    数えられ**、`invokes()` の `positional[: len(subcommands)] != list(subcommands)` の
    前方一致が崩れる。一致しない呼び出しは `invokes()` にとって「そのような呼び出しは
    無い」と同じであり、`found` に積まれず**空リストが返る** — 個々のチェック関数
    (`check_merge_flags` 等)のループは1回も回らない。前回の欠陥(help 誤判定による
    `continue`)とは経路が違うが、「依存する全ガードが到達しない」という結果は同じ。

    採るべき規則(レビュアーが確定): `-p` の曖昧さは位置によって意味が変わることに
    起因するので、**一致前の走査では `GLOBAL_VALUE_FLAGS[program]` をそのまま全部使い
    (`-p` を戻す)、一致後の `residual` の組み立てではグローバル値フラグの読み飛ばしを
    一切行わない**(curated なリストを引かない)。
    """

    def test_global_value_flag_before_mr_merge_does_not_bypass_merge_flags_check(self):
        """呼び出し元1/4: `check_merge_flags`(#1435)。`-p 2` がサブコマンドより前に
        あっても `mr merge` への一致が崩れず、`--rebase` 禁止検査が発火すること。"""
        reason = run_hook("bash", bash_payload("glab -p 2 mr merge --rebase"))
        self.assertIsNotNone(
            reason,
            "-p 2 がサブコマンドより前に置かれたことで位置引数がずれ、"
            "invokes() が空を返し --rebase 禁止検査(check_merge_flags)が"
            "スキップされた(#1450 レビュー1回目)",
        )
        self.assertIn("squash", reason)

    def test_global_value_flag_before_issue_update_does_not_bypass_hotfix_immutability(self):
        """呼び出し元2/4: `check_hotfix_label_immutability`(#1433)。`-p 2` が
        サブコマンドより前にあっても `issue update` への一致が崩れず、hotfix 不変性
        検査が発火すること。"""
        reason = run_hook(
            "bash", bash_payload("glab -p 2 issue update 42 -lhotfix")
        )
        self.assertIsNotNone(
            reason,
            "-p 2 がサブコマンドより前に置かれたことで位置引数がずれ、"
            "invokes() が空を返し hotfix 不変性検査(check_hotfix_label_immutability)"
            "がスキップされた(#1450 レビュー1回目)",
        )
        self.assertIn("hotfix", reason)

    def test_global_value_flag_before_issue_create_does_not_bypass_hotfix_creation_gate(self):
        """呼び出し元3/4: `check_hotfix_creation`(#1434)。マーカー無しでの `hotfix`
        付き起票は、`-p 1` がサブコマンドより前にあっても拒否され続けること。"""
        reason = run_hook(
            "bash",
            bash_payload("glab -p 1 issue create --title x --label hotfix"),
        )
        self.assertIsNotNone(
            reason,
            "-p 1 がサブコマンドより前に置かれたことで位置引数がずれ、"
            "invokes() が空を返し hotfix 作成ゲート(check_hotfix_creation)が"
            "スキップされた(#1450 レビュー1回目)",
        )
        self.assertIn("hotfix", reason)

    def test_global_value_flag_before_mr_create_does_not_bypass_coverage_gate(self):
        """呼び出し元4/4: `check_pr_coverage`。`-p 1` がサブコマンドより前にあっても
        `mr create` への一致が崩れず、カバレッジゲートが発火すること。"""
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write("import sys\nsys.exit(1)\n")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab -p 1 mr create --title x", cwd=root),
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(
            reason,
            "-p 1 がサブコマンドより前に置かれたことで位置引数がずれ、"
            "invokes() が空を返しカバレッジゲート(check_pr_coverage)が"
            "スキップされた(#1450 レビュー1回目)",
        )
        self.assertIn("カバレッジ", reason)

    def test_global_value_flag_after_subcommand_with_help_is_denied_by_design(self):
        """意図的に受け入れる代償(fail-closed側のトレードオフ、レビュー確定事項):
        一致**後**に置かれたグローバル値フラグ(`--repo o/r`)と `--help` が併記された
        呼び出しは、`residual` の組み立てでグローバル値フラグの読み飛ばしを一切
        行わないため、`residual` に `--repo`/`o/r` が残る。`_is_help_invocation` は
        全トークンが `--help`/`-h` のときだけ真を返す(AND方式、#1446)ので、この
        呼び出しはヘルプとは判定されず、`--squash` 未指定として通常どおり deny される。

        一致**前**に置かれた同じ形(`glab --repo o/r mr merge --help`)は、`-R`/`--repo`
        を読み飛ばして位置引数を正しく揃えたうえで、残余が `--help` だけになりヘルプと
        判定され続ける(このテストでは検査しないが、対照として #1435/#1446 の既存テストが
        カバーしている)。向きは無害な誤拒否(fail-closed)であり、#1449 が既に受け入れて
        いる代償と同種(呼び出し元、2026-09-27、#1450 レビュー1回目)。
        """
        reason = run_hook(
            "bash", bash_payload("glab mr merge --repo o/r --help")
        )
        self.assertIsNotNone(
            reason,
            "一致後に置かれた --repo o/r --help がヘルプ扱いされ、squash 必須検査が"
            "スキップされた(この形は意図的に deny 側に倒す設計)",
        )
        self.assertIn("squash", reason)

    def test_global_value_flag_after_mr_create_with_help_is_denied_by_design(self):
        """AC4 2/3: `mr create` でも同じ代償が起きること。一致後の `--repo o/r` が
        `residual` に残るため `--help` と誤判定されず、カバレッジゲート
        (`check_pr_coverage`)が通常どおり発火して deny されること。"""
        root = tempfile.mkdtemp()
        scripts = os.path.join(root, "scripts")
        os.makedirs(scripts)
        with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
            f.write("import sys\nsys.exit(1)\n")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab mr create --repo o/r --help", cwd=root),
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(
            reason,
            "一致後に置かれた --repo o/r --help がヘルプ扱いされ、カバレッジゲートが"
            "スキップされた(この形は意図的に deny 側に倒す設計)",
        )
        self.assertIn("カバレッジ", reason)

    def test_global_value_flag_after_issue_create_with_help_is_denied_by_design(self):
        """AC4 3/3: `issue create` でも同じ代償が起きること。一致後の `--repo o/r` が
        `residual` に残るため `--help` と誤判定されず、`status::` 必須検査
        (`check_issue_creation_requires_status`)が通常どおり発火して deny されること。"""
        reason = run_hook(
            "bash", bash_payload("glab issue create --repo o/r --help")
        )
        self.assertIsNotNone(
            reason,
            "一致後に置かれた --repo o/r --help がヘルプ扱いされ、status:: 必須検査が"
            "スキップされた(この形は意図的に deny 側に倒す設計)",
        )
        self.assertIn("status::", reason)

    def test_global_value_flag_before_subcommand_with_help_is_still_allowed(self):
        """AC4 の対照: 一致**前**に置かれた同じ形(`glab --repo o/r mr merge --help`)は、
        `-R`/`--repo` が読み飛ばされて位置引数が正しく揃うため、残余が `--help` だけに
        なりヘルプ判定され続ける(allow のまま)。前置形の read-only な用途
        (`glab --repo o/r mr merge --help` でヘルプだけ見る)を壊さないことの固定。"""
        reason = run_hook(
            "bash", bash_payload("glab --repo o/r mr merge --help")
        )
        self.assertIsNone(
            reason,
            "一致前に置かれた --repo o/r --help が誤って deny された"
            "(前置形のヘルプ判定が壊れている)",
        )


# --------------------------------------------------------------------------- #1454


def _run_bash_in_process(command, cwd=None, global_value_flags=None):
    """`guard.cmd_bash` を**同一プロセス内で**呼び、(拒否理由 or None) を返す。

    AC2(`GLOBAL_VALUE_FLAGS` を空集合に差し替えても AC1 が全 DENY のまま)は
    `guard` モジュールのグローバル辞書そのものを差し替える必要があり、
    `run_hook()`(別プロセス起動)では差し替えが子プロセスに伝わらない。
    `cmd_explain` が同じモジュールグローバル(`EXPLAIN_MODE`)を使って `emit_deny` を
    `sys.exit()` の代わりに `Denied` 例外にする、という既存の仕組みをそのまま流用する。
    """
    hooks_dir = os.path.dirname(HOOK)
    if hooks_dir not in sys.path:
        sys.path.insert(0, hooks_dir)
    import guard

    payload = bash_payload(command, cwd=cwd)
    backup_flags = guard.GLOBAL_VALUE_FLAGS
    backup_explain = guard.EXPLAIN_MODE
    try:
        if global_value_flags is not None:
            guard.GLOBAL_VALUE_FLAGS = global_value_flags
        guard.EXPLAIN_MODE = True
        try:
            guard.cmd_bash(payload)
        except guard.Denied as denied:
            return denied.reason
        except SystemExit:
            return None
        return None
    finally:
        guard.GLOBAL_VALUE_FLAGS = backup_flags
        guard.EXPLAIN_MODE = backup_explain


def _coverage_denying_root():
    """`scripts/check-changed-coverage.py` が常に rc 1 を返す作業ツリーを作る。"""
    root = tempfile.mkdtemp()
    scripts = os.path.join(root, "scripts")
    os.makedirs(scripts)
    with open(os.path.join(scripts, "check-changed-coverage.py"), "w") as f:
        f.write("import sys\nsys.exit(1)\n")
    return root


# AC1 が要求する7コマンド(git の1件を含む)。値の中身ではなく「サブコマンドより前に
# 任意のフラグを置いても検出が崩れない」という性質を1箇所にまとめ、AC1/AC2 の両方の
# テストから再利用する。カバレッジゲート対象(`mr create`)だけは作業ツリーの用意が
# 要るため別枠にする。
AC1_SIMPLE_COMMANDS = [
    ("glab --sha abc123 mr merge --rebase", "squash"),
    ("glab -m msg mr merge --rebase", "squash"),
    ("glab -l foo issue update 42 -l hotfix", "hotfix"),
    ("glab -t title issue update 42 -l hotfix", "hotfix"),
    ("glab --due-date 2026-01-01 issue create -l hotfix -t x", "hotfix"),
    ("glab -z 1 mr merge --rebase", "squash"),
    ("glab --zz 1 mr merge --rebase", "squash"),
    ("git --attr-source HEAD commit --no-verify", "no-verify"),
]

# #1454 レビュー1回目 BLOCKING1: サブコマンド2語の「あいだ」に GLOBAL_VALUE_FLAGS の
# フラグ(値ペア込み)を挟んだ形。merge-base(617c4a88)の位置引数整列は一致未完了の
# あいだ常にこの対を読み飛ばしたため元から DENY だったが、連続一致だけに絞った
# 現ブランチ(8291bf53)では検出が `None` になり ALLOW へ退行した。RED はこの
# 現ブランチに対して記録する(merge-base では退行していないため)。
AC1_BETWEEN_POSITION_COMMANDS = [
    ("glab mr -R o/r merge --rebase", "squash"),
    ("glab issue -R o/r update 42 -l hotfix", "hotfix"),
    ("glab mr -p 2 merge --rebase", "squash"),
    ("glab mr -R o/r -p 2 merge --rebase", "squash"),
]


class OrderIndependentSubcommandDetection(unittest.TestCase):
    """#1454 AC1: 葉のサブコマンドが定義する値フラグ(`--sha`/`-m`/`--title`/`-l`/`-t`/
    `--due-date` 等)や、`GLOBAL_VALUE_FLAGS` に無い任意のフラグ(`-z`/`--zz`)、
    さらに `GLOBAL_VALUE_FLAGS["git"]` に無い実在の git グローバル値フラグ
    (`--attr-source`)が、サブコマンドより前に置かれても `invokes()` が呼び出しを
    見失わないこと。

    ## 受け入れる代償(#1454 本文より)

    - **値の中身の誤検出**: `mr`/`merge` のような語がクォート無しで別々のトークンとして
      連続すると、それだけで一致とみなす(例: `glab issue create -t mr merge` は
      `-t` の値のつもりの `mr merge` を `mr merge` コマンドと誤認する)。クォートされた
      `--title "mr merge"` は `split_commands()` が1トークンとして扱うため一致しない。
    - `glab -z 1 mr merge --help` は `-z`/`1` が `residual` に残るため非ヘルプ判定になり、
      誤拒否になる(fail-closed、無害)。
    - 向きはすべて fail-closed(無害な誤拒否)であり、#1446/#1449 が既に受け入れている
      代償と同種。
    """

    def test_all_ac1_commands_are_denied(self):
        for command, expected_substring in AC1_SIMPLE_COMMANDS:
            with self.subTest(command=command):
                reason = run_hook("bash", bash_payload(command))
                self.assertIsNotNone(
                    reason,
                    "%r が許可された(サブコマンドより前の任意のフラグで位置引数が"
                    "ずれ、invokes() が空を返した可能性がある)" % command,
                )
                self.assertIn(expected_substring, reason)

    def test_between_position_flag_commands_are_denied(self):
        """#1454 レビュー1回目 BLOCKING1 への対応(AC1 あいだ位置4形)。

        `glab mr -R o/r merge --rebase` のように、`GLOBAL_VALUE_FLAGS` に載っている
        フラグでもサブコマンド2語の**あいだ**に置かれると、連続トークン一致だけでは
        検出が `None` になり、依存する全ガードが見えなくなる(#1435/#1440 が
        閉じたはずの回避経路の再発)。RED は本文の指示どおり現ブランチ(8291bf53)に
        対して記録する: merge-base はこれらを既に DENY していた。
        """
        for command, expected_substring in AC1_BETWEEN_POSITION_COMMANDS:
            with self.subTest(command=command):
                reason = run_hook("bash", bash_payload(command))
                self.assertIsNotNone(
                    reason,
                    "%r が許可された(サブコマンド語のあいだのフラグで連続一致が"
                    "破れ、invokes() が空を返した可能性がある。"
                    "#1454 レビュー1回目 BLOCKING1)" % command,
                )
                self.assertIn(expected_substring, reason)

    def test_title_value_prefixed_mr_create_triggers_coverage_gate(self):
        """`--title x` が `mr create` より前にあっても Coverage Gate が到達すること。"""
        root = _coverage_denying_root()
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash",
                bash_payload("glab --title x mr create --description y", cwd=root),
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(
            reason,
            "--title x mr create --description y が許可された"
            "(Coverage Gate が到達していない)",
        )
        self.assertIn("カバレッジ", reason)

    # --- AC2: 判定が GLOBAL_VALUE_FLAGS の網羅性に依存しないこと(性質そのものの固定) ---

    def test_ac1_commands_still_denied_with_empty_global_value_flags(self):
        """`GLOBAL_VALUE_FLAGS` の `glab`/`git` 両エントリを空集合に差し替えても、
        AC1 のすべてが引き続き DENY のままであること。

        インスタンス(`-z`/`--zz` 個別のケース)ではなく「検出が curated なリストの
        網羅性に依存しない」という性質そのものを固定する(#1454 Readiness Report)。
        これが通っていれば、`GLOBAL_VALUE_FLAGS` に載っていない別のフラグで同種の
        fail-open が再発しない。
        """
        empty_flags = {"glab": set(), "git": set()}
        for command, expected_substring in AC1_SIMPLE_COMMANDS:
            with self.subTest(command=command):
                reason = _run_bash_in_process(command, global_value_flags=empty_flags)
                self.assertIsNotNone(
                    reason,
                    "%r が GLOBAL_VALUE_FLAGS 空集合下で許可された"
                    "(検出が curated なリストの網羅性に依存している)" % command,
                )
                self.assertIn(expected_substring, reason)

    def test_title_value_prefixed_mr_create_coverage_gate_with_empty_global_value_flags(self):
        root = _coverage_denying_root()
        empty_flags = {"glab": set(), "git": set()}
        reason = _run_bash_in_process(
            "glab --title x mr create --description y",
            cwd=root,
            global_value_flags=empty_flags,
        )
        self.assertIsNotNone(
            reason,
            "GLOBAL_VALUE_FLAGS 空集合下で --title x mr create --description y が"
            "許可された(Coverage Gate が到達していない)",
        )
        self.assertIn("カバレッジ", reason)

    def test_between_position_commands_still_denied_with_empty_global_value_flags(self):
        """AC2 をあいだ位置4形にも適用する(#1454 レビュー SUGGESTION への対応)。

        あいだ位置4形は `p_prev` が `GLOBAL_VALUE_FLAGS` を引くため空集合では
        不成立になり、集合を引かない `p_proj` だけが検出を救う。したがって
        「検出が `GLOBAL_VALUE_FLAGS` を再び引くようにする」変異は、この4形が
        空集合下で allow に反転することで必ず落ちる。
        """
        empty_flags = {"glab": set(), "git": set()}
        for command, expected_substring in AC1_BETWEEN_POSITION_COMMANDS:
            with self.subTest(command=command):
                reason = _run_bash_in_process(command, global_value_flags=empty_flags)
                self.assertIsNotNone(
                    reason,
                    "%r が GLOBAL_VALUE_FLAGS 空集合下で許可された"
                    "(あいだ位置の検出が curated なリストの網羅性に依存している)"
                    % command,
                )
                self.assertIn(expected_substring, reason)

    def test_leading_flag_projected_tokens_cobra_limits_are_pinned_directly(self):
        """`_leading_flag_projected_tokens()` の cobra 制限を直接の単体 assert で
        固定する(#1454 AC2、レビュー2回目 IMPORTANT: 射影が全ての dash トークンで
        次を落とす変異は、`p_prev` が `--repo=o/r`/`-Ro/r` を覆うため end-to-end
        テストでは merge-base 比 0 件で捕まらない)。

        3文字以上の短縮形(`-Ro/r`)・`=` 結合形(`--repo=o/r`)は次を**落とさず**、
        ちょうど2文字の短縮形(`-R o/r`)は次を**落とす** — cobra の `stripFlags`
        と同じ形であることを直接固定する。
        """
        guard = _import_guard_module()
        self.assertEqual(
            guard._leading_flag_projected_tokens(
                ["mr", "-Ro/r", "merge", "--rebase"], 4
            ),
            [(0, "mr"), (2, "merge")],
        )
        self.assertEqual(
            guard._leading_flag_projected_tokens(["mr", "--repo=o/r", "merge"], 3),
            [(0, "mr"), (2, "merge")],
        )
        self.assertEqual(
            guard._leading_flag_projected_tokens(["mr", "-R", "o/r", "merge"], 4),
            [(0, "mr"), (3, "merge")],
        )

    # --- AC3: fail-closed の維持(#1435 の元テストと同種、glab が rc 1 で拒否する形) ---

    def test_jq_value_prefixed_mr_create_still_triggers_coverage_gate(self):
        """`glab --jq . mr create`(glab は rc 1 で受理しない形)でも `invokes()` が
        呼び出しを検出し、Coverage Gate が到達すること。"""
        root = _coverage_denying_root()
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash", bash_payload("glab --jq . mr create", cwd=root)
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNotNone(reason, "glab --jq . mr create が許可された")
        self.assertIn("カバレッジ", reason)

    # --- AC4: 既存の判定が無傷であること ---

    def test_repo_prefixed_mr_merge_rebase_is_still_denied(self):
        """#1435 の対照。"""
        reason = run_hook("bash", bash_payload("glab -R o/r mr merge --rebase"))
        self.assertIsNotNone(reason)
        self.assertIn("squash", reason)

    def test_p_prefixed_issue_update_hotfix_is_still_denied(self):
        """#1450 の対照。"""
        reason = run_hook(
            "bash", bash_payload("glab -p 42 issue update -l hotfix")
        )
        self.assertIsNotNone(reason)
        self.assertIn("hotfix", reason)

    def test_clustered_repo_short_flag_mr_merge_is_still_denied(self):
        """#1441 の対照。"""
        reason = run_hook("bash", bash_payload("glab -Rsss mr merge"))
        self.assertIsNotNone(reason)

    def test_repo_prefixed_mr_merge_help_is_still_allowed(self):
        """#1446 の対照。"""
        reason = run_hook(
            "bash", bash_payload("glab --repo o/r mr merge --help")
        )
        self.assertIsNone(reason)

    def test_bare_mr_merge_help_is_still_allowed(self):
        reason = run_hook("bash", bash_payload("glab mr merge --help"))
        self.assertIsNone(reason)

    def test_bare_mr_merge_help_short_flag_is_still_allowed(self):
        reason = run_hook("bash", bash_payload("glab mr merge -h"))
        self.assertIsNone(reason)

    def test_no_advice_prefixed_commit_no_verify_is_still_denied(self):
        """方式A(cobra 同等規則)却下の根拠そのもの。`--no-advice` は git が実際に
        値を取らず受理するブールのグローバルフラグであり(実測: `git --no-advice
        --version` は rc 0)、方式Aを採ると `commit` を値として消費して ALLOW に
        変わってしまう(#1454 Readiness Report)。方式Bはフラグと値を区別しないので
        この回帰を起こさない。"""
        reason = run_hook(
            "bash", bash_payload("git --no-advice commit --no-verify")
        )
        self.assertIsNotNone(
            reason,
            "git --no-advice commit --no-verify が許可された"
            "(方式Aと同じ新規 fail-open が発生している)",
        )
        self.assertIn("no-verify", reason)

    def test_dash_z_value_before_no_advice_prefixed_commit_no_verify_is_denied(self):
        """`git -z 1 --no-advice commit --no-verify`: AC1 が名指しする、**`p_raw`
        (生のトークン列の連続一致)だけが救う形(#1454 本文 Requirement 1)。

        `p_prev`(merge-base の整列)は `-z` の値のつもりの `1` を最初の位置引数
        として拾ってしまい(`1` は `-` で始まらないので読み飛ばされない)、
        `positional[:1] == ["commit"]` の前方一致が `1 != "commit"` で崩れて
        `None` を返す。`p_proj` は `--no-advice`(長形・`=` を含まない)が cobra
        規則で直後の `commit` を値として落とすため、射影列に `commit` が残らず
        一致しない。生の列をそのまま連続一致で探す `p_raw` だけが `commit`
        (index3)を見つける。

        RED は merge-base(617c4a88)に対して記録する: merge-base の整列は
        `p_prev` と同一であり、上記のとおりこの呼び出しを検出できず ALLOW して
        いた(実装報告に記録)。
        """
        reason = run_hook(
            "bash", bash_payload("git -z 1 --no-advice commit --no-verify")
        )
        self.assertIsNotNone(
            reason,
            "git -z 1 --no-advice commit --no-verify が許可された"
            "(p_raw だけが検出できる形で回帰している)",
        )
        self.assertIn("no-verify", reason)

    # --- AC5: `_leading_global_git_flags` が同じ一致位置から前置フラグ列を導くこと ---

    def test_attr_source_prefixed_commit_phase_separation_is_denied(self):
        """`--attr-source`(`GLOBAL_VALUE_FLAGS["git"]` に無い、git 2.40 以降の実在する
        値取りグローバルフラグ)を前置しても、`check_commit_phase` が対象リポジトリの
        ステージ内容を正しく読み、テスト/プロダクション混在を検出すること。

        修正前は前置列を `['--attr-source']` と誤って切り出し、
        `git --attr-source <root> diff --cached --name-only` が失敗して
        `staged is None` → `continue` となり、フェーズ分離検査自体が素通りする
        (#1454 Readiness Report)。
        """
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --attr-source %s commit -m x" % root, root
        )
        self.assertIsNotNone(
            reason,
            "--attr-source 前置の git commit がフェーズ分離検査をすり抜けている"
            "(前置フラグ列の切り出しが invokes() の一致位置と食い違っている)",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_attr_source_prefixed_commit_test_only_is_allowed(self):
        root = _git_phase_project("apps/web/e2e/features/a.feature")
        reason = _run_in_git_phase_project(
            "git --attr-source %s commit -m x" % root, root
        )
        self.assertIsNone(
            reason,
            "--attr-source 前置でテストのみのコミットが誤って拒否された: %s" % reason,
        )


class SubcommandDetectionTruncatesAtBareDashDash(unittest.TestCase):
    """#1454 AC3(b)(レビュー1回目 IMPORTANT への対応、レビュー2回目 SUGGESTION で
    論拠を訂正): 探索は最初の裸の `--` で打ち切るという Requirement 1 の規定を
    固定する。

    非空虚性の論拠は **`p_raw` の打ち切りの削除だけ**である(手元の変異注入で確認、
    実装報告に記録): `_subcommand_match_position()`(`p_raw`)の打ち切りループを
    削除すると、この2件はどちらも DENY に変わる。**`p_proj` の打ち切りだけを
    外しても2件は allow のまま**である — `_subcommand_match_position_proj()` の
    `limit` を `_rest_limit_before_bare_dashdash(rest)` から `len(rest)` に
    変えても、`--` 自身が cobra 規則上「`=` を含まない長形」として射影に一致し、
    続く `mr`/`commit` を値として落としてしまうため、`p_proj` は依然一致しない
    (手元の変異注入で確認、実装報告に記録)。旧 docstring は「`p_raw`/`p_proj` の
    打ち切りを丸ごと削除するとどちらも DENY に変わる」と書いていたが、これは
    `p_proj` については事実に反していた(#1454 レビュー2回目 SUGGESTION)。

    `--` 以降のサブコマンドは実際には実行されない(実測: `glab -- mr list` は root の
    ヘルプ、`git -- status` は `unknown option: --`)ので、許可のままが正しい。
    """

    def test_glab_bare_dashdash_before_subcommand_stays_allowed(self):
        reason = run_hook("bash", bash_payload("glab -z 1 -- mr merge --rebase"))
        self.assertIsNone(
            reason,
            "打ち切りが壊れ、`--` 以降の mr merge --rebase まで検出してしまった: %s"
            % reason,
        )

    def test_git_bare_dashdash_before_subcommand_stays_allowed(self):
        reason = run_hook("bash", bash_payload("git -z 1 -- commit --no-verify"))
        self.assertIsNone(
            reason,
            "打ち切りが壊れ、`--` 以降の commit --no-verify まで検出してしまった: %s"
            % reason,
        )


class BareDashDashPositionHasSingleImplementation(unittest.TestCase):
    """#1466: 裸の `--` の位置を決める規則は `_rest_limit_before_bare_dashdash()` の
    1箇所だけにある。`p_raw` が自前のループを持つと、片方だけを変異させても
    もう片方が残り、打ち切りの非空虚性の根拠を誤らせる(#1454)。
    """

    def test_limit_helper_direct_asserts(self):
        guard = _import_guard_module()
        f = guard._rest_limit_before_bare_dashdash
        self.assertEqual(f(["a", "--", "b"]), 1)
        self.assertEqual(f(["a", "b"]), 2)
        self.assertEqual(f(["--"]), 0)
        self.assertEqual(f([]), 0)

    def test_only_the_helper_scans_for_bare_dashdash(self):
        import re

        with open(HOOK, encoding="utf-8") as fh:
            src = fh.read()
        scans = re.findall(r'^\s*(?:if|elif)\s+\w+\s*==\s*"--"\s*:', src, re.M)
        self.assertEqual(
            len(scans),
            1,
            "裸の `--` の位置を決める走査が %d 箇所ある(1箇所のはず)" % len(scans),
        )

    def test_proj_docstring_uses_example_where_truncation_matters(self):
        guard = _import_guard_module()
        doc = guard._subcommand_match_position_proj.__doc__
        self.assertIn("glab -- x mr merge --rebase", doc)
        self.assertNotIn("glab -z 1 -- mr merge --rebase", doc)

    def test_proj_truncation_is_effective_for_dashdash_then_unknown_token(self):
        guard = _import_guard_module()
        reason = run_hook("bash", bash_payload("glab -- x mr merge --rebase"))
        self.assertIsNone(reason, reason)
        self.assertIsNone(
            guard._subcommand_match_position_proj(
                ["--", "x", "mr", "merge", "--rebase"], ("mr", "merge")
            )
        )

    def test_raw_stops_at_bare_dashdash(self):
        guard = _import_guard_module()
        self.assertIsNone(
            guard._subcommand_match_position(["--", "mr", "merge"], ("mr", "merge"))
        )
        self.assertEqual(
            guard._subcommand_match_position(["mr", "merge", "--"], ("mr", "merge")),
            0,
        )


class MatchEndUsesLastMatchedTokenNotFirst(unittest.TestCase):
    """#1454 AC3(c)(2026-09-27 の本文訂正で新設。レビュー2回目 IMPORTANT への対応)。

    Requirement 1 は「`match_end` を**最後に**一致したトークンの生 index と定義し」
    「一致が生の列で非連続な場合(`glab mr -R o/r merge --help`)…`residual` は
    `['--help']` → **許可(merge-base と同一)**」と明記している。この規則を
    `match_end = min(matched_indices)`(**最初に**一致したトークン)にする変異は、
    AC1〜AC5 のどのテストにも捕まらず全 suite を緑のまま生き残っていた
    (#1454 レビュー2回目 IMPORTANT)。**AC4 は allow 方向の退行だけを見る性質
    テストなので、この allow→DENY への反転は原理的に AC4 では捕まらない** — これが
    AC3(c) を独立に新設した理由。

    非空虚性: `match_end = min(matched_indices)` への変異を手元で注入すると、
    この2件はどちらも allow から DENY に反転する(実装報告に貼付)。
    """

    def test_repo_short_flag_between_subcommands_help_stays_allowed(self):
        """`glab mr -R o/r merge --help`: `-R o/r` は `mr` と `merge` の**あいだ**
        にあり、`p_prev` が非連続一致(`mr`=index0, `merge`=index3)する。
        `match_end` は最後に一致した `merge`(index3)であるべきで、それより前の
        `-R`/`o/r`(`GLOBAL_VALUE_FLAGS["glab"]` の対)は読み飛ばされ、`residual`
        は `['--help']` になり許可される(merge-base と同一)。"""
        reason = run_hook("bash", bash_payload("glab mr -R o/r merge --help"))
        self.assertIsNone(
            reason,
            "glab mr -R o/r merge --help が拒否された"
            "(match_end が最後に一致したトークンではなく最初になっている疑い): %s"
            % reason,
        )

    def test_page_flag_between_subcommands_help_stays_allowed(self):
        """`glab mr -p 2 merge --help`: 同上、あいだのフラグが `-p 2` の形。"""
        reason = run_hook("bash", bash_payload("glab mr -p 2 merge --help"))
        self.assertIsNone(
            reason,
            "glab mr -p 2 merge --help が拒否された"
            "(match_end が最後に一致したトークンではなく最初になっている疑い): %s"
            % reason,
        )


class LeadingGlobalGitFlagsCandidateRetry(unittest.TestCase):
    """#1454 AC5 / レビュー1回目 BLOCKING2 とその鏡像(Readiness 再評価、反例3・4)。

    `_leading_global_git_flags()` が単一の一致位置だけから前置列を導くと、
    グローバルフラグの値がサブコマンド語と同名のときに境界を誤り、
    `check_commit_phase` が `staged is None` から早期 `continue` して
    フェーズ分離検査そのものが素通りする。候補集合(`p_prev`/`p_proj`/`p_raw` の
    一致位置から導いた前置列を重複排除・短い順に並べたもの)+失敗時リトライで
    初めて閉じる。
    """

    def test_dash_C_value_shares_subcommand_name_is_denied(self):
        """`git -C commit commit -m x`: `-C` の値がたまたま `commit`(ディレクトリ名)。

        `p_prev`/`p_proj` は正しく `['-C', 'commit']` を導くが、`p_raw`(生の連続一致)
        は最初の `commit`(`-C` の値)に一致してしまい `['-C']` を導く。単一候補
        (`p_raw` 優先、あるいは `p_raw` のみ)では誤った前置列が採用され、
        `git -C diff --cached --name-only` が失敗して `staged is None` になる。
        """
        parent = tempfile.mkdtemp()
        target = os.path.join(parent, "commit")
        os.makedirs(target)
        subprocess.run(["git", "init", "-q"], cwd=target, check=True)
        subprocess.run(
            ["git", "config", "user.email", "t@example.com"], cwd=target, check=True
        )
        subprocess.run(["git", "config", "user.name", "t"], cwd=target, check=True)
        for rel in ("apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"):
            path = os.path.join(target, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write("x")
        subprocess.run(
            ["git", "add", "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"],
            cwd=target,
            check=True,
        )
        reason = _run_in_git_phase_project("git -C commit commit -m x", parent)
        self.assertIsNotNone(
            reason,
            "-C の値がサブコマンド語と同名(commit)のディレクトリのとき前置列の"
            "境界を誤り、フェーズ分離検査が素通りした(#1454 レビュー1回目 "
            "BLOCKING2)",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_dash_C_value_shares_subcommand_name_test_only_is_allowed(self):
        parent = tempfile.mkdtemp()
        target = os.path.join(parent, "commit")
        os.makedirs(target)
        subprocess.run(["git", "init", "-q"], cwd=target, check=True)
        subprocess.run(
            ["git", "config", "user.email", "t@example.com"], cwd=target, check=True
        )
        subprocess.run(["git", "config", "user.name", "t"], cwd=target, check=True)
        path = os.path.join(target, "apps/web/e2e/features/a.feature")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("x")
        subprocess.run(
            ["git", "add", "apps/web/e2e/features/a.feature"], cwd=target, check=True
        )
        reason = _run_in_git_phase_project("git -C commit commit -m x", parent)
        self.assertIsNone(
            reason,
            "-C commit commit のテストのみのコミットが誤って拒否された: %s" % reason,
        )

    def test_attr_source_value_shares_subcommand_name_requires_candidate_retry(self):
        """`git --attr-source commit commit -m x`: `--attr-source` の値がたまたま
        `commit`。BLOCKING2 の鏡像(Readiness 再評価、反例4): `p_prev`/`p_raw` は
        どちらも最初の `commit`(`--attr-source` の値)に一致し、単一候補では
        前置列が `['--attr-source']` になって `git --attr-source diff --cached
        --name-only` が失敗する。`p_proj` だけが正しい境界(`['--attr-source',
        'commit']`)を与えるので、候補集合+短い順リトライで初めて閉じる。
        """
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --attr-source commit commit -m x", root
        )
        self.assertIsNotNone(
            reason,
            "--attr-source の値がサブコマンド語と同名のとき前置列の候補選択に"
            "失敗し、フェーズ分離検査が素通りした",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_no_advice_boolean_then_positional_named_like_subcommand_is_denied(self):
        """`git --no-advice commit foo commit`: AC5 が名指しする3つ目の witness
        (#1454 レビュー2回目 IMPORTANT: 前回はこの witness の代わりに別の入力
        (`--attr-source commit commit -m x`)で代替しており、この witness 自体の
        回帰テストが無かった。別の入力での代替は禁止されている)。

        `--no-advice` はブールのグローバルフラグ、`foo` はその直後の無関係な
        位置引数、2つ目の `commit` が本当のサブコマンド。`p_prev`/`p_raw` は
        どちらも**最初の** `commit`(index1、`--no-advice` の直後の無関係な
        位置引数ではなく `commit` 自身)に一致し、`p_proj` は `--no-advice` が
        直後を値として落とすため `foo`(index2)を経て2つ目の `commit`
        (index3)に一致する。`_leading_global_git_flags` の候補は
        `[['--no-advice'], ['--no-advice', 'commit', 'foo']]` になり、**最短の
        候補(`['--no-advice']`)が正しい** — これが正しい唯一の witness であり、
        候補を逆順(長い方から)に試す変異はこの witness 以外では捕まらない。
        """
        root = _git_phase_project(
            "apps/web/e2e/features/a.feature", "services/foo/src/Bar.java"
        )
        reason = _run_in_git_phase_project(
            "git --no-advice commit foo commit", root
        )
        self.assertIsNotNone(
            reason,
            "git --no-advice commit foo commit がフェーズ分離検査をすり抜けた"
            "(候補の並びが崩れている疑い、#1454 レビュー2回目 IMPORTANT)",
        )
        self.assertIn("テストコードとプロダクションコード", reason)

    def test_no_advice_boolean_then_positional_named_like_subcommand_test_only_is_allowed(
        self,
    ):
        root = _git_phase_project("apps/web/e2e/features/a.feature")
        reason = _run_in_git_phase_project(
            "git --no-advice commit foo commit", root
        )
        self.assertIsNone(
            reason,
            "--no-advice commit foo commit のテストのみのコミットが誤って"
            "拒否された: %s" % reason,
        )

    def test_leading_global_git_flags_candidates_are_deduped_and_shortest_first(self):
        """`_leading_global_git_flags()` が返す候補列が重複排除済みかつ短い順で
        あることを、この2 witness に対する直接の単体 assert で固定する(#1454
        AC5、#1454 レビュー2回目 IMPORTANT: 候補を逆順にする変異は274件の多候補ケースの
        end-to-end テストでは差0で捕まらなかった)。

        `--no-advice commit foo commit` は**最短の候補が正しい**唯一の witness、
        `-C commit commit -m x` は**2番目の候補が正しい**唯一の witness であり、
        この対でリスト等価(順序込み)の assert により候補の並びが一意に固定される。
        """
        guard = _import_guard_module()
        self.assertEqual(
            guard._leading_global_git_flags(
                ["--no-advice", "commit", "foo", "commit"]
            ),
            [["--no-advice"], ["--no-advice", "commit", "foo"]],
        )
        self.assertEqual(
            guard._leading_global_git_flags(["-C", "commit", "commit", "-m", "x"]),
            [["-C"], ["-C", "commit"]],
        )


# --------------------------------------------------------------------------- #1454 AC4


# merge-base(617c4a88)時点の `GLOBAL_VALUE_FLAGS` を凍結したコピー。production の
# 辞書が将来変わっても、この参照実装は 617c4a88 時点の値のまま固定する。
_FROZEN_617C4A88_GLOBAL_VALUE_FLAGS = {
    "glab": {"-R", "--repo", "--jq", "-F", "--output", "-p", "--page", "-P", "--per-page"},
    "git": {"-C", "--git-dir", "--work-tree", "-c", "--namespace", "--config-env"},
}


def _frozen_617c4a88_is_help_invocation(residual):
    """merge-base(617c4a88)時点の `_is_help_invocation` を凍結したコピー。"""
    return bool(residual) and all(tok in ("--help", "-h") for tok in residual)


def _frozen_617c4a88_invokes_detects(rest, subcommands, value_flags):
    """merge-base(617c4a88)の `invokes()` の位置引数整列をそのまま凍結して再現した
    参照実装(AC4)。**production の `guard.py` に合わせて更新しないこと** —
    production 側の検出規則が変わっても、ここは 617c4a88 時点の規則のまま固定する。
    基準はこの関数であり、production の `invokes()` ではない。

    `rest`(`program` 後続のトークン列)が `subcommands` への呼び出しとして
    merge-base に検出され(`positional[:len(subcommands)] == subcommands`)、かつ
    ヘルプ専用呼び出しでもないときに True を返す。
    """
    target = list(subcommands)
    n = len(target)
    positional = []
    residual = []
    matched = 0
    skip_next = False
    for a in rest:
        if skip_next:
            skip_next = False
            continue
        match_complete = matched >= n
        if not match_complete and a in value_flags:
            skip_next = True
            continue
        if a.startswith("-"):
            residual.append(a)
            continue
        positional.append(a)
        if not match_complete and a == target[matched]:
            matched += 1
        else:
            residual.append(a)
    if positional[:n] != target:
        return False
    return not _frozen_617c4a88_is_help_invocation(residual)


# 基準コマンド(program, 後続トークン列, サブコマンド)。#1454 レビュー1回目が
# 実際に突いた4呼び出し元(check_merge_flags/check_hotfix_label_immutability/
# check_hotfix_creation/check_pr_coverage 相当)と、単一トークンのサブコマンド
# (git commit)を含む。
_AC4_BASELINES = [
    ("glab", ["mr", "merge", "--rebase"], ("mr", "merge")),
    ("glab", ["issue", "update", "42", "-l", "hotfix"], ("issue", "update")),
    ("glab", ["issue", "create", "-l", "hotfix", "-t", "x"], ("issue", "create")),
    ("glab", ["mr", "create", "--fill"], ("mr", "create")),
    ("glab", ["mr", "list", "--per-page", "5"], ("mr", "list")),
    ("git", ["commit", "--no-verify"], ("commit",)),
]

# 単一トークンで挿入するフラグ。curated な集合の内外、定義すら無いもの
# (`-z`/`--zz`/`-Q`)を混ぜる。裸の `--`(打ち切り)、`=` 結合形(`--repo=o/r`)、
# 2文字を超える短縮形(`-Ro/r`)も含める(#1454 レビュー2回目 SUGGESTION:
# #1454 レビュー1回目の回帰 family(あいだフラグ)がこの3形の変種で再発していないことを
# 性質テストとしても押さえる。正しさそのものは `p_prev` が merge-base を厳密に
# 再現することで構造的に成り立つため、ここは多様性を広げるための追加)。
_AC4_SINGLE_INSERTS = [
    "-z",
    "--zz",
    "-Q",
    "--long-unknown",
    "-R",
    "--repo",
    "--jq",
    "-F",
    "--output",
    "-p",
    "--page",
    "-P",
    "--per-page",
    "-C",
    "--git-dir",
    "--work-tree",
    "-c",
    "--namespace",
    "--config-env",
    "--attr-source",
    "--no-advice",
    "--bare",
    "--",
    "--repo=o/r",
    "-Ro/r",
]

# 対トークン(フラグ+値)で挿入する組。curated な集合の内外、各サブコマンドが
# 個別に定義する値フラグ(`--sha`/`-m`/`--title`/`--due-date`)を含む。
# `("-C", "commit")`(値がサブコマンド語と同名)も含める(#1454 レビュー2回目
# SUGGESTION: BLOCKING2 が突いた形をこの性質テストの生成器にも足す)。
_AC4_PAIR_INSERTS = [
    ("-R", "o/r"),
    ("--repo", "o/r"),
    ("--jq", ".x"),
    ("-F", "json"),
    ("--output", "json"),
    ("-p", "2"),
    ("--page", "2"),
    ("-P", "2"),
    ("--per-page", "2"),
    ("-C", "somepath"),
    ("--git-dir", ".git"),
    ("--work-tree", "."),
    ("-c", "a=b"),
    ("--namespace", "ns"),
    ("--config-env", "a=B"),
    ("--attr-source", "HEAD"),
    ("--sha", "abc123"),
    ("-m", "msg"),
    ("--title", "x"),
    ("--due-date", "2026-01-01"),
    ("-C", "commit"),
]


def _ac4_generate_cases():
    """基準コマンド×各位置への単一/対トークン挿入で入力を生成する(AC4)。"""
    cases = []
    for program, base_rest, subcommands in _AC4_BASELINES:
        value_flags = _FROZEN_617C4A88_GLOBAL_VALUE_FLAGS.get(program, set())
        for pos in range(len(base_rest) + 1):
            for tok in _AC4_SINGLE_INSERTS:
                rest = base_rest[:pos] + [tok] + base_rest[pos:]
                cases.append((program, rest, subcommands, value_flags))
            for flag, value in _AC4_PAIR_INSERTS:
                rest = base_rest[:pos] + [flag, value] + base_rest[pos:]
                cases.append((program, rest, subcommands, value_flags))
    return cases


def _import_guard_module():
    hooks_dir = os.path.dirname(HOOK)
    if hooks_dir not in sys.path:
        sys.path.insert(0, hooks_dir)
    import guard

    return guard


class FrozenMergeBaseAlignmentIsNeverLostByNewDetection(unittest.TestCase):
    """#1454 AC4: merge-base(617c4a88)の判定が allow 方向へ動いた入力が1件も無い
    ことを、列挙ではなく性質として固定する。

    基準コマンド×各位置への単一/対トークン挿入で生成した1,000件以上の入力について、
    凍結した参照実装(merge-base の位置引数整列をそのまま再現したもの)が呼び出しを
    検出する入力では、production の新しい `invokes()` も同じ `rest` を返す
    ことを検査する。判定文字列の同一性は要求しない(どのガードが先に発火するかは
    変わりうる)。
    """

    def test_new_invokes_detects_everything_the_frozen_reference_detects(self):
        guard = _import_guard_module()
        cases = _ac4_generate_cases()
        self.assertGreaterEqual(
            len(cases), 1000, "AC4 が要求する1,000件以上の生成に届いていない"
        )
        checked = 0
        for program, rest, subcommands, value_flags in cases:
            if not _frozen_617c4a88_invokes_detects(rest, subcommands, value_flags):
                continue
            checked += 1
            command = program + " " + " ".join(shlex.quote(t) for t in rest)
            found = guard.invokes(command, program, subcommands)
            self.assertEqual(
                found,
                [rest],
                "参照実装(merge-base)が検出した入力 %r (%s %s) を新しい "
                "invokes() が見失った、または residual/help 判定が変わった"
                % (rest, program, subcommands),
            )
        self.assertGreater(
            checked, 0, "参照実装が検出した入力が1件も無い(このテストは空虚)"
        )


class SlashCommandReadOnlyStage(unittest.TestCase):
    """#1469: スラッシュコマンド起動では `Skill` ツールが呼ばれないため、
    `UserPromptSubmit` の `prompt` 先頭からマーカーを立てる。`ReportBugReadOnlyStage` の
    合成 `Skill` payload 方式と併存する。実サブプロセスを駆動し、スタブは使わない。
    """

    SESSION = "slash-session"

    def _root(self):
        root = tempfile.mkdtemp()
        os.makedirs(os.path.join(root, ".claude"))
        return root

    def _marker(self, root):
        return os.path.join(root, ".claude", ".state", "readonly-%s" % self.SESSION)

    def _prompt(self, prompt, root):
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            proc = subprocess.run(
                [sys.executable, HOOK, "prompt"],
                input=json.dumps(
                    {"prompt": prompt, "session_id": self.SESSION, "cwd": root}
                ),
                capture_output=True,
                text=True,
                timeout=60,
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertEqual(0, proc.returncode, proc.stderr)

    def _read(self, root):
        with open(self._marker(root), encoding="utf-8") as f:
            return f.read().strip()

    def _put_marker(self, root, skill):
        os.makedirs(os.path.dirname(self._marker(root)), exist_ok=True)
        with open(self._marker(root), "w", encoding="utf-8") as f:
            f.write(skill)

    def test_each_read_only_skill_writes_its_marker(self):
        for skill in ("report-bug", "discover-issues", "triage-backlog", "ready-issue"):
            root = self._root()
            self._prompt("/%s 何か" % skill, root)
            self.assertEqual(skill, self._read(root))

    def test_bare_slash_command_writes_marker(self):
        root = self._root()
        self._prompt("/ready-issue", root)
        self.assertEqual("ready-issue", self._read(root))

    def test_report_bug_marker_allows_hotfix_create(self):
        root = self._root()
        self._prompt("/report-bug 記事が重複投稿される", root)
        cmd = "glab issue create --title x --label user-request,bug,priority::P0,hotfix,status::Backlog"
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            reason = run_hook(
                "bash",
                {"tool_input": {"command": cmd}, "session_id": self.SESSION, "cwd": root},
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertIsNone(reason)

    def test_ready_issue_marker_still_denies_hotfix_create(self):
        root = self._root()
        self._prompt("/ready-issue", root)
        cmd = "glab issue create --title x --label hotfix,priority::P0,status::Backlog"
        reason = _run_in_root("bash", {"command": cmd}, root, self.SESSION)
        self.assertIsNotNone(reason)

    def test_no_marker_still_denies_hotfix_create(self):
        root = self._root()
        cmd = "glab issue create --title x --label hotfix,priority::P0,status::Backlog"
        self.assertIsNotNone(_run_in_root("bash", {"command": cmd}, root, self.SESSION))

    def test_read_only_stage_denies_write_and_bash(self):
        for skill in ("discover-issues", "triage-backlog", "ready-issue"):
            root = self._root()
            self._prompt("/%s" % skill, root)
            target = os.path.join(root, "docs", "x.md")
            self.assertIsNotNone(
                _run_in_root("write", {"file_path": target, "content": "x"}, root, self.SESSION)
            )
            for cmd in (
                "rm -rf apps/web/src",
                "sed -i s/a/b/ CLAUDE.md",
                "echo hi > notes.txt",
            ):
                self.assertIsNotNone(
                    _run_in_root("bash", {"command": cmd}, root, self.SESSION), cmd
                )

    def test_other_prompts_create_no_marker(self):
        for prompt in (
            "こんにちは",
            "/plan-issue 何か",
            "先に /report-bug を実行して",
            " 説明 /ready-issue",
            "",
        ):
            root = self._root()
            self._prompt(prompt, root)
            self.assertFalse(os.path.exists(self._marker(root)), prompt)

    def test_other_prompts_remove_existing_marker(self):
        for prompt in ("こんにちは", "/plan-issue", "本文中に /report-bug がある"):
            root = self._root()
            self._put_marker(root, "ready-issue")
            self._prompt(prompt, root)
            self.assertFalse(os.path.exists(self._marker(root)), prompt)

    def test_slash_command_replaces_existing_marker(self):
        root = self._root()
        self._put_marker(root, "ready-issue")
        self._prompt("/report-bug x", root)
        self.assertEqual("report-bug", self._read(root))

    def test_marker_content_is_never_taken_from_prompt(self):
        root = self._root()
        self._prompt("/report-bug-evil x", root)
        self.assertFalse(os.path.exists(self._marker(root)))

    def test_clear_subcommand_still_removes_marker(self):
        root = self._root()
        self._put_marker(root, "ready-issue")
        env_backup = os.environ.pop("CLAUDE_PROJECT_DIR", None)
        try:
            subprocess.run(
                [sys.executable, HOOK, "clear"],
                input=json.dumps({"prompt": "/report-bug x", "session_id": self.SESSION, "cwd": root}),
                capture_output=True, text=True, timeout=60,
            )
        finally:
            if env_backup is not None:
                os.environ["CLAUDE_PROJECT_DIR"] = env_backup
        self.assertFalse(os.path.exists(self._marker(root)))


class FdNumberIsNotAnArgvToken(unittest.TestCase):
    """#1455: リダイレクト演算子に接した fd 番号(`2>&1` の `2`)は argv に残らない。

    残ると位置引数として数えられ、`glab 2>&1 mr merge --rebase` の前方一致が崩れ、
    `glab mr merge --help 2>&1` の `--help` 判定が残余引数 `2` で外れる。
    """

    def _explain(self, command):
        proc = subprocess.run(
            [sys.executable, HOOK, "explain", command],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        return proc.stdout

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

    def test_leading_fd_duplication_does_not_hide_rebase_merge(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("glab 2>&1 mr merge --rebase")))

    def test_trailing_fd_duplication_keeps_help_detection(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab mr merge --help 2>&1")))

    def test_help_with_devnull_then_fd_duplication_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab --repo x/y mr merge --help >/dev/null 2>&1")))

    def test_explain_argv_has_no_fd_number(self):
        for command in ("glab 2>&1 mr merge", "glab mr merge 2>/dev/null",
                        "glab mr merge 1>&2", "glab mr merge 2>&-"):
            with self.subTest(command=command):
                out = self._explain(command)
                self.assertNotIn("'2'", out)
                self.assertNotIn("'1'", out)

    def test_detached_digit_is_a_positional(self):
        out = self._explain("glab mr merge 2 > out.txt")
        self.assertIn("'2'", out)

    def test_quoted_digit_before_operator_is_kept(self):
        out = self._explain("echo '2>x' 3")
        self.assertIn("2>x", out)

    def test_fd_duplication_is_not_a_write_in_read_only_stage(self):
        self.assertIsNone(self._in_stage("glab issue view 1 2>&1"))

    def test_fd_redirect_to_a_file_is_still_denied_in_read_only_stage(self):
        self.assertIsNotNone(self._in_stage("glab issue view 1 2>out.txt"))


class AttachedGlobalValueFlagHelpTest(unittest.TestCase):
    """#1449: 値直結形のグローバル値フラグが前置されても、`--help` だけの呼び出しは
    ヘルプとして扱われる(空白区切りの `--repo o/r` と判定が一致する)。"""

    def test_attached_short_repo_help_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab -Ro/r mr merge --help")))

    def test_equals_short_repo_help_is_allowed(self):
        self.assertIsNone(run_hook("bash", bash_payload("glab -R=o/r mr merge --help")))

    def test_equals_long_repo_help_is_allowed(self):
        self.assertIsNone(
            run_hook("bash", bash_payload("glab --repo=o/r mr merge --help"))
        )

    def test_attached_repo_without_help_is_still_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("glab -Ro/r mr merge")))

    def test_attached_repo_with_extra_token_is_not_help(self):
        self.assertIsNotNone(
            run_hook("bash", bash_payload("glab -Ro/r mr merge --auto-merge --help x"))
        )

    def test_clustered_repo_short_flag_without_help_is_still_denied(self):
        self.assertIsNotNone(run_hook("bash", bash_payload("glab -Rsss mr merge")))


def agent_payload(subagent_type, background=None, key="run_in_background"):
    tool_input = {"subagent_type": subagent_type, "prompt": "x", "description": "x"}
    if background is not None:
        tool_input[key] = background
    return {"tool_input": tool_input, "session_id": "test-session"}


class WorkflowAgentForeground(unittest.TestCase):
    """#1269: ワークフローのエージェントはフォアグラウンドで起動する。

    バックグラウンドのまま段階のターンが終わると、`claude -p` が結果を返して再開を
    1回消費する。理由と、汎用エージェントを対象外にした根拠は
    docs/WORKFLOW_RULE_RATIONALE.md を参照。
    """

    WORKFLOW_AGENTS = ("implementer", "reviewer", "qa", "project-planner")

    def test_background_workflow_agent_is_denied(self):
        for agent in self.WORKFLOW_AGENTS:
            with self.subTest(agent=agent):
                reason = run_hook("agent", agent_payload(agent, True))
                self.assertIsNotNone(reason)
                self.assertIn("フォアグラウンド", reason)
                self.assertIn("run_in_background", reason)

    def test_foreground_workflow_agent_is_allowed(self):
        for agent in self.WORKFLOW_AGENTS:
            for flag in (None, False):
                with self.subTest(agent=agent, flag=flag):
                    self.assertIsNone(run_hook("agent", agent_payload(agent, flag)))

    def test_background_general_purpose_agent_is_allowed(self):
        for agent in ("general-purpose", "Explore", "claude", None):
            with self.subTest(agent=agent):
                self.assertIsNone(run_hook("agent", agent_payload(agent, True)))

    def test_missing_tool_input_is_allowed(self):
        self.assertIsNone(run_hook("agent", {"session_id": "test-session"}))


class AgentHookRegistration(unittest.TestCase):
    def test_settings_registers_guard_for_agent_and_task(self):
        path = os.path.join(os.path.dirname(HOOK), "..", "settings.json")
        with open(path, encoding="utf-8") as f:
            settings = json.load(f)
        entries = [
            e for e in settings["hooks"]["PreToolUse"] if e.get("matcher") == "Agent|Task"
        ]
        self.assertEqual(len(entries), 1)
        commands = [h["command"] for h in entries[0]["hooks"]]
        self.assertTrue(any("guard.py" in c and c.rstrip().endswith("agent") for c in commands))


if __name__ == "__main__":
    unittest.main()
