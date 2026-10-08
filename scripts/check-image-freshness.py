#!/usr/bin/env python3
"""稼働中コンテナのイメージが、ワークツリーのソースより古くないかを確認する(#1653)。

## 背景

受け入れテストは共有 docker compose スタックに対して走る。実行中のコンテナのイメージが
ワークツリーのコードより古いと、実装済みの機能が失敗に見え、切り分けに時間がかかる(#1652)。
`scripts/check-worktree-match.py`(#1202)は作業ツリーの一致を見るが、イメージの鮮度は見ない。

## 「古い」の定義(利用者が 2026-10-06 に決定)

サービスの稼働中コンテナのイメージ `Created` が、`HEAD` から到達できるコミットのうち、
そのサービスのソースパスに触れた最新コミットのコミット時刻(`git log -1 --format=%ct -- <パス>`)
より前であること。古いサービスが1つでもあれば非0で中断し、再ビルドのコマンドを表示する
(警告で続行はしない)。`AT_STALE_IMAGE_CHECK_BYPASS=1` で、古いサービス名と迂回を標準出力に
記録したうえで続行できる。

## 使い方(`apps/web/e2e/global-setup.ts` から、ゼロ構築の後に呼ばれる)

    python3 scripts/check-image-freshness.py

## 内容スタンプ(#1686、利用者が 2026-10-08 に決定。上の「古い」の定義を置き換える)

ビルドキャッシュが全面的に効くと、ソースの中身が同じでもイメージは作り直されず `Created` が
古いままなので、作成時刻の比較は「作り直したのに古い」を出し続ける。そこで各イメージに、
ソースの**内容**から決まる値(スタンプ)を焼き込み、`HEAD` の同じパスの内容と比べる。

- 値: `<blob sha1>  <ビルドコンテキスト相対パス>\n` を、パスのバイト順に並べた全体の sha1。
  blob sha1 は git のオブジェクト id と同じ(`blob <size>\0<内容>` の sha1)なので、
  イメージ側(各 Dockerfile の `stamp` ステージ)は `sha1sum` だけで、`HEAD` 側は
  `git ls-tree -r HEAD` だけで同じ値になる。
- SHA ではなくハッシュを選んだ理由: コミット SHA はソースに触れないコミット(docs など)でも
  変わり、キャッシュが効いてもスタンプが変わる(=誤検知が戻る)。内容のハッシュは内容が同じなら
  同じで、ビルドキャッシュの層も壊さない(`stamp` ステージはビルド本体と独立で、最終イメージの
  末尾に1つ層を足すだけ)。
- ラベルでなくイメージ内のファイル(`STAMP_PATH`)にした理由: ラベルの値は compose の
  `${VAR}` 補間か `ARG` でしか渡せず、ハッシュは呼び出し側が計算して環境変数で渡すことになる
  (Requirement 3: 呼び出し側に何も要求しない、に反する)。Dockerfile の `RUN` で計算した値は
  ファイルにしかできない。稼働中コンテナから `docker exec cat` で読む。
- スタンプを持たないイメージ(#1686 より前に作られたもの)は、従来の作成時刻の比較にフォールバックする。
- 未コミットの変更は見ない(#1653 と同じ)。ただしイメージ側はビルドコンテキストの実ファイルを
  数えるので、ソースパス配下の追跡されていないファイルは「古い」の原因になりうる。

単体テスト: `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`
(`scripts/test_check_image_freshness.py` のモジュール docstring に、Gherkin ではなく
ここで検証する理由を書いてある)。
"""

import datetime
import hashlib
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

BYPASS_ENV = "AT_STALE_IMAGE_CHECK_BYPASS"
COMPOSE_PROJECT_NAME = "lets_blog_server"
# 各 Dockerfile の `stamp` ステージが書き込み、最終イメージへ COPY するファイル(#1686)。
STAMP_PATH = "/etc/lbs-source-stamp"

# ---------------------------------------------------------------- サービス→ソースパス表
#
# 唯一の定義。「ソースパス」は、そのサービスの Dockerfile がイメージへ取り込む、リポジトリ内の
# パス(`COPY` 行から導出)。bind mount でコンテナへ渡しているパスは、コンテナが実行時に
# そのまま読むのでイメージの鮮度と無関係であり、含めない。
# `docker-compose.yml` に `build:` を持つサービスが増えたら、単体テスト
# (`SourcePathTable.test_table_covers_every_compose_build_service`)が失敗する。

# services/<svc>/Dockerfile の COPY(Java 7サービス共通の導出元。<svc> は自サービス名):
#   L6  COPY gradlew settings.gradle build.gradle ./   -> gradlew, settings.gradle, build.gradle
#   L7  COPY config ./config                           -> config
#   L8  COPY gradle ./gradle                           -> gradle
#   L11 COPY packages ./packages                       -> packages
#   L12 COPY services/<svc> ./services/<svc>           -> services/<svc>
#   L13-21 COPY services/<other>/build.gradle ...      -> 自サービス以外の全サービスの build.gradle
# 他サービスの build.gradle だけを変えたコミットでも、イメージに焼き込まれている以上
# 「古い」と判定されるのは、定義どおりの挙動である。
_JAVA_SERVICES = (
    "media",
    "ai",
    "content",
    "analytics",
    "platform",
    "project",
    "publishing",
    "log-writer",
    "gateway",
    "identity",
)
_JAVA_COMMON = ["gradlew", "settings.gradle", "build.gradle", "config", "gradle", "packages"]


def _java_paths(svc):
    others = ["services/%s/build.gradle" % o for o in _JAVA_SERVICES if o != svc]
    paths = _JAVA_COMMON + ["services/%s" % svc] + others
    if svc == "publishing":
        # services/publishing/Dockerfile L14:
        #   COPY infra/wordpress/letsblog-plugin ./infra/wordpress/letsblog-plugin
        paths.append("infra/wordpress/letsblog-plugin")
    return paths


SERVICE_SOURCE_PATHS = {svc: _java_paths(svc) for svc in _JAVA_SERVICES}

# web: apps/web/Dockerfile は `COPY package.json package-lock.json ./`(L8)、`COPY . .`(L11)、
# `COPY docker-entrypoint.sh /docker-entrypoint.sh`(L13)。ただし `./apps/web:/app` を
# bind mount しており(docker-compose.yml の web)、`COPY . .` の中身(apps/web/src/** など)は
# 実行時に bind mount が覆う。よって鮮度に効くのは、イメージに焼き込まれる依存定義と
# エントリポイントに限る。
SERVICE_SOURCE_PATHS["web"] = [
    "apps/web/package.json",
    "apps/web/package-lock.json",
    "apps/web/Dockerfile",
    "apps/web/docker-entrypoint.sh",
]

# wordpress: build context は infra/wordpress。infra/wordpress/Dockerfile の
# `COPY uploads.ini`(L24)、`COPY provision-agent`(L26)、`COPY letsblog-plugin`(L27)、
# `COPY start.sh`(L28)。bind mount は named volume のみでソースを覆わない。
SERVICE_SOURCE_PATHS["wordpress"] = ["infra/wordpress"]

# ビルドコンテキストのリポジトリ内の位置(スタンプのパスはコンテキスト相対)。無ければリポジトリルート。
_CONTEXT_DIR = {"web": "apps/web", "wordpress": "infra/wordpress"}


# ---------------------------------------------------------------- 外部コマンドの境界
#
# 単体テストはこの2関数だけを差し替える。


def _docker(args):
    """`docker <args>` を実行し `(returncode, stdout, stderr)` を返す。"""
    try:
        r = subprocess.run(
            ["docker"] + list(args), capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


def _git(args, cwd):
    """`git <args>` を `cwd` で実行し `(returncode, stdout, stderr)` を返す。"""
    try:
        r = subprocess.run(
            ["git"] + list(args), cwd=cwd, capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


class FreshnessError(Exception):
    """判定を安全に下せないことを表す(git の失敗、解釈できない時刻)。"""


class DockerUnavailable(Exception):
    """docker に問い合わせられない(デーモンが無い等)。"""


# ---------------------------------------------------------------- 判定ロジック


def compose_build_services(compose_path):
    """`docker-compose.yml` の `services:` 直下で `build:` を持つサービス名を順に返す。"""
    services = []
    in_services = False
    current = None
    with open(compose_path, encoding="utf-8") as f:
        for line in f:
            if re.match(r"^services:\s*$", line):
                in_services = True
                continue
            if in_services and re.match(r"^\S", line) and not line.startswith("#"):
                break
            if not in_services:
                continue
            m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(#.*)?$", line)
            if m:
                current = m.group(1)
                continue
            if current and re.match(r"^    build:", line):
                services.append(current)
                current = None
    return services


def parse_created(value):
    """docker の RFC3339(ナノ秒、`Z` 可)を epoch 秒にする。解釈できなければ None。"""
    v = value.strip()
    m = re.match(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$", v)
    if not m:
        return None
    base, frac, tz = m.groups()
    frac = (frac or "")[:7]  # マイクロ秒まで
    tz = "+00:00" if tz == "Z" else tz
    try:
        dt = datetime.datetime.fromisoformat(base + frac + tz)
    except ValueError:
        return None
    return int(dt.timestamp())


def image_created(service):
    """稼働中コンテナのイメージ作成時刻(epoch)。稼働していなければ None。"""
    code, out, err = _docker(
        [
            "ps",
            "-q",
            "--filter",
            "label=com.docker.compose.project=%s" % COMPOSE_PROJECT_NAME,
            "--filter",
            "label=com.docker.compose.service=%s" % service,
        ]
    )
    if code != 0:
        raise DockerUnavailable(err.strip() or "docker ps に失敗しました")
    cid = out.split()[0] if out.split() else ""
    if not cid:
        return None
    code, out, err = _docker(["inspect", "--format", "{{.Image}}", cid])
    if code != 0 or not out.strip():
        raise FreshnessError("docker inspect %s に失敗しました: %s" % (cid, err.strip()))
    code, out, err = _docker(
        ["image", "inspect", "--format", "{{.Created}}", out.strip()]
    )
    if code != 0 and "No such image" in err:
        # コンテナを作り直さないままタグが付け替わり、実行中のイメージ自体が消えている。
        # イメージ作成時刻は取れないので、コンテナの作成時刻(イメージ作成時刻以降)で代える。
        # 実際より新しい側にずれるだけなので、これによって誤って「古い」と判定することは無い。
        code, out, err = _docker(["inspect", "--format", "{{.Created}}", cid])
    created = parse_created(out) if code == 0 else None
    if created is None:
        raise FreshnessError(
            "サービス %s のイメージ作成時刻を解釈できません: %s" % (service, (out or err).strip())
        )
    return created


def _running_container(service):
    """サービスの稼働中コンテナ id。稼働していなければ None。"""
    code, out, err = _docker(
        [
            "ps",
            "-q",
            "--filter",
            "label=com.docker.compose.project=%s" % COMPOSE_PROJECT_NAME,
            "--filter",
            "label=com.docker.compose.service=%s" % service,
        ]
    )
    if code != 0:
        raise DockerUnavailable(err.strip() or "docker ps に失敗しました")
    return out.split()[0] if out.split() else None


def image_stamp(service):
    """稼働中コンテナのイメージに焼き込まれた内容スタンプ。無い(古いイメージ等)なら None。"""
    cid = _running_container(service)
    if not cid:
        return None
    code, out, _err = _docker(["exec", cid, "cat", STAMP_PATH])
    stamp = out.strip() if code == 0 else ""
    return stamp if re.fullmatch(r"[0-9a-f]{40}", stamp) else None


def head_source_stamp(service, cwd):
    """`HEAD` のソースパスの内容スタンプ(定義はモジュール docstring)。追跡ファイルが無ければ None。"""
    code, out, err = _git(
        ["ls-tree", "-r", "-z", "HEAD", "--"] + list(SERVICE_SOURCE_PATHS[service]), cwd
    )
    if code != 0:
        raise FreshnessError("git ls-tree に失敗しました: %s" % err.strip())
    prefix = _CONTEXT_DIR.get(service, "")
    prefix = prefix + "/" if prefix else ""
    entries = []
    for rec in out.split("\0"):
        if not rec:
            continue
        meta, _, path = rec.partition("\t")
        mode, kind, sha = meta.split()
        if kind != "blob" or mode == "120000":
            continue  # find -type f が数えるのは通常ファイルだけ
        if prefix and path.startswith(prefix):
            path = path[len(prefix):]
        entries.append((path.encode(), sha))
    if not entries:
        return None
    entries.sort()
    text = b"".join(b"%s  %s\n" % (sha.encode(), path) for path, sha in entries)
    return hashlib.sha1(text).hexdigest()


def latest_commit(paths, cwd):
    """`paths` に触れた最新コミットの (コミット時刻, 短縮ハッシュ)。無ければ None。"""
    code, out, err = _git(["log", "-1", "--format=%ct %h", "--"] + list(paths), cwd)
    if code != 0:
        raise FreshnessError("git log に失敗しました: %s" % err.strip())
    out = out.strip()
    if not out:
        return None
    ts, h = out.split()
    return int(ts), h


def find_stale(cwd=None):
    """古いサービスの一覧(対応表の順)。各要素は service / image_created / commit_time / commit。"""
    cwd = cwd or REPO_ROOT
    stale = []
    for service, paths in SERVICE_SOURCE_PATHS.items():
        created = image_created(service)
        if created is None:
            continue  # 稼働していない。起動の有無は healthy 待ちの責務。
        stamp = image_stamp(service)
        if stamp is not None:
            head = head_source_stamp(service, cwd)
            if head is not None and head != stamp:
                stale.append({"service": service, "image_stamp": stamp, "head_stamp": head})
            continue  # スタンプを持つイメージは作成時刻を見ない(#1686)
        commit = latest_commit(paths, cwd)
        if commit is None:
            continue
        if created < commit[0]:
            stale.append(
                {
                    "service": service,
                    "image_created": created,
                    "commit_time": commit[0],
                    "commit": commit[1],
                }
            )
    return stale


def _fmt(epoch):
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )


def _describe_one(s):
    if "image_stamp" in s:
        return "  - %s: イメージのソース内容 %s / HEAD のソース内容 %s" % (
            s["service"],
            s["image_stamp"][:12],
            s["head_stamp"][:12],
        )
    return "  - %s: イメージ作成 %s / 最新コミット %s (%s)" % (
        s["service"],
        _fmt(s["image_created"]),
        _fmt(s["commit_time"]),
        s["commit"],
    )


def _describe(stale):
    return "\n".join(_describe_one(s) for s in stale)


def check(cwd=None):
    """(ok, message) を返す。`ok=False` は受け入れテストを開始してはならないことを意味する。"""
    bypass = os.environ.get(BYPASS_ENV) == "1"
    try:
        stale = find_stale(cwd=cwd)
    except DockerUnavailable as e:
        return True, "docker に問い合わせられないため、イメージの鮮度確認を省略します(%s)" % e
    except FreshnessError as e:
        if bypass:
            return True, "%s=1 のためイメージ鮮度チェックを迂回しました(判定不能: %s)" % (
                BYPASS_ENV,
                e,
            )
        return False, (
            "エラー: イメージの鮮度を判定できません(%s)。\n"
            "安全に判定できないため停止します。続行するには %s=1 を指定してください。"
            % (e, BYPASS_ENV)
        )

    if not stale:
        note = (
            "(%s=1 が指定されていますが、古いサービスはありません)" % BYPASS_ENV
            if bypass
            else ""
        )
        return True, "全サービスのイメージはソースの最新コミットより新しいです%s" % note

    if bypass:
        return True, (
            "%s=1 のため、古いイメージのまま続行します(迂回)。古いサービス:\n%s"
            % (BYPASS_ENV, _describe(stale))
        )

    names = " ".join(s["service"] for s in stale)
    return False, (
        "エラー: 稼働中コンテナのイメージが、ワークツリーのソースより古いサービスがあります"
        "(#1653)。\n"
        "古いイメージに対する受け入れテストは、実装済みの機能を失敗に見せます。\n%s\n"
        "再ビルドしてください(スタックを起動したときと同じ -f を付けること):\n"
        "  docker compose up -d --build %s\n"
        "意図して古いスタックのまま実行するには %s=1 を指定してください"
        "(古いサービス名が標準出力に記録されます)。"
        % (_describe(stale), names, BYPASS_ENV)
    )


def main(argv):
    if len(argv) != 1:
        print("使い方: check-image-freshness.py", file=sys.stderr)
        return 2
    ok, message = check()
    if ok:
        print(message)
        return 0
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
