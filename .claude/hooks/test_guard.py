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

    GitLab の `glab mr merge` は、マージ方式のフラグを付けないとマージコミットを作る
    (プロジェクト設定 `squash_option` が `default_off` のため)。`gh pr merge` は方式未指定だと
    対話的に尋ねる仕様だったので「禁止フラグの検出」で足りていたが、GitLab では
    **`--squash` の不在そのものが規約違反**になる。判定は「squash の要求」でなければならない。
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
        """方式を指定しない `glab mr merge` はマージコミットになるため拒否する。"""
        reason = run_hook("bash", bash_payload("glab mr merge 42"))
        self.assertIsNotNone(reason, "方式未指定の glab mr merge が拒否されていない")
        self.assertIn("squash", reason)

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

