#!/usr/bin/env python3
"""developの検証済みコミットに版数コミットを積んでmainへ直接マージし、semverタグを
付ける。タグ付け後、developだけを次の開発版数へ進める(#1274, #1305)。

    python3 scripts/release-verify-tag.py [commit] [--bump patch|minor|major]

利用者の決定(Issue #1274 Background、#1305 Background):

  1. main / develop は GitHub と同期を取り、main に付いたタグを次のリリースバージョンとする。
  2. タグ名はsemver(`MAJOR.MINOR.PATCH`、`v`接頭辞なし)。既存の `0.3.0` の続きにする。
  3. develop -> main はdevelopをmainへマージする(強制リセットはしない)。
     マージコミットの木が検証したコミットの木と同一であることを機械的に確かめる。
  4. タグはGitLab(origin)で作る。GitHubへは#1256のpushミラーが届ける
     (このスクリプトはGitHubに一切触れない)。
  5. リリースを1回実行すると、全コンポーネントの版数をXにしたコミットにタグを付け、
     タグ付け後はdevelopだけを`X.Y.(Z+1)-DEVELOP`へ進める(#1305)。

## 何をするか

1. P(`origin/develop`の先頭。指定時はそれと一致することを要求)を固定する。
   Pが現在の`origin/develop`の先頭でなければ、どの手順も始めずに拒否する
   (#1305 要件6: 古いコミットを指定したリリースはできない)。
2. メイン作業ツリーとは別のclone(isolated checkout)を作り、Pをdetachedで
   検証する。`git worktree`はref(ブランチ・タグ)を元リポジトリと共有してしまうため
   使わない(Issue Implementation Notes)。
3. 無人ループと共有している`cycle.lock`
   (`${CLAUDE_AUTO_STATE_DIR:-$HOME/.local/state/claude-auto}/cycle.lock`)を取得する。
   最初の手順を始める前から、push・引き渡しの完了まで保持する。
4. 版数X を`compute_next_version`で決める(#1305 要件2: テスト手順より前)。
   同名タグが既に存在しないかを、ここ(計算時)と push 直前の両方で確認する。
5. リリースコミットR(Pの子。版数ファイルの変更だけを含む)を、git フックを
   束縛する前に作る(#1305 要件3。Implementation Notes: `apps/mcp-server/src/server.js`は
   プロダクション扱いなので、束縛したまま作ると隔離チェックアウトのpre-commitフックに
   拒否される)。
6. 事前確認: `origin/main`の先頭にRを`--no-ff`でマージし、結果の木がRの木と
   同一であることを確かめる。不一致ならテスト手順を1つも始めずに失敗する。
7. 全手順(依存導入・Pythonユニットテスト・web/extension/mcp-server/penpot-plugin/
   api-clientのテスト・lint・build・受け入れテスト・バックエンドの`./gradlew test lint`)を
   Rに対して実行する。ゼロ許容(全終了コード0、failed/skipped/did not run/flakyが全て0)。
8. 成功したら、`origin/main`を再取得してもう一度マージ+木の同一性確認をRに対して行い、
   同名タグの不存在とdevelopがPのままであることを再確認する(#1305 要件2/5/6: 競合対策)。
9. 次の開発版数コミントV(Rの子。全箇所を`X.Y.(Z+1)-DEVELOP`へ)を作る(#1305 要件4)。
   マージコミットM(main, R)にsemverの注釈付きタグ(PとRのSHAを記録)を付け、
   `git push --atomic`で`M:refs/heads/main`・`V:refs/heads/develop`・タグを
   1回で送る(#1305 要件5: `--force`は使わない。developがPから進んでいたら全体を失敗とする)。
10. 成功・失敗にかかわらず、終了前に共有スタックをメイン作業ツリーが所有する状態へ戻す。

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

MSG_P_NOT_TIP = (
    "P(対象コミット)が現在の origin/develop の先頭と一致しません"
    "(#1305 要件6: 古いコミットを指定したリリースはできません)"
)
MSG_TREE_MISMATCH = "木が一致しません(main へ直接コミットされた可能性、または R の生成に問題)"
MSG_LOCK_TIMEOUT = "ロックの取得がタイムアウトしました"
MSG_PUSH_REJECTED = "origin への push が拒否されました"
MSG_VERSION_EXISTS = "タグが既に origin に存在します"
MSG_DEVELOP_ADVANCED = (
    "検証中に origin/develop が P から進みました(#1305 要件5: 再試行はしません)"
)
MSG_ENV_FILE_MISSING = (
    "メイン作業ツリーに .env が見つかりません(要件2: gitignore対象の入力)。"
    "cp .env.example .env で作成してください。"
)


# --------------------------------------------------------------------- version files (#1305 要件1)
#: Requirement 1 の全ファイル・全箇所。(相対パス, 種別)。
#: 種別ごとの意味は set_version_everywhere() / read_version() 参照。
VERSION_LOCATIONS = [
    ("apps/web/package.json", "npm_pkg"),
    ("apps/web/package-lock.json", "npm_lock"),
    ("apps/extension/package.json", "npm_pkg"),
    ("apps/extension/package-lock.json", "npm_lock"),
    ("apps/mcp-server/package.json", "npm_pkg"),
    ("apps/mcp-server/package-lock.json", "npm_lock"),
    ("apps/mcp-server/src/server.js", "server_js"),
    ("apps/penpot-plugin/package.json", "npm_pkg"),
    ("apps/penpot-plugin/package-lock.json", "npm_lock"),
    ("packages/api-client/package.json", "npm_pkg"),
    ("packages/api-client/package-lock.json", "npm_lock"),
    ("build.gradle", "gradle"),
]

#: npm の package.json / package-lock.json の `"version": "..."` フィールド。
VERSION_FIELD_RE = re.compile(r'"version":\s*"[^"]*"')
#: build.gradle の `subprojects { version = '...' }`。
GRADLE_VERSION_RE = re.compile(r"(version\s*=\s*)'[^']*'")
#: apps/mcp-server/src/server.js の health 応答の `version: '...'`。
SERVER_JS_VERSION_RE = re.compile(r"(version:\s*)'[^']*'")


class VersionFieldNotFound(RuntimeError):
    """版数欄が見つからないファイルがある(要件1: 黙って飛ばさず失敗させる)。"""


def _replace_first_n(text, pattern, make_replacement, n, path):
    """`pattern` に一致する最初の n 件だけを書き換える。n 件に満たなければ失敗する
    (npm lockfile は `"version":` が依存パッケージの数だけ出現するため、対象は
    「先頭から n 件」= ルートパッケージの `version` と `packages[""].version` に限定する)。
    """
    out_parts = []
    last = 0
    count = 0
    for m in pattern.finditer(text):
        if count >= n:
            break
        out_parts.append(text[last:m.start()])
        out_parts.append(make_replacement(m))
        last = m.end()
        count += 1
    if count < n:
        raise VersionFieldNotFound("バージョン欄が見つかりません: %s" % path)
    out_parts.append(text[last:])
    return "".join(out_parts)


def set_version_everywhere(checkout_dir, version, out):
    """#1305 要件1: VERSION_LOCATIONS の全ファイル・全箇所を version へ書き換える。

    1箇所でも版数欄が見つからなければ VersionFieldNotFound を送出する(黙って
    スキップしない)。
    """
    for rel, kind in VERSION_LOCATIONS:
        path = os.path.join(checkout_dir, rel)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        if kind == "npm_pkg":
            new_text = _replace_first_n(
                text, VERSION_FIELD_RE, lambda m: '"version": "%s"' % version, 1, rel
            )
        elif kind == "npm_lock":
            new_text = _replace_first_n(
                text, VERSION_FIELD_RE, lambda m: '"version": "%s"' % version, 2, rel
            )
        elif kind == "gradle":
            new_text = _replace_first_n(
                text,
                GRADLE_VERSION_RE,
                lambda m: "%s'%s'" % (m.group(1), version),
                1,
                rel,
            )
        elif kind == "server_js":
            new_text = _replace_first_n(
                text,
                SERVER_JS_VERSION_RE,
                lambda m: "%s'%s'" % (m.group(1), version),
                1,
                rel,
            )
        else:
            raise AssertionError("未知の種別: %s" % kind)
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_text)
        if out is not None:
            out.write("    版数設定: %s -> %s\n" % (rel, version))


def read_version(text, kind):
    """`set_version_everywhere` が書き込んだ版数を読み戻す(テスト・診断用)。"""
    if kind == "npm_pkg":
        m = VERSION_FIELD_RE.search(text)
    elif kind == "npm_lock":
        matches = list(VERSION_FIELD_RE.finditer(text))
        m = matches[1] if len(matches) > 1 else None
    elif kind == "gradle":
        m = GRADLE_VERSION_RE.search(text)
    elif kind == "server_js":
        m = SERVER_JS_VERSION_RE.search(text)
    else:
        raise AssertionError("未知の種別: %s" % kind)
    if not m:
        return None
    # 値は最後のクォート区間(キー側の `"version"` を値と取り違えないため、
    # コロン/イコールより後ろだけを見る)。
    after_separator = re.split(r"[:=]", m.group(0), maxsplit=1)[1]
    q = re.search(r"['\"]([^'\"]*)['\"]", after_separator)
    return q.group(1) if q else None


def compute_dev_version(version):
    """#1305 要件4: X から次の開発版数 `X.Y.(Z+1)-DEVELOP` を決める(DEVELOP は大文字)。"""
    m = SEMVER_RE.match(version)
    if not m:
        raise ValueError("semver ではありません: %s" % version)
    major, minor, patch = (int(g) for g in m.groups())
    return "%d.%d.%d-DEVELOP" % (major, minor, patch + 1)


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


def list_tags(cwd):
    r = git(["tag", "--list"], cwd=cwd)
    return [l for l in r.stdout.splitlines() if l.strip()]


def list_remote_tags(cwd):
    """origin の実際のタグ名を読み取り専用で確認する(要件2: push直前の再確認に、
    ローカル ref を書き換える `git fetch` ではなく `ls-remote` を使う)。"""
    r = git(["ls-remote", "--tags", "origin"], cwd=cwd)
    names = set()
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        ref = parts[1]
        if ref.endswith("^{}"):
            continue
        if ref.startswith("refs/tags/"):
            names.add(ref[len("refs/tags/"):])
    return names


def remote_ref_sha(cwd, ref):
    """origin 上の ref の先頭を読み取り専用で確認する(要件5/6: developが検証中に
    進んでいないかの再確認)。"""
    r = git(["ls-remote", "origin", ref], cwd=cwd)
    lines = [l for l in r.stdout.splitlines() if l.strip()]
    if not lines:
        return None
    return lines[0].split()[0]


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

    このファイルはシェルで `set -a; . ~/.config/lets-blog-e2e.env` として
    `source` することも前提の書式(`docs/ACCEPTANCE_TESTING.md` §10)なので、
    `export ` の前置きと、値を囲む `"..."` / `'...'` の一重の引用符を、
    `source` した場合と同じ値になるよう読み取り時に外す(#1356)。

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
            k = k.strip()
            if k.startswith("export ") or k.startswith("export\t"):
                k = k[len("export"):].strip()
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
                v = v[1:-1]
            result[k] = v
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
    {
        # issue #1421: `apps/web/e2e/` の単体テスト。`apps/web/jest.config.ts` は
        # 対象除外オプションで `e2e/` を既定の実行から外している(既定の testMatch が
        # rootDir 全体に及び、Playwright用のファイルまで拾ってしまうため)。
        # **除外自体は妥当**で、問題は「除外したまま、別経路でも実行していなかった」点だった。
        # 12スイート58件が一度も自動実行されておらず、その中には #1391 / #1403 の
        # ログイン撮り直し、#1360 / #1381 / #1385 のハイドレーション競合、#1295 の
        # アカウント単位ロックといった、**受け入れテストの土台**が含まれていた。
        #
        # ここでは `--testMatch` と対象除外オプションを上書きして e2e だけを対象にする。
        # 上の `web-test` の argv は触らないので、既定の `npm run test` の対象は変わらない。
        #
        # 件数は `web-test` と同じく jest の `--json` 出力から読む。終了コードだけの判定では
        # **skipped を検出できず**、要件4のゼロ許容(failed/skipped/did not run/flaky が全て0)の
        # 網に穴が開くため。出力ファイル名は `web-test` と分ける(同じにすると集計が上書きされる)。
        "name": "web-test-e2e-unit",
        "argv": [
            "npx",
            "jest",
            "--config",
            "jest.config.ts",
            "--testPathIgnorePatterns=/node_modules/|/\\.next/",
            "--testMatch=**/e2e/**/*.test.ts",
            "--json",
            "--outputFile=%CHECKOUT%/apps/web/jest-e2e-unit-report.json",
        ],
        "cwd": "apps/web",
        "counts_parser": "jest",
        "counts_source": "apps/web/jest-e2e-unit-report.json",
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
            # #1318: このホストにはGPU(実機lbs-comfyui)が無く、`@requires-gpu`のシナリオ
            # (comfyui-checkpoints.featureの導入・削除)は必ず落ちる。
            # `apps/web/playwright.config.ts`はこの環境変数が1のときだけ、生成時タグ式
            # (at-main/at-destructiveの`tags`)からこのタグを除外する。`--grep-invert`は
            # 依存プロジェクトを絞り込まないため使えない(Readiness評価で実測)。
            # test:at:clean/test:atそのものは変えない(手動実行では引き続き全シナリオが対象)。
            "AT_EXCLUDE_REQUIRES_GPU": "1",
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
        # issue #1418: provision-agent(managed WordPress への全操作の実行主体)の
        # PHPテスト。手順表にもpre-commitにも無く、CIも無いため、これまで
        # **どこからも自動実行されていなかった**。#1417 で足した判定のテストも同じ状態だった。
        #
        # ## 実行の仕方に3つの制約がある
        #
        # 1. ホストに `php` が無い。`lets_blog_server-wordpress` イメージの中にある。
        #    イメージ名が固定なのは、この手順より前の `web-test-at-clean` が
        #    `ACCEPTANCE_RESET=1` のゼロ構築(`docker compose up -d --build`)で
        #    共有プロジェクト名 `lets_blog_server` の下にチェックアウトのソースから
        #    ビルドするためである。**この手順を web-test-at-clean より前へ動かすと、
        #    イメージ未ビルドで偽陽性になる。**
        #
        # 2. マウントは `infra/wordpress` **全体**でなければならない。テストは
        #    `__DIR__ . '/../../start.sh'` を読む。`provision-agent` だけを
        #    マウントすると `start.sh` が見えず AC4 のテストが誤って落ちる。
        #    イメージ内の `start.sh` は `/usr/local/bin/start.sh` にあって
        #    `/var/www/start.sh` には無いので、イメージ内のコピーをそのまま実行する
        #    形も採れない(だからチェックアウトをマウントする)。
        #
        # 3. `timeout` で包む。ハーネス自身のdocstringが
        #    「デッドロック回帰時にこのテスト自身が無限に固まらないよう、必ずシェルの
        #    timeoutで包んで実行すること」と求めている。
        #
        # 件数は終了コードで判定する(`counts_parser` なし)。このハーネスは
        # PASS/FAIL を自前で出力する簡易実装で、JUnit XML も jest JSON も出さないが、
        # 失敗があれば非0で終了する。`backend-check-test-db` 等と同じ扱い。
        "name": "provision-agent-php-test",
        "argv": [
            "timeout",
            "300",
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            "php",
            "-v",
            "%CHECKOUT%/infra/wordpress:/tmp/wp:ro",
            "lets_blog_server-wordpress:latest",
            "/tmp/wp/provision-agent/__tests__/test-process-runner.php",
        ],
        "cwd": "",
        "touches_stack": True,
    },
    {
        # letsblog プラグインと provision-agent への導入処理のテスト(issue #1556)。
        # 実行方法の理由は上の provision-agent-php-test と同じ。
        "name": "letsblog-plugin-php-test",
        "argv": [
            "timeout",
            "300",
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            "php",
            "-v",
            "%CHECKOUT%/infra/wordpress:/tmp/wp:ro",
            "lets_blog_server-wordpress:latest",
            "/tmp/wp/provision-agent/__tests__/test-letsblog-plugin.php",
        ],
        "cwd": "",
        "touches_stack": True,
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
        #
        # issue #1415: `-Dlbs.dockerAvailable=true` を渡す。
        # `HikariDeadConnectionRecoveryIntegrationTest`(#1095: mysqlコンテナを再作成すると
        # 確立済みのHikariCP接続がサイレントに応答不能になる障害の再現・検証)は
        # `@EnabledIfSystemProperty(named = "lbs.dockerAvailable", matches = "true")` を持ち、
        # `services/publishing/build.gradle` が既定 `false` を転送する。これを渡さないと
        # 当該2件が常にskipされ、上のゼロ許容(要件4)に自分自身が掛かって失敗する。
        #
        # リリース検証は必ずdockerのある環境で走る(直前の受け入れテストが
        # docker composeのスタックを丸ごと使う)ため、ここで有効にしてよい。
        # テストが使う使い捨てMySQLコンテナ(`DisposableMysqlContainer`)は`lbs-mysql`とは
        # 無関係で、共有スタックを壊さない。実測では2件が86秒で通る。
        #
        # `build.gradle` 側の既定 `false` は据え置く。dockerの無い開発環境で
        # `./gradlew test` が落ちるのは開発体験の後退であり、そちらの設計は意図的である。
        "name": "backend-gradle-test-lint",
        "argv": ["./gradlew", "test", "lint", "-Dlbs.dockerAvailable=true"],
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


# --------------------------------------------------------------------- @requires-gpu 除外一覧 (#1318)
#: `.feature` の相対パス(隔離チェックアウトのルートから)。手書きしない — 実ファイルを
#: 機械的に走査する(Issue 要件3)。
REQUIRES_GPU_FEATURES_GLOB = os.path.join("apps", "web", "e2e", "features", "**", "*.feature")
REQUIRES_GPU_TAG = "@requires-gpu"


def _parse_feature_scenarios(text):
    """簡易Gherkinパーサ。(フィーチャ単位のタグ, [(シナリオ名, シナリオ単位のタグ), ...])を返す。

    このスクリプトが対象にするのは`@requires-gpu`の有無だけなので、ステップ本文
    (前提/もし/ならば/かつ)や背景は無視する。タグ行はシナリオ/フィーチャの見出し行の
    直前に連続して書かれる、このリポジトリの`.feature`の書式(#926)を前提にする。
    """
    feature_tags = []
    scenarios = []
    pending_tags = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            pending_tags.extend(line.split())
            continue
        if line.startswith("機能:") or line.startswith("Feature:"):
            feature_tags = pending_tags
            pending_tags = []
            continue
        for prefix in ("シナリオアウトライン:", "Scenario Outline:", "シナリオ:", "Scenario:"):
            if line.startswith(prefix):
                name = line[len(prefix):].strip()
                scenarios.append((name, pending_tags))
                pending_tags = []
                break
        else:
            # 前提/もし/ならば/かつ・背景・説明文など。直前に置き場を失ったタグは捨てる。
            pending_tags = []
            continue
        continue
    return feature_tags, scenarios


def find_requires_gpu_scenarios(checkout_dir):
    """`@requires-gpu`が付いたシナリオを(フィーチャの相対パス, シナリオ名)の一覧で返す(#1318)。

    フィーチャ単位のタグはそのフィーチャの全シナリオに継承させる。手書きの一覧を
    保守する代わりに、隔離チェックアウトの`.feature`を実際に走査して機械的に作る
    (Issue 要件3)。決定的な順序にするため、パス→ファイル内の出現順でソートする。
    """
    pattern = os.path.join(checkout_dir, REQUIRES_GPU_FEATURES_GLOB)
    results = []
    for path in sorted(glob.glob(pattern, recursive=True)):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        feature_tags, scenarios = _parse_feature_scenarios(text)
        rel = os.path.relpath(path, checkout_dir).replace(os.sep, "/")
        feature_has_tag = REQUIRES_GPU_TAG in feature_tags
        for name, tags in scenarios:
            if feature_has_tag or REQUIRES_GPU_TAG in tags:
                results.append((rel, name))
    return results


def format_requires_gpu_exclusion_lines(excluded_scenarios):
    """実行ログとタグ注釈の両方で使う、除外一覧の表示形を1か所にまとめる(#1318 要件3)。

    0件のときも「該当なし」と明示する(何も出さないと、除外の仕組みが動いていない
    のか、たまたま対象が無かったのかを後から区別できない)。
    """
    if not excluded_scenarios:
        return ["  (該当なし)"]
    return ["  - %s :: %s" % (rel, name) for rel, name in excluded_scenarios]


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


def build_tag_message(
    p_sha, r_sha, main_before, merge_commit, tree_id, start_time, end_time, step_results,
    excluded_scenarios=(),
):
    """#1305 要件7: P(develop 先頭)と R(版数コミット)の両方をタグメッセージに記録する。

    #1318 要件3: リリース検証から除外した`@requires-gpu`シナリオの一覧も記録する。
    """
    lines = [
        "release-verify-tag.py による自動リリース",
        "P (develop先頭): %s" % p_sha,
        "R (版数コミット): %s" % r_sha,
        "parents: %s %s" % (main_before, r_sha),
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
    lines.append("")
    lines.append("@requires-gpu 除外(#1318):")
    lines.extend(format_requires_gpu_exclusion_lines(excluded_scenarios))
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

        # #1305 要件6: P(develop の先頭)が現在の origin/develop の先頭でなければ、
        # どの手順も始めずに拒否する。古いコミットを指定したリリースはできない。
        develop_tip = rev_parse(checkout_dir, "origin/develop")
        p_sha = rev_parse(checkout_dir, args.commit) if args.commit else develop_tip
        out.write("==> P(develop先頭): %s\n" % p_sha)
        if p_sha != develop_tip:
            fail(out, MSG_P_NOT_TIP, "指定=%s develop先頭=%s" % (p_sha, develop_tip))
            return 1

        main_before = rev_parse(checkout_dir, "origin/main")

        out.write("==> cycle.lock を取得: %s\n" % lock_path)
        lock_fh = acquire_lock(lock_path, lock_timeout, lock_poll_interval, out)
        if lock_fh is None:
            fail(out, MSG_LOCK_TIMEOUT, "lock=%s timeout=%ss" % (lock_path, lock_timeout))
            return 1
        out.write("==> ロックを取得しました\n")

        # #1305 要件2: 版数 X の計算をテスト手順より前に移す。同名タグの確認は
        # ここ(計算時)と、push 直前の両方で行う。
        out.write("==> 版数 X を計算(要件2: テスト手順より前)\n")
        existing_tags = list_remote_tags(checkout_dir)
        version = compute_next_version(existing_tags, args.bump)
        if version in existing_tags:
            fail(out, MSG_VERSION_EXISTS, "version=%s" % version)
            return 1
        out.write("==> 版数 X=%s\n" % version)

        # #1305 要件3: リリースコミット R を P の子として作る(版数ファイルの変更だけ)。
        # Implementation Notes: git フックを束縛する前に作ること
        # (apps/mcp-server/src/server.js はプロダクション扱いなので、束縛したまま
        #  だと隔離チェックアウトの pre-commit フックがテストファースト規則で拒否する)。
        out.write("==> リリースコミット R を作成(P の子、版数ファイルのみ)\n")
        git(["checkout", "--detach", p_sha], cwd=checkout_dir)
        try:
            set_version_everywhere(checkout_dir, version, out)
        except VersionFieldNotFound as e:
            fail(out, str(e))
            return 1
        git(["add"] + [rel for rel, _ in VERSION_LOCATIONS], cwd=checkout_dir)
        git(["commit", "-m", "release: %s" % version], cwd=checkout_dir)
        r_sha = rev_parse(checkout_dir, "HEAD")
        out.write("==> R: %s\n" % r_sha)

        # #1305 要件3: 事前の木チェック・全手順・本番の木チェックは、すべて R に対して行う。
        out.write("==> 事前確認: main へのマージと木の同一性(R に対して)\n")
        _merge_commit, tree_match = do_merge_and_check_tree(checkout_dir, main_before, r_sha)
        if not tree_match:
            fail(out, MSG_TREE_MISMATCH, "main=%s R=%s" % (main_before, r_sha))
            return 1

        git(["checkout", "--detach", r_sha], cwd=checkout_dir)

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

        # #1318 要件3: web-test-at-clean が AT_EXCLUDE_REQUIRES_GPU=1 で除外する
        # `@requires-gpu` シナリオの一覧を、Rの木から機械的に作って記録する
        # (手書きしない)。手順を実行する前に確定させ、タグ注釈にもそのまま使う。
        excluded_scenarios = find_requires_gpu_scenarios(checkout_dir)
        out.write("==> @requires-gpu のシナリオをリリース検証の対象から除外します(#1318)\n")
        for line in format_requires_gpu_exclusion_lines(excluded_scenarios):
            out.write(line + "\n")

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

        out.write("==> 本番: origin/main を再取得してマージ+木の同一性を再確認(R に対して)\n")
        git(["fetch", "origin", "main"], cwd=checkout_dir)
        main_now = rev_parse(checkout_dir, "origin/main")
        merge_commit, tree_match2 = do_merge_and_check_tree(checkout_dir, main_now, r_sha)
        if not tree_match2:
            fail(out, MSG_TREE_MISMATCH, "main=%s R=%s" % (main_now, r_sha))
            return 1

        # #1305 要件2: 同名タグの確認を push 直前にもう一度行う(競合対策)。
        existing_tags2 = list_remote_tags(checkout_dir)
        if version in existing_tags2:
            fail(out, MSG_VERSION_EXISTS, "version=%s" % version)
            return 1

        # #1305 要件5/6: 検証中に origin/develop が P から進んでいないか再確認する
        # (進んでいたら再試行はせず、全体を失敗として扱う)。
        develop_now = remote_ref_sha(checkout_dir, "refs/heads/develop")
        if develop_now != p_sha:
            fail(out, MSG_DEVELOP_ADVANCED, "develop_now=%s P=%s" % (develop_now, p_sha))
            return 1

        # #1305 要件4: 全手順が成功したら、次の開発版数 V を R の子として作る。
        out.write("==> 次の開発版数コミット V を作成(R の子)\n")
        dev_version = compute_dev_version(version)
        git(["checkout", "--detach", r_sha], cwd=checkout_dir)
        try:
            set_version_everywhere(checkout_dir, dev_version, out)
        except VersionFieldNotFound as e:
            fail(out, str(e))
            return 1
        git(["add"] + [rel for rel, _ in VERSION_LOCATIONS], cwd=checkout_dir)
        git(["commit", "-m", "chore: 次の開発版数 %s" % dev_version], cwd=checkout_dir)
        v_sha = rev_parse(checkout_dir, "HEAD")
        out.write("==> V: %s (%s)\n" % (v_sha, dev_version))

        end_time = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        tree_id = tree_of(checkout_dir, merge_commit)
        message = build_tag_message(
            p_sha, r_sha, main_now, merge_commit, tree_id, start_time, end_time, step_results,
            excluded_scenarios,
        )
        git(["tag", "-a", version, "-m", message, merge_commit], cwd=checkout_dir)

        # #1305 要件5: M(main, タグ)・V(develop)・タグを1回の atomic push で送る。
        # --force は使わない(develop が P から進んでいれば non-fast-forward で拒否される。
        # 上の明示チェックと二重の防御)。
        out.write(
            "==> push: %s(main) と %s(develop) と %s を1回のatomicで送る\n"
            % (merge_commit[:12], v_sha[:12], version)
        )
        push = git(
            [
                "push",
                "--atomic",
                "origin",
                "%s:refs/heads/main" % merge_commit,
                "%s:refs/heads/develop" % v_sha,
                "refs/tags/%s" % version,
            ],
            cwd=checkout_dir,
            check=False,
        )
        if push.returncode != 0:
            fail(out, MSG_PUSH_REJECTED, push.stdout + push.stderr)
            return 1

        out.write(
            "==> 成功: main=%s develop=%s tag=%s\n" % (merge_commit, v_sha, version)
        )
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
