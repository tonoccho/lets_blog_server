#!/usr/bin/env python3
"""developの検証済みコミットをmainへ直接マージし、semverタグを付ける(#1274)。

    python3 scripts/release-verify-tag.py [commit] [--bump patch|minor|major]

利用者の決定(Issue #1274 Background):

  1. main / develop は GitHub と同期を取り、main に付いたタグを次のリリースバージョンとする。
  2. タグ名はsemver(`MAJOR.MINOR.PATCH`、`v`接頭辞なし)。既存の `0.3.0` の続きにする。
  3. develop -> main はdevelopをmainへマージする(強制リセットはしない)。
     マージコミットの木が検証したコミットの木と同一であることを機械的に確かめる。
  4. タグはGitLab(origin)で作る。GitHubへは#1256のpushミラーが届ける
     (このスクリプトはGitHubに一切触れない)。

## 何をするか

1. 対象コミットを解決し固定する(省略時は`origin/develop`の先頭)。
   `origin/develop`から到達できない、または既に`origin/main`に含まれる場合は
   どの手順も始めずに拒否する。
2. メイン作業ツリーとは別のclone(isolated checkout)を作り、固定したSHAをdetachedで
   検証する。`git worktree`はref(ブランチ・タグ)を元リポジトリと共有してしまうため
   使わない(Issue Implementation Notes)。
3. 無人ループと共有している`cycle.lock`
   (`${CLAUDE_AUTO_STATE_DIR:-$HOME/.local/state/claude-auto}/cycle.lock`)を取得する。
   最初の手順を始める前から、push・引き渡しの完了まで保持する。
4. 事前確認: `origin/main`の先頭に固定したSHAを`--no-ff`でマージし、
   結果の木が固定したSHAの木と同一であることを確かめる。不一致ならテスト手順を
   1つも始めずに失敗する。
5. 全手順(依存導入・Pythonユニットテスト・web/extension/mcp-server/penpot-plugin/
   api-clientのテスト・lint・build・受け入れテスト・バックエンドの`./gradlew test lint`)を
   実行する。ゼロ許容(全終了コード0、failed/skipped/did not run/flakyが全て0)。
6. 成功したら、`origin/main`を再取得してもう一度マージ+木の同一性確認を行い、
   マージコミットにsemverの注釈付きタグを付け、`git push --atomic`で
   `main`とタグを1回で送る。
7. 成功・失敗にかかわらず、終了前に共有スタックをメイン作業ツリーが所有する状態へ戻す。

## テスト専用の差し替え口(本番では絶対に設定しない)

  RELEASE_VERIFY_STEP_TABLE      要件4の手順表を丸ごと置き換えるJSONファイルへのパス。
  RELEASE_VERIFY_HANDOFF_COMMAND 要件10の引き渡しコマンドを置き換えるJSONファイルへのパス。
  RELEASE_VERIFY_MAIN_WORKTREE   「メイン作業ツリー」の場所(既定: このリポジトリ)。
  RELEASE_VERIFY_ORIGIN_URL      originのURL(既定: メイン作業ツリーの`git remote get-url origin`)。
  RELEASE_VERIFY_STATE_DIR       cycle.lockを置くディレクトリ
                                 (既定: `${CLAUDE_AUTO_STATE_DIR:-~/.local/state/claude-auto}`)。
  RELEASE_VERIFY_LOG_DIR         ログの出力先(既定: `~/.local/state/release-verify-tag/logs/...`)。
  RELEASE_VERIFY_CHECKOUT_PARENT 隔離チェックアウトの親ディレクトリ。
  RELEASE_VERIFY_LOCK_TIMEOUT / RELEASE_VERIFY_LOCK_POLL_INTERVAL  ロック待機の上限・間隔(秒)。
"""

import argparse
import fcntl
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")

MSG_UNREACHABLE = "対象コミットは origin/develop から到達できません"
MSG_ALREADY_RELEASED = "対象コミットは既に origin/main に含まれています"
MSG_TREE_MISMATCH = "木が一致しません(main へ直接コミットされた可能性)"
MSG_LOCK_TIMEOUT = "ロックの取得がタイムアウトしました"
MSG_PUSH_REJECTED = "origin への push が拒否されました"
MSG_VERSION_EXISTS = "タグが既に origin に存在します"
MSG_ENV_FILE_MISSING = (
    "メイン作業ツリーに .env が見つかりません(要件2: gitignore対象の入力)。"
    "cp .env.example .env で作成してください。"
)


# --------------------------------------------------------------------- semver
def compute_next_version(tag_names, bump="patch"):
    """origin上の厳密なsemverタグの最大値から、次のバージョンを計算する(要件7)。"""
    versions = []
    for name in tag_names:
        m = SEMVER_RE.match(name.strip())
        if m:
            versions.append(tuple(int(x) for x in m.groups()))
    if not versions:
        raise ValueError("厳密なsemverタグが origin に見つからない")
    versions.sort()
    major, minor, patch = versions[-1]
    if bump == "major":
        return "%d.0.0" % (major + 1)
    if bump == "minor":
        return "%d.%d.0" % (major, minor + 1)
    return "%d.%d.%d" % (major, minor, patch + 1)


# --------------------------------------------------------------------- git helpers
class GitError(RuntimeError):
    pass


def sh(argv, cwd=None, env=None, check=True, timeout=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    r = subprocess.run(
        argv, cwd=cwd, env=e, capture_output=True, text=True, timeout=timeout
    )
    if check and r.returncode != 0:
        raise GitError(
            "コマンド失敗: %s (cwd=%s)\n%s\n%s" % (argv, cwd, r.stdout, r.stderr)
        )
    return r


def git(args, cwd, check=True, env=None, timeout=120):
    return sh(["git"] + args, cwd=cwd, check=check, env=env, timeout=timeout)


def rev_parse(cwd, ref):
    return git(["rev-parse", ref], cwd=cwd).stdout.strip()


def tree_of(cwd, ref):
    return git(["rev-parse", ref + "^{tree}"], cwd=cwd).stdout.strip()


def is_ancestor(cwd, ancestor, descendant):
    r = git(["merge-base", "--is-ancestor", ancestor, descendant], cwd=cwd, check=False)
    return r.returncode == 0


def list_tags(cwd):
    r = git(["tag", "--list"], cwd=cwd)
    return [l for l in r.stdout.splitlines() if l.strip()]


# --------------------------------------------------------------------- gitignored inputs (要件2)
def copy_gitignored_inputs(main_worktree, checkout_dir):
    """`.env` と `certs/` をメイン作業ツリーから隔離チェックアウトへコピーする。

    `git clone` はgitignore対象を含まないため、`scripts/rebuild-acceptance-env.sh` が
    要求する `.env` と、reverse-proxy用の `certs/` はここで別途用意する必要がある。

    戻り値: `.env` が見つかってコピーできたか(見つからなければFalse)。
    """
    env_src = os.path.join(main_worktree, ".env")
    if not os.path.exists(env_src):
        return False
    shutil.copy2(env_src, os.path.join(checkout_dir, ".env"))

    certs_src = os.path.join(main_worktree, "certs")
    if os.path.isdir(certs_src):
        certs_dst = os.path.join(checkout_dir, "certs")
        shutil.copytree(certs_src, certs_dst, dirs_exist_ok=True)
    return True


def load_credential_env(path):
    """`~/.config/lets-blog-e2e.env` を読み込み、dict で返す(要件2)。

    ファイルが無ければ空dict(資格情報を要求しない手順まで失敗させないため)。
    """
    result = {}
    if not path or not os.path.exists(path):
        return result
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            result[k.strip()] = v.strip()
    return result


# --------------------------------------------------------------------- lock
def describe_lock_holder(lock_path):
    try:
        r = subprocess.run(
            ["fuser", "-v", lock_path], capture_output=True, text=True, timeout=5
        )
        lines = [l.strip() for l in (r.stdout + r.stderr).splitlines() if l.strip()]
        return lines[-1] if lines else "不明"
    except Exception:
        return "不明"


def acquire_lock(lock_path, timeout, poll_interval, out):
    os.makedirs(os.path.dirname(lock_path), exist_ok=True)
    fh = open(lock_path, "a+")
    start = time.monotonic()
    while True:
        try:
            fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fh
        except BlockingIOError:
            elapsed = time.monotonic() - start
            if elapsed >= timeout:
                fh.close()
                return None
            holder = describe_lock_holder(lock_path)
            out.write(
                "ロック待機中... 保持者=%s 経過=%ds\n" % (holder, int(elapsed))
            )
            out.flush()
            time.sleep(max(0.01, min(poll_interval, timeout - elapsed)))


def release_lock(fh):
    try:
        fcntl.flock(fh, fcntl.LOCK_UN)
    finally:
        fh.close()


# --------------------------------------------------------------------- steps
#: 要件4「しっかり」= 全部。ここに列挙した手順が本番での既定値。
#: 各手順は隔離チェックアウト(cwdは"%CHECKOUT%"相対)で実行する。
DEFAULT_STEPS = [
    {
        "name": "deps-web",
        "argv": ["npm", "ci", "--prefix", "apps/web"],
        "cwd": "",
    },
    {
        "name": "deps-extension",
        "argv": ["npm", "ci", "--prefix", "apps/extension"],
        "cwd": "",
    },
    {
        "name": "deps-mcp-server",
        "argv": ["npm", "ci", "--prefix", "apps/mcp-server"],
        "cwd": "",
    },
    {
        "name": "deps-penpot-plugin",
        "argv": ["npm", "ci", "--prefix", "apps/penpot-plugin"],
        "cwd": "",
    },
    {
        "name": "deps-api-client",
        "argv": ["npm", "ci", "--prefix", "packages/api-client"],
        "cwd": "",
    },
    {
        "name": "python-unittest-scripts",
        "argv": [
            "python3",
            "-m",
            "unittest",
            "discover",
            "-s",
            "scripts",
            "-t",
            "scripts",
            "-p",
            "test_*.py",
        ],
        "cwd": "",
    },
    {
        "name": "python-unittest-hooks",
        "argv": [
            "python3",
            "-m",
            "unittest",
            "discover",
            "-s",
            ".claude/hooks",
            "-t",
            ".claude/hooks",
            "-p",
            "test_*.py",
        ],
        "cwd": "",
    },
    {
        # 要件5: 終了コードだけでなく、jest --json の実出力からskipped/todoを検出する。
        "name": "web-test",
        "argv": [
            "npm",
            "run",
            "test",
            "--prefix",
            "apps/web",
            "--",
            "--json",
            "--outputFile=%CHECKOUT%/apps/web/jest-report.json",
        ],
        "cwd": "",
        "counts_parser": "jest",
        "counts_source": "apps/web/jest-report.json",
    },
    {"name": "web-lint", "argv": ["npm", "run", "lint", "--prefix", "apps/web"], "cwd": ""},
    {"name": "web-build", "argv": ["npm", "run", "build", "--prefix", "apps/web"], "cwd": ""},
    {
        "name": "extension-test",
        "argv": ["npm", "run", "test", "--prefix", "apps/extension"],
        "cwd": "",
    },
    {
        "name": "extension-compile",
        "argv": ["npm", "run", "compile", "--prefix", "apps/extension"],
        "cwd": "",
    },
    {
        "name": "mcp-server-test",
        "argv": ["npm", "test", "--prefix", "apps/mcp-server"],
        "cwd": "",
    },
    {
        "name": "penpot-plugin-build",
        "argv": ["npm", "run", "build", "--prefix", "apps/penpot-plugin"],
        "cwd": "",
    },
    {
        "name": "penpot-plugin-build-ui",
        "argv": ["npm", "run", "build:ui", "--prefix", "apps/penpot-plugin"],
        "cwd": "",
    },
    {
        "name": "api-client-typecheck",
        "argv": ["npm", "run", "typecheck", "--prefix", "packages/api-client"],
        "cwd": "",
    },
    {
        # #1202の迂回。cycle.lockを保持していることが正当化根拠(要件3)。
        # 要件5: playwrightの`json`レポーターを既存の`html`に追加し、実出力から
        # skipped/flakyを検出する(`PLAYWRIGHT_JSON_OUTPUT_NAME`でファイルへ出力)。
        "name": "web-test-at-clean",
        "argv": [
            "npm",
            "run",
            "test:at:clean",
            "--prefix",
            "apps/web",
            "--",
            "--reporter=html,json",
        ],
        "cwd": "",
        "touches_stack": True,
        "env": {
            "ACCEPTANCE_RESET": "1",
            "AT_WORKTREE_CHECK_BYPASS": "1",
            "PLAYWRIGHT_JSON_OUTPUT_NAME": "%CHECKOUT%/apps/web/playwright-at-clean.json",
        },
        "counts_parser": "playwright_json",
        "counts_source": "apps/web/playwright-at-clean.json",
    },
    {
        # 要件5: jest --json (e2e/jest.config.js) の実出力からskipped/todoを検出する。
        "name": "extension-test-at",
        "argv": [
            "npm",
            "run",
            "test:at",
            "--prefix",
            "apps/extension",
            "--",
            "--json",
            "--outputFile=%CHECKOUT%/apps/extension/e2e-jest-report.json",
        ],
        "cwd": "",
        "touches_stack": True,
        "counts_parser": "jest",
        "counts_source": "apps/extension/e2e-jest-report.json",
    },
    {
        "name": "backend-expose-mysql",
        "argv": [
            "docker",
            "compose",
            "-f",
            "docker-compose.yml",
            "-f",
            "docker-compose.host-tests.yml",
            "up",
            "-d",
            "mysql",
        ],
        "cwd": "",
        "touches_stack": True,
    },
    {
        "name": "backend-check-test-db",
        "argv": ["bash", "scripts/check-test-db.sh"],
        "cwd": "",
        "touches_stack": True,
    },
    {
        # 要件5: `test`タスクが全11モジュールに残すJUnit XMLを集計し、
        # 終了コードだけでは見えないskippedを検出する(要件4)。
        "name": "backend-gradle-test-lint",
        "argv": ["./gradlew", "test", "lint"],
        "cwd": "",
        "touches_stack": True,
        "counts_parser": "junit_xml_glob",
        "counts_source": "**/build/test-results/test/*.xml",
    },
]

#: 要件10。共有スタックをメイン作業ツリーへ引き渡す既定コマンド
#: (Issue Implementation Notes 候補1)。メイン作業ツリーで実行する。
DEFAULT_HANDOFF = {
    "argv": ["bash", "scripts/rebuild-acceptance-env.sh", "--yes"],
    "cwd": "",
    "env": {"AT_WORKTREE_CHECK_BYPASS": "1"},
}


def load_json_table(env_var, default):
    path = os.environ.get(env_var)
    if not path:
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def substitute(argv, checkout_dir):
    return [a.replace("%CHECKOUT%", checkout_dir) for a in argv]


# --------------------------------------------------------------------- real counts parsers (要件5)
def parse_jest_json_counts(checkout_dir, source):
    """jest `--json --outputFile=...` の出力から件数を抽出する。"""
    path = os.path.join(checkout_dir, source)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return {
        "passed": data.get("numPassedTests", 0),
        "failed": data.get("numFailedTests", 0),
        "skipped": data.get("numPendingTests", 0) + data.get("numTodoTests", 0),
        "did_not_run": 0,
    }


def parse_playwright_json_counts(checkout_dir, source):
    """playwright `--reporter=...,json` の出力(`stats`)から件数を抽出する。

    flaky も要件5のゼロ許容の対象なので、`failed`/`skipped`とは別に`flaky`として保持する
    (`StepResult.ok`が見る)。
    """
    path = os.path.join(checkout_dir, source)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    stats = data.get("stats", {})
    return {
        "passed": stats.get("expected", 0),
        "failed": stats.get("unexpected", 0),
        "skipped": stats.get("skipped", 0),
        "did_not_run": 0,
        "flaky": stats.get("flaky", 0),
    }


def parse_junit_xml_glob_counts(checkout_dir, pattern):
    """`./gradlew test` が全モジュールに残すJUnit XML(`**/build/test-results/test/*.xml`)を
    集計する(要件4: 11モジュール全体)。1件も見つからなければ失敗として扱う。
    """
    paths = glob.glob(os.path.join(checkout_dir, pattern), recursive=True)
    if not paths:
        return None
    passed = failed = skipped = 0
    for p in paths:
        try:
            root = ET.parse(p).getroot()
        except ET.ParseError:
            failed += 1
            continue
        tests = int(root.get("tests", 0) or 0)
        failures = int(root.get("failures", 0) or 0)
        errors = int(root.get("errors", 0) or 0)
        sk = int(root.get("skipped", 0) or 0)
        failed += failures + errors
        skipped += sk
        passed += tests - failures - errors - sk
    return {"passed": passed, "failed": failed, "skipped": skipped, "did_not_run": 0}


COUNTS_PARSERS = {
    "jest": parse_jest_json_counts,
    "playwright_json": parse_playwright_json_counts,
    "junit_xml_glob": parse_junit_xml_glob_counts,
}


class StepResult:
    def __init__(self, name, returncode, counts, duration, command, log_path):
        self.name = name
        self.returncode = returncode
        self.counts = counts
        self.duration = duration
        self.command = command
        self.log_path = log_path

    @property
    def ok(self):
        c = self.counts
        return (
            self.returncode == 0
            and c["failed"] == 0
            and c["skipped"] == 0
            and c["did_not_run"] == 0
            and c.get("flaky", 0) == 0
        )

    def summary(self):
        return "passed=%s failed=%s skipped=%s did_not_run=%s" % (
            self.counts["passed"],
            self.counts["failed"],
            self.counts["skipped"],
            self.counts["did_not_run"],
        )


#: 共有スタックのcompose project名(#1297)。隔離チェックアウトは
#: `tempfile.mkdtemp(prefix="checkout-")` に作られ、`.env`にも`docker-compose.yml`にも
#: project名が無いため、明示しない呼び出しはcloneのディレクトリ名(`checkout-XXXX`)に
#: 解決されてしまう。`touches_stack`な手順のサブプロセス環境へ明示することで、
#: `scripts/wait-for-stack-healthy.sh`・`scripts/setup-shared-host-proxy.sh`・
#: `docker compose`直接呼び出し(`backend-expose-mysql`)のいずれも共有スタック
#: `lets_blog_server`を対象にする(`scripts/rebuild-acceptance-env.sh`が自分の
#: `COMPOSE_PROJECT`を子プロセスへ明示的に渡すのと対になる修正)。
SHARED_COMPOSE_PROJECT_NAME = "lets_blog_server"


def run_step(step, checkout_dir, log_dir, credential_env=None):
    argv = substitute(step["argv"], checkout_dir)
    cwd = os.path.join(checkout_dir, step.get("cwd", "") or "")
    env = dict(os.environ)
    env.update(credential_env or {})  # 要件2: 資格情報をサブプロセス環境へ読み込む
    if step.get("touches_stack"):
        env["COMPOSE_PROJECT_NAME"] = SHARED_COMPOSE_PROJECT_NAME
    step_env = {k: v.replace("%CHECKOUT%", checkout_dir) for k, v in step.get("env", {}).items()}
    env.update(step_env)
    log_path = os.path.join(log_dir, "%s.log" % step["name"])
    os.makedirs(log_dir, exist_ok=True)
    start = time.monotonic()
    r = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, text=True)
    duration = time.monotonic() - start
    with open(log_path, "w", encoding="utf-8") as f:
        f.write("$ %s\n(cwd=%s)\n\n" % (" ".join(argv), cwd))
        f.write(r.stdout)
        f.write(r.stderr)

    counts_parser_name = step.get("counts_parser")
    counts_file = step.get("counts_file")
    if counts_parser_name:
        parser = COUNTS_PARSERS[counts_parser_name]
        counts = parser(checkout_dir, step.get("counts_source", ""))
        if counts is None:
            counts = {"passed": 0, "failed": 1, "skipped": 0, "did_not_run": 0, "flaky": 0}
        else:
            counts.setdefault("flaky", 0)
    elif counts_file:
        counts_path = os.path.join(checkout_dir, counts_file)
        if os.path.exists(counts_path):
            with open(counts_path, encoding="utf-8") as f:
                data = json.load(f)
            counts = {
                "passed": data.get("passed", 0),
                "failed": data.get("failed", 0),
                "skipped": data.get("skipped", 0),
                "did_not_run": data.get("did_not_run", 0),
            }
        else:
            counts = {"passed": 0, "failed": 1, "skipped": 0, "did_not_run": 0}
    else:
        if r.returncode == 0:
            counts = {"passed": 1, "failed": 0, "skipped": 0, "did_not_run": 0}
        else:
            counts = {"passed": 0, "failed": 1, "skipped": 0, "did_not_run": 0}

    return StepResult(step["name"], r.returncode, counts, duration, " ".join(argv), log_path)


def run_handoff(handoff, main_worktree, log_dir):
    argv = handoff["argv"]
    cwd = os.path.join(main_worktree, handoff.get("cwd", "") or "")
    env = dict(os.environ)
    env.update(handoff.get("env", {}))
    log_path = os.path.join(log_dir, "handoff.log")
    os.makedirs(log_dir, exist_ok=True)
    r = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, text=True)
    with open(log_path, "w", encoding="utf-8") as f:
        f.write("$ %s\n(cwd=%s)\n\n" % (" ".join(argv), cwd))
        f.write(r.stdout)
        f.write(r.stderr)
    return r.returncode == 0, log_path


# --------------------------------------------------------------------- evidence retention (要件9)
#: 本番での既定。`docker compose logs` はメイン作業ツリーで実行する(共有スタックはそこにある)。
DEFAULT_DOCKER_LOGS_COMMAND = {
    "argv": ["docker", "compose", "logs", "--no-color"],
    "cwd": "",
}

#: Playwrightのレポート/生成物の既定の出力先(`apps/web/playwright.config.ts`の既定値)。
EVIDENCE_PATHS = ["apps/web/playwright-report", "apps/web/test-results"]


def capture_evidence(checkout_dir, main_worktree, log_dir, docker_logs_cmd, out):
    """隔離チェックアウトを削除する前に、Playwrightのレポートと`docker compose logs`を
    リポジトリ外(log_dir)へ保全する。レポートが存在しない場合は何もしない
    (受け入れテストの手順まで到達していない失敗など)。
    """
    for rel in EVIDENCE_PATHS:
        src = os.path.join(checkout_dir, rel)
        if os.path.isdir(src):
            dst = os.path.join(log_dir, "evidence", rel)
            shutil.copytree(src, dst, dirs_exist_ok=True)

    argv = docker_logs_cmd["argv"]
    cwd = os.path.join(main_worktree, docker_logs_cmd.get("cwd", "") or "")
    try:
        r = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=120)
        with open(os.path.join(log_dir, "docker-compose-logs.txt"), "w", encoding="utf-8") as f:
            f.write(r.stdout)
            f.write(r.stderr)
    except Exception as e:  # noqa: BLE001 - 証跡保全の失敗で本体の後始末を止めない
        out.write("docker compose logs の保全に失敗しました: %s\n" % e)


# --------------------------------------------------------------------- merge / tree check
def do_merge_and_check_tree(checkout_dir, main_tip, pinned_sha):
    """origin/mainの先頭にpinned_shaを--no-ffでマージし、木の同一性を確かめる(要件6)。

    戻り値: (merge_commit または None, 木が一致したか)
    """
    git(["checkout", "--detach", main_tip], cwd=checkout_dir)
    r = git(["merge", "--no-ff", "--no-edit", pinned_sha], cwd=checkout_dir, check=False)
    if r.returncode != 0:
        git(["merge", "--abort"], cwd=checkout_dir, check=False)
        return None, False
    merge_commit = rev_parse(checkout_dir, "HEAD")
    match = tree_of(checkout_dir, "HEAD") == tree_of(checkout_dir, pinned_sha)
    return merge_commit, match


def build_tag_message(pinned_sha, main_before, merge_commit, tree_id, start_time, end_time, step_results):
    lines = [
        "release-verify-tag.py による自動リリース",
        "pinned_sha: %s" % pinned_sha,
        "parents: %s %s" % (main_before, pinned_sha),
        "tree: %s" % tree_id,
        "start: %s" % start_time,
        "end: %s" % end_time,
        "",
        "手順:",
    ]
    for res in step_results:
        lines.append(
            "  - %s: rc=%s %s (%.1fs) %s"
            % (res.name, res.returncode, res.summary(), res.duration, res.command)
        )
    return "\n".join(lines)


# --------------------------------------------------------------------- main
def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("commit", nargs="?", default=None, help="対象コミット(省略時: origin/developの先頭)")
    p.add_argument(
        "--bump",
        choices=["patch", "minor", "major"],
        default="patch",
        help="semverの上げ方(既定: patch。Q1利用者決定)",
    )
    return p.parse_args(argv)


def default_main_worktree():
    return os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def default_state_dir():
    return os.environ.get(
        "CLAUDE_AUTO_STATE_DIR", os.path.expanduser("~/.local/state/claude-auto")
    )


def fail(out, message, detail=""):
    out.write("失敗: %s\n" % message)
    if detail:
        out.write(detail + "\n")
    out.write("origin の main は変更されていません。新しいタグは作られていません。\n")


def main(argv=None):
    args = parse_args(argv)
    out = sys.stdout

    main_worktree = os.environ.get("RELEASE_VERIFY_MAIN_WORKTREE") or default_main_worktree()
    if os.environ.get("RELEASE_VERIFY_ORIGIN_URL"):
        origin_url = os.environ["RELEASE_VERIFY_ORIGIN_URL"]
    else:
        origin_url = git(["remote", "get-url", "origin"], cwd=main_worktree).stdout.strip()

    state_dir = os.environ.get("RELEASE_VERIFY_STATE_DIR") or default_state_dir()
    lock_path = os.environ.get("RELEASE_VERIFY_LOCK_PATH") or os.path.join(state_dir, "cycle.lock")
    lock_timeout = float(os.environ.get("RELEASE_VERIFY_LOCK_TIMEOUT", "28800"))
    lock_poll_interval = float(os.environ.get("RELEASE_VERIFY_LOCK_POLL_INTERVAL", "30"))

    log_dir_parent = os.environ.get(
        "RELEASE_VERIFY_LOG_DIR", os.path.expanduser("~/.local/state/release-verify-tag/logs")
    )
    timestamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    log_dir = os.path.join(log_dir_parent, "%s-%d" % (timestamp, os.getpid()))
    os.makedirs(log_dir, exist_ok=True)
    out.write("ログ: %s\n" % log_dir)

    checkout_parent = os.environ.get(
        "RELEASE_VERIFY_CHECKOUT_PARENT",
        os.path.expanduser("~/.local/state/release-verify-tag/checkouts"),
    )
    os.makedirs(checkout_parent, exist_ok=True)
    checkout_dir = tempfile.mkdtemp(prefix="checkout-", dir=checkout_parent)

    start_time = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    steps = load_json_table("RELEASE_VERIFY_STEP_TABLE", DEFAULT_STEPS)
    handoff = load_json_table("RELEASE_VERIFY_HANDOFF_COMMAND", DEFAULT_HANDOFF)
    docker_logs_cmd = load_json_table(
        "RELEASE_VERIFY_DOCKER_LOGS_COMMAND", DEFAULT_DOCKER_LOGS_COMMAND
    )

    cred_env_path = os.environ.get(
        "RELEASE_VERIFY_CRED_ENV_FILE", os.path.expanduser("~/.config/lets-blog-e2e.env")
    )
    credential_env = load_credential_env(cred_env_path)

    lock_fh = None
    stack_touched = False
    try:
        out.write("==> 隔離チェックアウトを作成: %s\n" % checkout_dir)
        git(["clone", "--origin", "origin", origin_url, checkout_dir], cwd=None, timeout=600)

        develop_tip = rev_parse(checkout_dir, "origin/develop")
        sha = rev_parse(checkout_dir, args.commit) if args.commit else develop_tip

        out.write("==> 対象コミット: %s\n" % sha)
        if not is_ancestor(checkout_dir, sha, develop_tip):
            fail(out, MSG_UNREACHABLE, "commit=%s develop=%s" % (sha, develop_tip))
            return 1

        main_before = rev_parse(checkout_dir, "origin/main")
        if is_ancestor(checkout_dir, sha, main_before):
            fail(out, MSG_ALREADY_RELEASED, "commit=%s main=%s" % (sha, main_before))
            return 1

        out.write("==> cycle.lock を取得: %s\n" % lock_path)
        lock_fh = acquire_lock(lock_path, lock_timeout, lock_poll_interval, out)
        if lock_fh is None:
            fail(out, MSG_LOCK_TIMEOUT, "lock=%s timeout=%ss" % (lock_path, lock_timeout))
            return 1
        out.write("==> ロックを取得しました\n")

        out.write("==> 事前確認: main へのマージと木の同一性\n")
        _merge_commit, tree_match = do_merge_and_check_tree(checkout_dir, main_before, sha)
        if not tree_match:
            fail(out, MSG_TREE_MISMATCH, "main=%s commit=%s" % (main_before, sha))
            return 1

        git(["checkout", "--detach", sha], cwd=checkout_dir)

        out.write("==> gitignore対象の入力(.env / certs/)をメイン作業ツリーからコピー\n")
        if not copy_gitignored_inputs(main_worktree, checkout_dir):
            fail(out, MSG_ENV_FILE_MISSING, "main_worktree=%s" % main_worktree)
            return 1

        # #1298: 隔離チェックアウト自身の git フックを束縛する。python-unittest-scripts
        # 手順が実行する test_git_hooks_binding.py の test_core_hooks_path_is_bound は
        # 「このチェックアウトの」core.hooksPath を見るため、隔離チェックアウト
        # (メイン作業ツリーとは別の.git/config)では明示的に束縛しない限り必ず失敗する。
        # メイン作業ツリーの core.hooksPath には一切触れない(setup-git-hooks.sh は
        # 自分の位置からリポジトリ根を割り出すので、cwd=checkout_dir で実行すれば
        # 隔離チェックアウトの .git/config だけが書き換わる)。
        #
        # 束縛は手順の実行中だけに留める: 前後の do_merge_and_check_tree が作る
        # マージコミットは、束縛されたままだと隔離チェックアウト自身の pre-commit
        # フック(フェーズ分離等)を通ってしまい、release の性質(検証済みの木を
        # そのままmainへ運ぶ)と無関係な理由で失敗しかねない。手順の直前で束縛し、
        # 手順の直後で必ず外す。
        out.write("==> 隔離チェックアウトで git フックを束縛: bash scripts/setup-git-hooks.sh\n")
        bind_result = sh(["bash", "scripts/setup-git-hooks.sh"], cwd=checkout_dir, check=False)
        if bind_result.returncode != 0:
            fail(
                out,
                "隔離チェックアウトでの git フック束縛に失敗しました",
                bind_result.stdout + bind_result.stderr,
            )
            return 1

        out.write("==> 手順を実行(ゼロ許容)\n")
        step_results = []
        overall_ok = True
        failing = None
        try:
            for step in steps:
                if step.get("touches_stack"):
                    stack_touched = True
                out.write("--- %s\n" % step["name"])
                res = run_step(step, checkout_dir, log_dir, credential_env=credential_env)
                step_results.append(res)
                out.write("    rc=%s %s (%.1fs)\n" % (res.returncode, res.summary(), res.duration))
                if not res.ok:
                    overall_ok = False
                    failing = res
                    break
        finally:
            # 手順の外(事前確認・本番のマージ、push)では束縛しない(上記コメント参照)。
            git(["config", "--unset", "core.hooksPath"], cwd=checkout_dir, check=False)

        if not overall_ok:
            fail(
                out,
                "手順が失敗しました: %s" % failing.name,
                "ログ: %s\n%s" % (failing.log_path, failing.summary()),
            )
            return 1

        out.write("==> 本番: origin/main を再取得してマージ+木の同一性を再確認\n")
        git(["fetch", "origin", "main"], cwd=checkout_dir)
        main_now = rev_parse(checkout_dir, "origin/main")
        merge_commit, tree_match2 = do_merge_and_check_tree(checkout_dir, main_now, sha)
        if not tree_match2:
            fail(out, MSG_TREE_MISMATCH, "main=%s commit=%s" % (main_now, sha))
            return 1

        version = compute_next_version(list_tags(checkout_dir), args.bump)
        if version in list_tags(checkout_dir):
            fail(out, MSG_VERSION_EXISTS, "version=%s" % version)
            return 1

        end_time = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        tree_id = tree_of(checkout_dir, "HEAD")
        message = build_tag_message(
            sha, main_now, merge_commit, tree_id, start_time, end_time, step_results
        )
        git(["tag", "-a", version, "-m", message, merge_commit], cwd=checkout_dir)

        out.write("==> push: %s と %s を1回で送る\n" % (merge_commit[:12], version))
        push = git(
            [
                "push",
                "--atomic",
                "origin",
                "%s:refs/heads/main" % merge_commit,
                "refs/tags/%s" % version,
            ],
            cwd=checkout_dir,
            check=False,
        )
        if push.returncode != 0:
            fail(out, MSG_PUSH_REJECTED, push.stdout + push.stderr)
            return 1

        out.write("==> 成功: main=%s tag=%s\n" % (merge_commit, version))
        return 0
    finally:
        if stack_touched:
            out.write("==> 証跡(Playwrightレポート・docker compose logs)を保全中\n")
            capture_evidence(checkout_dir, main_worktree, log_dir, docker_logs_cmd, out)
            out.write("==> 共有スタックをメイン作業ツリーへ引き渡し中\n")
            ok, handoff_log = run_handoff(handoff, main_worktree, log_dir)
            out.write("    引き渡し: %s (ログ: %s)\n" % ("OK" if ok else "NG", handoff_log))
        if lock_fh is not None:
            release_lock(lock_fh)
            out.write("==> ロックを解放しました\n")
        shutil.rmtree(checkout_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
