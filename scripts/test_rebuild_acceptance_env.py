#!/usr/bin/env python3
"""受け入れテストを「全撤去 → ゼロから構築 → テスト」の順で回すことの検証(#965)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、受入基準を原則として
`apps/web/e2e/features/**` の受け入れシナリオで表現することを求め、
「Web UI から到達できない基準は、その旨を明示してサービス/スクリプトレベルのテストで
表現する」ことを明示的な例外として認めている。

本Issueの受入基準は、**受け入れテストを走らせる土台そのもの**を対象にしている。

  - `docker compose down -v` が Docker ボリュームを破棄したか
  - `docker volume inspect --format '{{.CreatedAt}}'` が更新されたか
  - `--yes` なしのドライランが何も変更しないか
  - 撤去の直前に置いたプローブが構築後に消えているか

いずれも、判定した瞬間にはスタックが存在しない(あるいは存在してはいけない)。
Playwright はスタックが上がっていることを前提に起動するため、これらを Gherkin で
書いても**走らせる手段が無い**。書かなかったのではなく、構造的に書けない。

唯一 Web UI から到達できる基準 —「ゼロ構築直後に `needsSetup: true`」— は、
既存の `apps/web/e2e/features/auth/setup.feature`(`@stage:setup`、`前提 システムに
ユーザーが1人も居ない`)が `reset` 段の直後に実行されることで既に表現されている。
本ファイルではスクリプト側の自己検証(§4-6)としてのみ扱う。

したがって `scripts/test_shared_host_proxy.py`(#1038)・
`scripts/test_git_hooks_binding.py`(#1039)と同じ**文書化された例外**として、ここで表現する。

## どう検証するか

本物の docker を叩くわけにはいかない(このテストは開発機の実スタックを壊しうる)。
そこで `docker` と `curl` を **PATH で差し替えた偽物**に向け、状態をディレクトリで模す。
偽 docker はボリューム・Keycloak ユーザー・MySQL データベース・WordPress サイトを
ファイルで持ち、`volume rm` でそのボリュームに乗っているデータを消す。
「keycloak_postgres だけ消えなかった」のような**人為的な残存**もこれで作れる。

環境変数(偽 docker 向け):

    FAKE_STATE            状態ディレクトリ
    FAKE_DOCKER_LOG       呼ばれた引数の記録先
    FAKE_STACK_UP         1 なら直前のスタックが起動している
    FAKE_UNDELETABLE      volume rm を失敗させるボリューム名(空白区切り)。
                          手順1で落ちるので、手順4の検証には**到達しない**
    FAKE_NOT_RECREATED    破棄はできるが、構築が作り直さないボリューム名(空白区切り)。
                          手順4-1 が捕まえるべき壊れ方
    FAKE_STICKY_PROBE     破棄をまたいで生き残らせるプローブの種類
                          (keycloak / mysql / wordpress。空白区切り)。
                          ボリュームは破棄され作り直されるが、そのプローブの実体だけが
                          構築後の存在確認に現れる = 手順4-7 が捕まえるべき壊れ方
    FAKE_UNHEALTHY        1 なら compose ps が unhealthy を返す
    FAKE_EXTRA_KC_USER    構築後の letsblog レルムに居座る余計なユーザー名
    FAKE_KCADM_GET_USERS_FAILS  1 なら `kcadm get users` が SIGPIPE ではない本物の
                          エラー(認可切れ等)で失敗する(#1233 のフォローアップ)
    FAKE_DIRTY_SCHEMA     行が残っているスキーマ名
    FAKE_SETUP_STATUS     setup-status の応答ボディ
    FAKE_SETUP_CODE       setup-status の HTTP ステータス
    FAKE_SHARED_HOST      1 なら reverse-proxy がポートを公開していない(#1038 の構成)
    FAKE_OTHER_STACK_HOLDS_PORT  1 なら、このスタックに属さない別コンテナ(infra-proxy等)
                          が80/443番を公開している(#1065: 直前のスタックが完全停止
                          していても検知できるべき構成)
"""

import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

import shadow_checkout

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

SCRIPT = "scripts/rebuild-acceptance-env.sh"
LEGACY_SCRIPT = "scripts/reset-acceptance-env.sh"
WAIT_SCRIPT = "scripts/wait-for-stack-healthy.sh"
GLOBAL_SETUP = "apps/web/e2e/global-setup.ts"
PACKAGE_JSON = "apps/web/package.json"
DOC = "docs/ACCEPTANCE_TESTING.md"

# docker-compose.yml が宣言するボリューム(接頭辞なしの短い名前)。
DESTROYED_VOLUMES = [
    "mysql_data",
    "rabbitmq_data",
    "keycloak_postgres",
    "penpot_postgres",
    "penpot_assets",
    "comfyui_output",
    "wordpress_sites",
    "bulk_upload_files",
    "generated_images",
    "avatar_images",
]
PRESERVED_VOLUMES = ["comfyui_models", "ollama_models"]
VOLUME_PREFIX = "lets_blog_server_"

SERVICE_SCHEMAS = [
    "lbs_identity",
    "lbs_project",
    "lbs_content",
    "lbs_media",
    "lbs_ai",
    "lbs_publishing",
    "lbs_analytics",
    "lbs_platform",
    "lbs_log",
]


def read(rel_path):
    with open(os.path.join(REPO_ROOT, rel_path), encoding="utf-8") as f:
        return f.read()


def exists(rel_path):
    return os.path.exists(os.path.join(REPO_ROOT, rel_path))


FAKE_DOCKER = r'''#!/usr/bin/env python3
"""テスト用の docker スタブ。状態を FAKE_STATE 配下のファイルで持つ。"""
import json
import os
import sys
import time

STATE = os.environ["FAKE_STATE"]
LOG = os.environ["FAKE_DOCKER_LOG"]
args = sys.argv[1:]

VOLUME_PREFIX = "lets_blog_server_"
SCHEMAS = """lbs_identity lbs_project lbs_content lbs_media lbs_ai
lbs_publishing lbs_analytics lbs_platform lbs_log""".split()
# __VOLUME_LISTS__

with open(LOG, "a", encoding="utf-8") as f:
    f.write("\t".join(args) + "\n")


def path(*p):
    return os.path.join(STATE, *p)


def read_lines(name):
    p = path(name)
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip()]


def write_lines(name, lines):
    with open(path(name), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + ("\n" if lines else ""))


def volume_exists(name):
    return os.path.exists(path("volumes", name))


def volume_created_at(name):
    with open(path("volumes", name), encoding="utf-8") as f:
        return f.read().strip()


def create_volume(name, when=None):
    os.makedirs(path("volumes"), exist_ok=True)
    when = when or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    with open(path("volumes", name), "w", encoding="utf-8") as f:
        f.write(when)


# ボリュームに乗っているデータ。volume rm でまとめて消える。
VOLUME_DATA = {
    VOLUME_PREFIX + "keycloak_postgres": "kcusers",
    VOLUME_PREFIX + "mysql_data": "mysqldbs",
    VOLUME_PREFIX + "wordpress_sites": "wpsites",
}

# FAKE_STICKY_PROBE が指すプローブの種類 → それが乗っているデータ。
# ボリューム破棄は成功するのに、この実体だけが残る(古いレプリカ・キャッシュ・
# 削除経路のバグ)状況を作るための仕掛け。
STICKY_DATA = {"keycloak": "kcusers", "mysql": "mysqldbs", "wordpress": "wpsites"}


def sticky_data_files():
    kinds = os.environ.get("FAKE_STICKY_PROBE", "").split()
    return {STICKY_DATA[k] for k in kinds if k in STICKY_DATA}


def is_probe_entry(line):
    return line.startswith("at-wipe-probe-") or line.startswith("at_wipe_probe_")

REQUIRED_SERVICES = """reverse-proxy web gateway keycloak keycloak-postgres mysql rabbitmq
identity media ai content analytics project publishing platform log-writer""".split()

PROBE_CONTAINERS = ["lbs-mysql", "lbs-keycloak", "lbs-wordpress", "lbs-reverse-proxy"]

# #1293: gpu プロファイル(comfyui)の残存コンテナを模す。
#
# FAKE_LEFTOVER_GPU_CONTAINER=1 のとき、初回起動時に一度だけマーカーファイルを置く。
# これは「直前の実行が終わったあとも lbs-comfyui が Created/Exited のまま残っている」
# 状態を表す。`compose down` が `--profile` 付きで呼ばれて初めてこのマーカーを消す
# (= プロファイル込みで撤去されて初めて片付く)。プロファイル無しの `down` では
# 消えないので、そのまま comfyui_output の `volume rm` を失敗させる。
GPU_LEFTOVER_CONTAINER_NAME = "lbs-comfyui"
if os.environ.get("FAKE_LEFTOVER_GPU_CONTAINER") == "1" and not os.path.exists(
    path("gpu_leftover_initialized")
):
    with open(path("gpu_leftover_container"), "w", encoding="utf-8") as _f:
        _f.write(GPU_LEFTOVER_CONTAINER_NAME)
    open(path("gpu_leftover_initialized"), "w", encoding="utf-8").close()


def gpu_leftover_container():
    if os.path.exists(path("gpu_leftover_container")):
        with open(path("gpu_leftover_container"), encoding="utf-8") as f:
            return f.read().strip()
    return ""


def fail(msg, code=1):
    sys.stderr.write(msg + "\n")
    sys.exit(code)


def sql_of(argv):
    if "-e" in argv:
        return argv[argv.index("-e") + 1]
    return ""


def fresh_boot():
    """空ボリュームから起動したときの初期状態を作る。

    init スクリプトが作るスキーマは**足りない分を足す**。まるごと空のときだけ書くと、
    生き残ったプローブ DB が1つあるだけでスキーマの再作成が起きなくなり、
    手順4-3b の別の失敗にすり替わってしまう。
    """
    dbs = read_lines("mysqldbs")
    want = list(SCHEMAS) + [s + "_test" for s in SCHEMAS]
    missing = [d for d in want if d not in dbs]
    if missing or not os.path.exists(path("mysqldbs")):
        write_lines("mysqldbs", dbs + missing)
    if not os.path.exists(path("kcusers")):
        write_lines("kcusers", [])
    if not os.path.exists(path("wpsites")):
        write_lines("wpsites", [])


# ---------------------------------------------------------------- volume
if args[:1] == ["volume"]:
    sub = args[1]
    if sub == "inspect":
        names = [a for a in args[2:] if not a.startswith("--") and "{{" not in a]
        out = []
        for n in names:
            if not volume_exists(n):
                fail("Error: No such volume: %s" % n)
            out.append(volume_created_at(n))
        print("\n".join(out))
        sys.exit(0)
    if sub == "rm":
        undeletable = os.environ.get("FAKE_UNDELETABLE", "").split()
        sticky = sticky_data_files()
        referencing = os.environ.get("FAKE_REFERENCING_CONTAINER", "")
        for n in args[2:]:
            if any(n.endswith(u) for u in undeletable if u):
                if referencing:
                    fail("Error response from daemon: remove %s: volume is in use - [%s]" % (n, referencing))
                fail("Error: volume is in use: %s" % n)
            leftover = gpu_leftover_container()
            if n.endswith("comfyui_output") and leftover:
                fail("Error response from daemon: remove %s: volume is in use - [%s]" % (n, leftover))
            if volume_exists(n):
                os.remove(path("volumes", n))
                data = VOLUME_DATA.get(n)
                if data and os.path.exists(path(data)):
                    if data in sticky:
                        # 破棄は成功したのに、このプローブの実体だけが残る。
                        write_lines(data, [l for l in read_lines(data) if is_probe_entry(l)])
                    else:
                        os.remove(path(data))
        sys.exit(0)
    if sub == "ls":
        names = sorted(os.listdir(path("volumes"))) if os.path.isdir(path("volumes")) else []
        print("\n".join(names))
        sys.exit(0)
    fail("fake docker: unsupported volume subcommand %s" % sub)

# ---------------------------------------------------------------- inspect
if args[:1] == ["inspect"]:
    positional = []
    fmt = ""
    i = 1
    while i < len(args):
        if args[i] in ("-f", "--format"):
            fmt = args[i + 1]
            i += 2
            continue
        positional.append(args[i])
        i += 1
    name = positional[0]
    if not name.startswith("lbs-"):
        # このスタック以外のコンテナ(共有プロキシの infra-proxy 等)は常に在るものとして扱う。
        print("[]")
        sys.exit(0)
    running = name in read_lines("containers")
    if name == "lbs-reverse-proxy" and "PortBindings" in fmt:
        if not running:
            fail("Error: No such object: %s" % name)
        print("0" if os.environ.get("FAKE_SHARED_HOST") == "1" else "2")
        sys.exit(0)
    if not running:
        fail("Error: No such object: %s" % name)
    if "State.Running" in fmt:
        print("true")
    else:
        print("[]")
    sys.exit(0)

# ---------------------------------------------------------------- exec
if args[:1] == ["exec"]:
    rest = [a for a in args[1:] if a != "-i"]
    while rest and rest[0].startswith("-"):
        rest = rest[2:] if rest[0] == "-e" else rest[1:]
    container = rest[0]
    argv = rest[1:]
    if not container.startswith("lbs-"):
        sys.exit(0)
    if container not in read_lines("containers"):
        fail("Error: No such container: %s" % container)

    if container == "lbs-keycloak":
        joined = " ".join(argv)
        if "config credentials" in joined:
            sys.exit(0)
        if "get realms/letsblog" in joined:
            if not volume_exists(VOLUME_PREFIX + "keycloak_postgres"):
                fail("realm not found")
            print(json.dumps({"realm": "letsblog"}))
            sys.exit(0)
        if joined.startswith("/opt/keycloak/bin/kcadm.sh create users") or " create users" in joined:
            username = ""
            for i, a in enumerate(argv):
                if a == "-s" and argv[i + 1].startswith("username="):
                    username = argv[i + 1].split("=", 1)[1]
            users = read_lines("kcusers")
            users.append(username)
            write_lines("kcusers", users)
            sys.stderr.write("Created new user with id 'fake-id'\n")
            sys.exit(0)
        if " get users" in joined:
            if os.environ.get("FAKE_KCADM_GET_USERS_FAILS") == "1":
                # 本物のエラー(認可切れ・接続断など)を模す。SIGPIPE(141)とは違う、
                # kcadm 自身が非0で終わる本物の失敗。
                fail("Unable to send request - Invalid access token")
            users = read_lines("kcusers")
            extra = os.environ.get("FAKE_EXTRA_KC_USER", "")
            if extra and extra not in users:
                users = users + [extra]
            # 破棄後も大量のユーザーが返る状況(#1277)。プローブより後ろに並ぶ。
            # 「想定外ユーザー」検査(--fields id,username,email)には混ぜず、
            # 存在確認(--fields username)の呼び出しにだけ混ぜる。
            pad = 0
            if joined.endswith("--fields username"):
                pad = int(os.environ.get("FAKE_PAD_KC_USERS", "0") or "0")
            users = users + ["zzz-user-%05d-%s@letsblog.local" % (i, "x" * 200) for i in range(pad)]
            # 本物の kcadm はユーザー名の辞書順で返す。プローブが中間に来ることで
            # SIGPIPE を再現する(#1233)ので、その順序をここでも模す。
            users = sorted(users)
            print(
                json.dumps(
                    [{"id": "id-%d" % i, "username": u, "email": u} for i, u in enumerate(users)],
                    indent=2,
                )
            )
            sys.exit(0)
        fail("fake docker: unsupported kcadm call: %s" % joined)

    if container == "lbs-mysql":
        sql = sql_of(argv)
        if not volume_exists(VOLUME_PREFIX + "mysql_data"):
            fail("Can't connect to MySQL server")
        if sql.upper().startswith("CREATE DATABASE"):
            name = sql.split("`")[1]
            dbs = read_lines("mysqldbs")
            if name not in dbs:
                dbs.append(name)
            write_lines("mysqldbs", dbs)
            sys.exit(0)
        if "SHOW DATABASES LIKE" in sql:
            prefix = "at_wipe_probe_"
            print("\n".join(d for d in read_lines("mysqldbs") if d.startswith(prefix)))
            sys.exit(0)
        if sql.upper().startswith("SHOW DATABASES"):
            print("\n".join(read_lines("mysqldbs")))
            sys.exit(0)
        if "information_schema.tables" in sql:
            dirty = os.environ.get("FAKE_DIRTY_SCHEMA", "")
            if dirty and ("'%s'" % dirty) in sql:
                print("some_table")
            sys.exit(0)
        if "SELECT COUNT(*)" in sql.upper():
            print("3")
            sys.exit(0)
        sys.exit(0)

    if container == "lbs-wordpress":
        joined = " ".join(argv)
        if "mkdir" in joined:
            slug = joined.rstrip("'\"").split("/var/www/html/sites/")[1].split()[0].strip("'\"")
            sites = read_lines("wpsites")
            if slug not in sites:
                sites.append(slug)
            write_lines("wpsites", sites)
            sys.exit(0)
        if "ls " in joined:
            sites = read_lines("wpsites")
            if "wc -l" in joined:
                print(len(sites))
            else:
                print("\n".join(sites))
            sys.exit(0)
        if "test -d" in joined:
            slug = joined.split("/var/www/html/sites/")[1].split()[0].strip("'\"")
            sys.exit(0 if slug in read_lines("wpsites") else 1)
        sys.exit(0)

    sys.exit(0)

# ---------------------------------------------------------------- compose
if args[:1] == ["compose"]:
    rest = args[1:]
    verb = None
    for a in rest:
        if a in ("down", "up", "build", "ps"):
            verb = a
            break
    if verb == "down":
        write_lines("containers", [])
        # #1293: `--profile` 付きで撤去されて初めて、gpu プロファイルの残存
        # コンテナ(lbs-comfyui)も片付く。プロファイル無しの撤去では残ったままにする。
        if "--profile" in rest and os.path.exists(path("gpu_leftover_container")):
            os.remove(path("gpu_leftover_container"))
        sys.exit(0)
    if verb in ("up", "build"):
        if verb == "up":
            # up のあとに並ぶ位置引数がサービス指定。無ければ全サービスが対象。
            i = rest.index("up")
            targets = [a for a in rest[i + 1 :] if not a.startswith("-")]
            not_recreated = os.environ.get("FAKE_NOT_RECREATED", "").split()
            for v in DESTROYED + PRESERVED:
                if v in not_recreated:
                    continue
                if not volume_exists(VOLUME_PREFIX + v):
                    create_volume(VOLUME_PREFIX + v)
            fresh_boot()
            # GPU が無いホストでは comfyui の起動に失敗する。compose はそこで中断するため、
            # 依存関係の下流(web / gateway / keycloak ほか)は created のまま残る。
            if not targets or "comfyui" in targets:
                sys.stderr.write(
                    'Error response from daemon: could not select device driver "nvidia" '
                    "with capabilities: [[gpu]]\n"
                )
                started = read_lines("containers")
                for s in ("mysql", "rabbitmq", "keycloak-postgres", "reverse-proxy"):
                    started.append("lbs-%s" % s)
                write_lines("containers", sorted(set(started)))
                sys.exit(1)
            containers = ["lbs-%s" % s for s in REQUIRED_SERVICES] + PROBE_CONTAINERS
            write_lines("containers", sorted(set(containers)))
        sys.exit(0)
    if "config" in rest and "--services" in rest:
        print("\n".join(sorted(set(REQUIRED_SERVICES + ["comfyui", "wordpress", "phpmyadmin"]))))
        sys.exit(0)
    if verb == "ps":
        running = read_lines("containers")
        out = []
        for s in REQUIRED_SERVICES:
            if "lbs-%s" % s not in running:
                continue
            health = "healthy"
            if os.environ.get("FAKE_UNHEALTHY") == "1" and s == "gateway":
                health = "starting"
            out.append({"Service": s, "State": "running", "Health": health, "ExitCode": 0})
        print(json.dumps(out))
        sys.exit(0)
    sys.exit(0)

# ---------------------------------------------------------------- ps / network / その他
if args[:1] == ["ps"]:
    # #1293: `docker ps -a --filter "volume=<name>" --format '{{.Names}}'` —
    # ボリューム破棄が失敗したとき、参照しているコンテナ名を名指しするために使う。
    filter_value = ""
    format_value = ""
    i = 1
    while i < len(args):
        if args[i] == "--filter":
            filter_value = args[i + 1] if i + 1 < len(args) else ""
            i += 2
            continue
        if args[i].startswith("--filter="):
            filter_value = args[i].split("=", 1)[1]
            i += 1
            continue
        if args[i] == "--format":
            format_value = args[i + 1] if i + 1 < len(args) else ""
            i += 2
            continue
        if args[i].startswith("--format="):
            format_value = args[i].split("=", 1)[1]
            i += 1
            continue
        i += 1
    if filter_value.startswith("volume="):
        vol = filter_value.split("=", 1)[1]
        names = []
        if vol.endswith("comfyui_output") and gpu_leftover_container():
            names.append(gpu_leftover_container())
        extra = os.environ.get("FAKE_REFERENCING_CONTAINER", "")
        if extra and extra not in names:
            names.append(extra)
        print("\n".join(names))
        sys.exit(0)
    # #1065: `docker ps --format '{{.Names}}\t{{.Ports}}'` —
    # 直前のスタックの起動有無に依らず、ホストの80/443番を握るコンテナを
    # 名前を問わず調べるための呼び方。フィルタは付かない(全コンテナが対象)。
    if "{{.Names}}" in format_value and "{{.Ports}}" in format_value:
        lines_out = []
        for c in read_lines("containers"):
            ports = ""
            if c == "lbs-reverse-proxy" and os.environ.get("FAKE_SHARED_HOST") != "1":
                ports = "0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp"
            lines_out.append("%s\t%s" % (c, ports))
        if os.environ.get("FAKE_OTHER_STACK_HOLDS_PORT") == "1":
            lines_out.append("infra-proxy\t0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp")
        print("\n".join(lines_out))
        sys.exit(0)
    print("")
    sys.exit(0)

sys.exit(0)
'''

# 偽 docker のソースへ、ボリューム一覧を埋め込む(テスト側の定義を唯一の出所にする)。
FAKE_DOCKER = FAKE_DOCKER.replace(
    "# __VOLUME_LISTS__",
    "DESTROYED = %r\nPRESERVED = %r" % (DESTROYED_VOLUMES, PRESERVED_VOLUMES),
)
assert "DESTROYED = [" in FAKE_DOCKER, "偽 docker へボリューム一覧を埋め込めていない"

FAKE_CURL = r'''#!/usr/bin/env python3
"""テスト用の curl スタブ。setup-status とベースURLだけを返す。"""
import os
import sys

argv = sys.argv[1:]
url = [a for a in argv if a.startswith("http")]
url = url[0] if url else ""

with open(os.environ["FAKE_DOCKER_LOG"], "a", encoding="utf-8") as f:
    f.write("curl\t" + " ".join(argv) + "\n")

if "setup-status" in url:
    body = os.environ.get("FAKE_SETUP_STATUS", '{"needsSetup":true}')
    code = os.environ.get("FAKE_SETUP_CODE", "200")
else:
    body = ""
    code = "200"
    # ベースURLは、構築直後しばらく 502 を返す(下流の web / gateway がまだ起動途中)。
    # FAKE_LOCALHOST_WARMUP 回だけ 502 を返し、以降 200 にする。
    warmup = int(os.environ.get("FAKE_LOCALHOST_WARMUP", "0"))
    if warmup:
        counter = os.path.join(os.environ["FAKE_STATE"], "localhost-probes")
        seen = 0
        if os.path.exists(counter):
            with open(counter, encoding="utf-8") as f:
                seen = int(f.read() or 0)
        seen += 1
        with open(counter, "w", encoding="utf-8") as f:
            f.write(str(seen))
        if seen <= warmup:
            code = "502"

write_format = ""
if "-w" in argv:
    write_format = argv[argv.index("-w") + 1]

silent_body = "-o" in argv
if not silent_body:
    sys.stdout.write(body)
sys.stdout.write(write_format.replace("%{http_code}", code).replace("\\n", "\n"))
sys.exit(0)
'''


class RebuildScriptHarness(unittest.TestCase):
    """偽 docker / 偽 curl の上でゼロ構築スクリプトを走らせるための土台。"""

    maxDiff = None

    def setUp(self):
        self.assertTrue(exists(SCRIPT), "%s が無い" % SCRIPT)
        self.tmp = tempfile.mkdtemp(prefix="at-rebuild-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.state = os.path.join(self.tmp, "state")
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(os.path.join(self.state, "volumes"))
        os.makedirs(self.bin)
        self.infra = os.path.join(self.tmp, "infra")
        os.makedirs(os.path.join(self.infra, "proxy", "conf.d"))
        os.makedirs(os.path.join(self.infra, "proxy", "certs"))
        self.log = os.path.join(self.tmp, "docker.log")

        for name, source in (("docker", FAKE_DOCKER), ("curl", FAKE_CURL)):
            p = os.path.join(self.bin, name)
            with open(p, "w", encoding="utf-8") as f:
                f.write(source)
            os.chmod(p, 0o755)

        # 直前のスタックが起動している状態を既定にする。
        self.set_containers(
            ["lbs-mysql", "lbs-keycloak", "lbs-wordpress", "lbs-reverse-proxy"]
            + ["lbs-%s" % s for s in ("gateway", "web", "identity")]
        )
        for v in DESTROYED_VOLUMES + PRESERVED_VOLUMES:
            self.create_volume(v, "2020-01-01T00:00:00Z")
        self.write_state("mysqldbs", SERVICE_SCHEMAS + [s + "_test" for s in SERVICE_SCHEMAS])
        self.write_state("kcusers", [])
        self.write_state("wpsites", [])

    # -- 状態の読み書き -------------------------------------------------
    def write_state(self, name, lines):
        with open(os.path.join(self.state, name), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))

    def read_state(self, name):
        p = os.path.join(self.state, name)
        if not os.path.exists(p):
            return []
        with open(p, encoding="utf-8") as f:
            return [l.strip() for l in f if l.strip()]

    def set_containers(self, names):
        self.write_state("containers", sorted(set(names)))

    def create_volume(self, short_name, when):
        with open(
            os.path.join(self.state, "volumes", VOLUME_PREFIX + short_name), "w", encoding="utf-8"
        ) as f:
            f.write(when)

    def volume_created_at(self, short_name):
        p = os.path.join(self.state, "volumes", VOLUME_PREFIX + short_name)
        if not os.path.exists(p):
            return None
        with open(p, encoding="utf-8") as f:
            return f.read().strip()

    def docker_calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log, encoding="utf-8") as f:
            return [l.rstrip("\n") for l in f if l.strip()]

    # -- 実行 -----------------------------------------------------------
    def run_script(self, *args, **env_overrides):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_STATE"] = self.state
        env["FAKE_DOCKER_LOG"] = self.log
        # healthy 待ちの既定は 900 秒。単体テストでその上限を待つわけにはいかないので短くする。
        # 縮められるのは**待つ時間**だけで、接続先・プロジェクト・ボリュームは差し替えられない。
        env["ACCEPTANCE_HEALTH_TIMEOUT"] = "10"
        # 共有プロキシの再適用は実物の scripts/setup-shared-host-proxy.sh を呼ぶ。
        # 書き込み先を一時ディレクトリへ逃がし、GitLab の生存確認は省く。
        env["INFRA_DIR"] = self.infra
        env["GITLAB_HEALTH_URL"] = ""
        env.update({k: str(v) for k, v in env_overrides.items()})
        root = self.script_root()
        return subprocess.run(
            ["bash", os.path.join(root, SCRIPT)] + list(args),
            capture_output=True,
            text=True,
            timeout=300,
            env=env,
            cwd=root,
        )

    def script_root(self):
        """スクリプトを走らせるリポジトリ直下。

        `.env` / `certs/` は .gitignore 対象で、worktree や新規チェックアウトには無い(#1291)。
        足りないときだけ、ダミーで補った影のチェックアウトを使う。揃っていれば実物のままで、
        テストの件数も検出力も変わらない。
        """
        if not shadow_checkout.needs_shadow():
            return REPO_ROOT
        if not hasattr(self, "_shadow_root"):
            self._shadow_root = shadow_checkout.make(tempfile.mkdtemp(dir=self.tmp))
        return self._shadow_root

    def out(self, r):
        return r.stdout + r.stderr


class Usage(RebuildScriptHarness):
    def test_is_executable(self):
        st = os.stat(os.path.join(REPO_ROOT, SCRIPT))
        self.assertTrue(st.st_mode & stat.S_IXUSR, "%s に実行ビットが無い" % SCRIPT)

    def test_syntax_is_valid(self):
        r = subprocess.run(
            ["bash", "-n", os.path.join(REPO_ROOT, SCRIPT)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(0, r.returncode, r.stderr)

    def test_help_exits_zero(self):
        r = self.run_script("--help")
        self.assertEqual(0, r.returncode, self.out(r))
        self.assertIn("--yes", r.stdout)

    def test_unknown_option_is_refused(self):
        r = self.run_script("--project", "something-else")
        self.assertNotEqual(0, r.returncode, "接続先を差し替える引数が通ってしまった")
        self.assertIn("不明な引数", self.out(r))


class SafetyDeviceIsHardcodedTargets(unittest.TestCase):
    """安全装置(1)は維持する: 接続先・compose プロジェクト・ボリューム名を引数で差し替えられない。

    #965 §2 は「1 は維持し、ゼロ構築スクリプトへそのまま引き継ぐ」と決めている。
    """

    def setUp(self):
        self.assertTrue(exists(SCRIPT), "%s が無い" % SCRIPT)
        self.text = read(SCRIPT)

    def test_compose_project_is_readonly(self):
        self.assertRegex(
            self.text,
            r"readonly\s+COMPOSE_PROJECT=[\"']?lets_blog_server",
            "compose プロジェクト名が readonly で固定されていない",
        )

    def test_volume_lists_are_readonly(self):
        self.assertRegex(self.text, r"readonly\s+DESTROY_VOLUMES=\(", "破棄対象が固定されていない")
        self.assertRegex(self.text, r"readonly\s+PRESERVE_VOLUMES=\(", "保全対象が固定されていない")

    def test_declares_every_compose_volume(self):
        """docker-compose.yml が宣言するボリュームを、破棄か保全のどちらかに必ず割り当てる。

        取りこぼしたボリュームは「黙って残る」。列挙漏れに気づく手段が無いのが
        現行リセットの問題(#965 Problem)なので、ここで固定する。
        """
        compose = read("docker-compose.yml")
        declared = []
        in_volumes = False
        for line in compose.splitlines():
            if line.startswith("volumes:"):
                in_volumes = True
                continue
            if in_volumes:
                if line and not line.startswith(" "):
                    break
                name = line.strip().rstrip(":")
                if name and not name.startswith("#"):
                    declared.append(name)
        self.assertTrue(declared, "docker-compose.yml の volumes: を読み取れなかった")
        for name in declared:
            with self.subTest(volume=name):
                self.assertIn(
                    name,
                    self.text,
                    "ボリューム %s が破棄・保全のどちらにも現れない" % name,
                )

    def test_fixture_volume_lists_match_the_script(self):
        """このテストの偽 docker が持つ `DESTROYED_VOLUMES` / `PRESERVED_VOLUMES` は、
        スクリプトの `DESTROY_VOLUMES` / `PRESERVE_VOLUMES` と一致していなければならない。

        一致していないと、偽 docker は一覧に無いボリュームを作らず、`rebuild-acceptance-env.sh`
        の事後検証が「作り直されていない」と誤って NG を出す(#1327: avatar_images が
        #1288 でスクリプト側に追加された後、fixture 側の一覧が追随していなかった)。
        """
        destroy_match = re.search(r"readonly\s+DESTROY_VOLUMES=\(([^)]*)\)", self.text, re.S)
        preserve_match = re.search(r"readonly\s+PRESERVE_VOLUMES=\(([^)]*)\)", self.text, re.S)
        self.assertIsNotNone(destroy_match, "DESTROY_VOLUMES を読み取れなかった")
        self.assertIsNotNone(preserve_match, "PRESERVE_VOLUMES を読み取れなかった")
        script_destroy = set(destroy_match.group(1).split())
        script_preserve = set(preserve_match.group(1).split())
        self.assertEqual(
            script_destroy,
            set(DESTROYED_VOLUMES),
            "fixture の DESTROYED_VOLUMES がスクリプトの DESTROY_VOLUMES と食い違う",
        )
        self.assertEqual(
            script_preserve,
            set(PRESERVED_VOLUMES),
            "fixture の PRESERVED_VOLUMES がスクリプトの PRESERVE_VOLUMES と食い違う",
        )

    def test_probe_names_are_confined_to_the_wipe_probe_prefix(self):
        """スクリプトが作るアカウントは at-wipe-probe- 接頭辞に限る(#965 §2)。"""
        self.assertIn("at-wipe-probe-", self.text)
        self.assertIn("@letsblog.local", self.text)

    def test_does_not_delete_accounts_by_name(self):
        """ゼロ構築経路は、名指しでアカウントを削除する処理を持たない(#965 §2)。"""
        self.assertNotIn("kcadm delete", self.text)
        self.assertNotIn("delete users/", self.text)

    def test_preserves_comfyui_models_and_says_why(self):
        self.assertRegex(self.text, r"readonly\s+PRESERVE_VOLUMES=\(\s*comfyui_models")
        self.assertIn("モデル", self.text, "comfyui_models を保全する理由がコメントに無い")

    def test_preserves_ollama_models_and_says_why(self):
        """#1089: ollama_models も comfyui_models と同じく保全対象で、理由が明文化されている。"""
        self.assertIn(
            "ollama_models",
            re.search(r"readonly\s+PRESERVE_VOLUMES=\([^)]*\)", self.text, re.S).group(0),
            "ollama_models が PRESERVE_VOLUMES に無い",
        )
        preserve_block = re.search(
            r"保全するボリューム。.*?readonly\s+PRESERVE_VOLUMES=\([^)]*\)", self.text, re.S
        )
        self.assertIsNotNone(preserve_block, "PRESERVE_VOLUMES 直上のコメント塊が見つからない")
        self.assertIn(
            "ollama_models",
            preserve_block.group(0),
            "ollama_models を保全する理由のコメントが無い",
        )

    def test_reuses_the_existing_health_wait(self):
        """#965 §1-3: `wait-for-stack-healthy.sh` を再利用する(重複実装しない)。"""
        self.assertIn("wait-for-stack-healthy.sh", self.text)

    def test_legacy_data_layer_reset_keeps_its_domain_restriction(self):
        """#965 §2: データ層リセット経路のドメイン限定は撤廃しない。"""
        legacy = read(LEGACY_SCRIPT)
        self.assertIn("SYNTHETIC_EMAIL_DOMAIN", legacy)
        self.assertIn("@letsblog.local", legacy)


class DryRunChangesNothing(RebuildScriptHarness):
    """受入基準: `--yes` なしは3つを表示して、何も削除せず、ダミーも作らない。"""

    def setUp(self):
        super().setUp()
        self.before = {v: self.volume_created_at(v) for v in DESTROYED_VOLUMES + PRESERVED_VOLUMES}
        self.r = self.run_script()

    def test_exits_zero(self):
        self.assertEqual(0, self.r.returncode, self.out(self.r))

    def test_lists_the_volumes_it_would_destroy(self):
        out = self.out(self.r)
        self.assertIn("破棄するボリューム", out)
        for v in DESTROYED_VOLUMES:
            with self.subTest(volume=v):
                self.assertIn(VOLUME_PREFIX + v, out)

    def test_lists_the_volumes_it_would_preserve(self):
        out = self.out(self.r)
        self.assertIn("保全するボリューム", out)
        self.assertIn(VOLUME_PREFIX + "comfyui_models", out)
        self.assertIn(VOLUME_PREFIX + "ollama_models", out, "ollama_models が保全リストに出ていない")

    def test_does_not_list_ollama_models_as_a_volume_to_destroy(self):
        out = self.out(self.r)
        destroy_section = out.split("保全するボリューム", 1)[0]
        self.assertNotIn(
            VOLUME_PREFIX + "ollama_models",
            destroy_section,
            "ollama_models が破棄するボリュームの一覧に出ている",
        )

    def test_lists_the_probes_it_would_create(self):
        out = self.out(self.r)
        self.assertIn("作成するプローブ", out)
        self.assertRegex(out, r"at-wipe-probe-\d+@letsblog\.local")
        self.assertRegex(out, r"at_wipe_probe_\d+")
        self.assertRegex(out, r"/var/www/html/sites/at-wipe-probe-\d+")

    def test_says_it_changed_nothing(self):
        self.assertIn("何も変更していません", self.out(self.r))

    def test_created_no_probes(self):
        self.assertEqual([], self.read_state("kcusers"), "ドライランが Keycloak ユーザーを作った")
        self.assertEqual(
            [], [d for d in self.read_state("mysqldbs") if d.startswith("at_wipe_probe_")]
        )
        self.assertEqual([], self.read_state("wpsites"), "ドライランが WordPress サイトを作った")

    def test_destroyed_nothing(self):
        after = {v: self.volume_created_at(v) for v in DESTROYED_VOLUMES + PRESERVED_VOLUMES}
        self.assertEqual(self.before, after, "ドライランでボリュームの CreatedAt が変わった")

    def test_ran_no_destructive_docker_command(self):
        for call in self.docker_calls():
            with self.subTest(call=call):
                self.assertNotIn("volume\trm", call)
                self.assertNotIn("down", call.split("\t"))
                self.assertNotIn("up", call.split("\t"))


class ProbesAreCreatedBeforeTheTeardown(RebuildScriptHarness):
    """受入基準: `--yes` 実行時、3つのダミーが作られ、作られたことがログに出る。"""

    def setUp(self):
        super().setUp()
        self.r = self.run_script("--yes")
        self.out_text = self.out(self.r)

    def test_succeeds(self):
        self.assertEqual(0, self.r.returncode, self.out_text)

    def test_logs_all_three_probes_with_the_same_identifier(self):
        import re

        ids = set(re.findall(r"at[-_]wipe[-_]probe[-_](\d+)", self.out_text))
        self.assertEqual(1, len(ids), "プローブ識別子が一意でない: %s\n%s" % (ids, self.out_text))
        probe_id = ids.pop()
        self.assertIn("at-wipe-probe-%s@letsblog.local" % probe_id, self.out_text)
        self.assertIn("at_wipe_probe_%s" % probe_id, self.out_text)
        self.assertIn("/var/www/html/sites/at-wipe-probe-%s" % probe_id, self.out_text)

    def test_creates_the_keycloak_user_with_kcadm_not_the_identity_api(self):
        calls = "\n".join(self.docker_calls())
        self.assertIn("kcadm.sh", calls)
        self.assertIn("create", calls)
        self.assertNotIn("/api/users", calls, "identity-service の API でユーザーを作っている")

    def test_creates_a_standalone_mysql_database(self):
        calls = [c for c in self.docker_calls() if "CREATE DATABASE" in c]
        self.assertTrue(calls, "MySQL のプローブ DB を作っていない")
        for c in calls:
            with self.subTest(call=c):
                self.assertIn("at_wipe_probe_", c)
                for schema in SERVICE_SCHEMAS:
                    self.assertNotIn(
                        "`%s`" % schema, c, "サービススキーマの中にプローブを作っている"
                    )

    def test_confirms_the_probes_exist_right_after_creating_them(self):
        """作成直後の存在確認が無いと、後の「消えている」が「作れていなかった」と区別できない。"""
        self.assertRegex(self.out_text, r"確認:.*プローブ.*存在")
        import re

        confirm = re.search(r"確認:.*プローブ.*存在.*", self.out_text)
        teardown = re.search(r"^--- 1/5 .*$", self.out_text, re.M)
        self.assertIsNotNone(teardown, "撤去の段が見つからない:\n" + self.out_text)
        self.assertLess(
            confirm.start(), teardown.start(), "プローブの存在確認が撤去より後に来ている"
        )

    def test_does_not_delete_the_probes_itself(self):
        """消すのは `down -v`(ボリューム破棄)である。個別削除は書かない(#965 §7-A-3)。"""
        for call in self.docker_calls():
            with self.subTest(call=call):
                self.assertNotIn("DROP DATABASE", call)
                self.assertNotIn("rm -rf /var/www/html/sites/at-wipe-probe", call)


class PlacementCheckSurvivesSigpipeUnderManyExistingUsers(RebuildScriptHarness):
    """#1233: `kcadm get users | grep -q ...` が SIGPIPE で誤って失敗しないことを確かめる。

    `grep -q` は最初の一致を見つけた時点で標準入力を閉じて終了する。このとき
    kcadm(ここでは偽 docker)がまだ大量の残り出力を書き込み中だと、パイプの
    読み手が消えたことで SIGPIPE(終了コード141)を受けて死ぬ。`set -o pipefail`
    下ではパイプ全体の終了コードが141になり、grep 自身は一致していた
    (本来は成功)にもかかわらず `|| placed_ok=0` に落ちて
    「設置を確認できなかった」という誤検知になる。

    実運用では既存ユーザーが18人でも再現した(プローブ名がアルファベット順で
    中間に来るため)。ここでは確実に再現させるため、プローブより後にソートされる
    大量のダミーユーザーを用意し、`kcadm get users` の出力を OS パイプの容量
    (既定64KB)を大きく超えさせ、grep の早期終了時になお書き込み中にする。
    """

    def setUp(self):
        super().setUp()
        # "at-wipe-probe-<epoch>@letsblog.local" よりアルファベット順で後に来る
        # 大量のダミーユーザーを用意する。grep がプローブに一致して即座に
        # 標準入力を閉じたあとも、まだ書き込むべき出力が大量に残っている状況を作る。
        padding = "x" * 200
        many_users = ["zzz-user-%05d-%s@letsblog.local" % (i, padding) for i in range(3000)]
        self.write_state("kcusers", many_users)

    def test_placement_check_is_not_defeated_by_sigpipe(self):
        r = self.run_script("--yes")
        out = self.out(r)
        self.assertEqual(
            0,
            r.returncode,
            "既存ユーザーが多いと SIGPIPE で誤って失敗する(#1233):\n" + out,
        )
        self.assertNotIn(
            "プローブを設置できたことを確認できませんでした",
            out,
            "設置確認が誤検知で失敗した(#1233):\n" + out,
        )


class PlacementCheckReportsAGenuineKcadmFailure(RebuildScriptHarness):
    """#1233 フォローアップ: `kcadm get users` が SIGPIPE ではなく本物のエラーで
    失敗したとき、診断メッセージ無しに黙って終了してはいけない。

    `kc_users_output="$(kcadm get users ...)"` という代入文は、それ自体は
    どの `||`/`&&` リストにも入っていない裸の文なので、`set -e` 下で kcadm が
    (SIGPIPE ではなく)本当に失敗すると、`placed_ok` を見る前にその場で
    スクリプトが終了してしまう。kcadm の標準エラーは `/dev/null` に捨てているため、
    これは無言のまま(診断メッセージ無しに)終了する退行になる。
    """

    def test_reports_diagnostic_when_kcadm_get_users_genuinely_fails(self):
        r = self.run_script("--yes", FAKE_KCADM_GET_USERS_FAILS="1")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "kcadm の本物の失敗が握りつぶされている:\n" + out)
        self.assertIn(
            "プローブを設置できたことを確認できませんでした",
            out,
            "kcadm が本物のエラーで失敗しても、診断メッセージ無しに無言で終了している(#1233 フォローアップ):\n"
            + out,
        )


class ProbesAreGoneAfterTheRebuild(RebuildScriptHarness):
    def test_reports_all_three_probes_gone(self):
        r = self.run_script("--yes")
        self.assertEqual(0, r.returncode, self.out(r))
        self.assertIn("プローブ", self.out(r))
        self.assertRegex(self.out(r), r"OK:.*プローブ")

    def test_probe_state_is_actually_empty(self):
        self.run_script("--yes")
        self.assertEqual([], self.read_state("kcusers"))
        self.assertEqual(
            [], [d for d in self.read_state("mysqldbs") if d.startswith("at_wipe_probe_")]
        )
        self.assertEqual([], self.read_state("wpsites"))


class RemainingKeycloakProbeSurvivesSigpipeUnderManyUsers(RebuildScriptHarness):
    """#1277: 手順4-7 の `kcadm get users | grep -q` が SIGPIPE で診断を潰さないこと。

    プローブが残っている異常系では grep -q が早期に一致して終了し、書き込み中の
    kcadm が SIGPIPE を受ける。`set -o pipefail` 下ではパイプ全体が141になり、
    `&& remaining=...` が実行されず、残存の名指しが消える。
    """

    def test_names_the_surviving_keycloak_probe_even_with_many_users(self):
        r = self.run_script("--yes", FAKE_STICKY_PROBE="keycloak", FAKE_PAD_KC_USERS="3000")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertRegex(
            out,
            r"- Keycloak ユーザー at-wipe-probe-\d+@letsblog\.local"
            + re.escape("(keycloak_postgres が破棄されていない)"),
            "ユーザーが多いと SIGPIPE で残存プローブの名指しが消える(#1277):\n" + out,
        )


class RemainingProbeIsNamedAndFatal(RebuildScriptHarness):
    """受入基準: どれか1つでも残ったら、どれが残ったかを名指しして非0で終了する。

    ここで再現するのは「**ボリュームは破棄され、構築で作り直されたのに、そのプローブの
    実体だけが構築後の存在確認に現れる**」状態である(古いレプリカ、キャッシュ、
    削除経路のバグ)。手順4-7 の存在確認は、まさにこれを捕まえるために置かれている。

    `FAKE_UNDELETABLE`(`docker volume rm` 自体を失敗させる)ではこの経路へ**入れない**。
    スクリプトは手順1で「ボリューム ... を破棄できませんでした」と落ち、手順4へ到達しない。
    それでも旧テストが green だったのは、手順0の計画表示と作成ログに
    プローブ名がそのまま出ているためで、手順4-7 は一度も走っていなかった(QA 1、2026-09-05)。

    そこで `FAKE_STICKY_PROBE` を使う。`volume rm` は成功させ、ボリュームは作り直したうえで、
    **指定した1種類のプローブの実体だけ**を破棄をまたいで残す。
    """

    #: 手順4-7 だけが出す見出し。手順0の計画表示にも 4-4 / 4-5 にも現れない。
    STEP_47_HEADING = "NG: 撤去したはずのプローブが残っています"

    #: 手順4-7 が各プローブに付ける括弧書き。名指しの根拠がここにある。
    STEP_47_REASON = {
        "keycloak": "(keycloak_postgres が破棄されていない)",
        "mysql": "(mysql_data が破棄されていない)",
        "wordpress": "(wordpress_sites が破棄されていない)",
    }

    def run_with_sticky_probe(self, kind):
        """1種類だけプローブが生き残った状態で走らせ、手順4-7 を通ったことまで確かめる。"""
        r = self.run_script("--yes", FAKE_STICKY_PROBE=kind)
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "プローブが残ったのに成功した:\n" + out)
        self.assertNotIn(
            "を破棄できませんでした",
            out,
            "手順1のボリューム破棄失敗で落ちている(手順4-7 を検証できていない):\n" + out,
        )
        self.assertIn("--- 4/5", out, "手順4(検証)へ到達していない:\n" + out)
        self.assertIn(
            self.STEP_47_HEADING,
            out,
            "手順4-7(構築後のプローブ存在確認)が失敗を検出していない:\n" + out,
        )
        # 破棄と再作成そのものは成立している。捕まえたのは「作り直したのに残っている」である。
        for v in DESTROYED_VOLUMES:
            self.assertNotEqual(
                "2020-01-01T00:00:00Z",
                self.volume_created_at(v),
                "%s が作り直されていない(別の失敗を見ている)" % v,
            )
        # 名指しは残った1種類だけに限る。
        for other, reason in self.STEP_47_REASON.items():
            if other != kind:
                self.assertNotIn(reason, out, "残っていない %s まで名指ししている" % other)
        return out

    def test_names_the_keycloak_probe_when_the_user_survives_the_wipe(self):
        out = self.run_with_sticky_probe("keycloak")
        self.assertRegex(
            out,
            r"- Keycloak ユーザー at-wipe-probe-\d+@letsblog\.local"
            + re.escape(self.STEP_47_REASON["keycloak"]),
            "残った Keycloak プローブを名指ししていない:\n" + out,
        )

    def test_names_the_mysql_probe_when_the_database_survives_the_wipe(self):
        out = self.run_with_sticky_probe("mysql")
        self.assertRegex(
            out,
            r"- MySQL データベース at_wipe_probe_\d+" + re.escape(self.STEP_47_REASON["mysql"]),
            "残った MySQL プローブを名指ししていない:\n" + out,
        )

    def test_names_the_wordpress_probe_when_the_directory_survives_the_wipe(self):
        out = self.run_with_sticky_probe("wordpress")
        self.assertRegex(
            out,
            r"- WordPress ディレクトリ /var/www/html/sites/at-wipe-probe-\d+"
            + re.escape(self.STEP_47_REASON["wordpress"]),
            "残った WordPress プローブを名指ししていない:\n" + out,
        )


class ProbesAreSkippedWhenThePreviousStackIsDown(RebuildScriptHarness):
    """受入基準: 直前のスタックが停止していても実行でき、省略したことを出力に残す。"""

    def setUp(self):
        super().setUp()
        self.set_containers([])
        self.r = self.run_script("--yes")

    def test_succeeds(self):
        self.assertEqual(0, self.r.returncode, self.out(self.r))

    def test_says_the_probes_were_skipped_and_why(self):
        self.assertIn("プローブ省略", self.out(self.r))
        self.assertIn("直前のスタックが起動していない", self.out(self.r))

    def test_still_verifies_volume_recreation(self):
        self.assertIn("CreatedAt", self.out(self.r))
        for v in DESTROYED_VOLUMES:
            with self.subTest(volume=v):
                self.assertNotEqual(
                    "2020-01-01T00:00:00Z",
                    self.volume_created_at(v),
                    "%s が作り直されていない" % v,
                )


class VolumesAreDestroyedAndRecreated(RebuildScriptHarness):
    def test_destroy_targets_get_a_newer_created_at(self):
        r = self.run_script("--yes")
        self.assertEqual(0, r.returncode, self.out(r))
        for v in DESTROYED_VOLUMES:
            with self.subTest(volume=v):
                self.assertNotEqual("2020-01-01T00:00:00Z", self.volume_created_at(v))

    def test_comfyui_models_is_never_removed(self):
        self.run_script("--yes")
        for call in self.docker_calls():
            if call.startswith("volume\trm"):
                with self.subTest(call=call):
                    self.assertNotIn("comfyui_models", call)

    def test_comfyui_models_created_at_is_unchanged(self):
        self.run_script("--yes")
        self.assertEqual(
            "2020-01-01T00:00:00Z",
            self.volume_created_at("comfyui_models"),
            "保全対象の comfyui_models が作り直されている",
        )

    def test_ollama_models_is_never_removed(self):
        """#1089: ollama_models も comfyui_models と同じく volume rm の対象にならない。"""
        self.run_script("--yes")
        for call in self.docker_calls():
            if call.startswith("volume\trm"):
                with self.subTest(call=call):
                    self.assertNotIn("ollama_models", call)

    def test_ollama_models_created_at_is_unchanged(self):
        """#1089: ollama_models の CreatedAt が実行後も変わらないことを検証する。"""
        r = self.run_script("--yes")
        self.assertEqual(0, r.returncode, self.out(r))
        self.assertEqual(
            "2020-01-01T00:00:00Z",
            self.volume_created_at("ollama_models"),
            "保全対象の ollama_models が作り直されている",
        )
        self.assertRegex(
            self.out(r),
            r"OK:.*ollama_models.*保全",
            "ollama_models の保全検証の OK ログが出ていない",
        )

    def test_a_volume_that_cannot_be_destroyed_is_fatal_at_the_teardown(self):
        """手順1: `docker volume rm` が失敗したら、そこで名指しして止まる。

        ここで止まるので、**手順4の検証には到達しない**。手順4-1(構築後に CreatedAt が
        更新されているか)を検査したいなら別の壊し方が要る —
        `test_reports_a_destroy_target_that_the_build_did_not_recreate` がそれである。
        """
        r = self.run_script("--yes", FAKE_UNDELETABLE="rabbitmq_data")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "破棄できていないのに成功した:\n" + out)
        self.assertIn("rabbitmq_data", out)
        self.assertIn("を破棄できませんでした", out)

    def test_reports_a_destroy_target_that_the_build_did_not_recreate(self):
        """手順4-1: 破棄はできたのに構築が作り直さなかったボリュームを名指しして非0終了する。

        破棄そのものは成功させ(`volume rm` は通る)、構築だけがそのボリュームを
        作らない状態を作る。手順1では落ちないので、判定は手順4-1 が下すしかない。
        """
        r = self.run_script("--yes", FAKE_NOT_RECREATED="rabbitmq_data")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "作り直されていないのに成功した:\n" + out)
        self.assertNotIn(
            "を破棄できませんでした", out, "手順1で落ちている(手順4-1 を検証できていない):\n" + out
        )
        self.assertIn("--- 4/5", out, "手順4(検証)へ到達していない:\n" + out)
        self.assertIn(
            VOLUME_PREFIX + "rabbitmq_data が存在しません(構築で作り直されていない)",
            out,
            "作り直されなかったボリュームを名指ししていない:\n" + out,
        )

    def test_builds_from_source(self):
        self.run_script("--yes")
        calls = "\n".join(self.docker_calls())
        self.assertIn("--build", calls, "ソースからの再ビルドを行っていない")

    def test_no_cache_is_available_as_a_flag(self):
        self.run_script("--yes", "--no-cache")
        calls = "\n".join(self.docker_calls())
        self.assertIn("--no-cache", calls)

    def test_starts_the_required_services_even_though_comfyui_cannot_start(self):
        """**回帰させてはいけない検査(2026-09-04 の実測で踏んだ)。**

        この開発機には NVIDIA ランタイムが無く、`comfyui` は起動できない
        (`could not select device driver "nvidia"`)。`docker compose up -d` を
        サービス無指定で呼ぶと、compose はそこで**中断**し、依存関係の下流
        (web / gateway / keycloak / 各ドメインサービス)を `created` のまま残す。
        健全性待ちはそれを 900 秒待ってからタイムアウトする — 実測で踏んだ壊れ方である。

        必須サービスと任意サービスを分けて起動し、任意サービスの起動失敗は警告に留めること。
        """
        r = self.run_script("--yes")
        out = self.out(r)
        self.assertEqual(0, r.returncode, "comfyui の起動失敗でゼロ構築ごと落ちている:\n" + out)
        for service in ("web", "gateway", "keycloak", "identity"):
            with self.subTest(service=service):
                self.assertIn(
                    "lbs-%s" % service,
                    self.read_state("containers"),
                    "%s が起動していない(compose が comfyui で中断している)" % service,
                )

    def test_says_which_optional_service_failed_to_start(self):
        r = self.run_script("--yes")
        self.assertIn("comfyui", self.out(r), "起動しなかった任意サービスの名前が出ていない")

    def test_still_creates_the_volume_of_the_optional_service(self):
        """comfyui_output は comfyui だけが使う。作られないと破棄検証が通らない。"""
        self.run_script("--yes")
        self.assertIsNotNone(self.volume_created_at("comfyui_output"))

    def test_includes_the_e2e_stub_compose_file(self):
        self.run_script("--yes")
        calls = "\n".join(self.docker_calls())
        self.assertIn("docker-compose.e2e-stubs.yml", calls)


class PostBuildVerification(RebuildScriptHarness):
    """#965 §4: ゼロ構築が成立したことをスクリプト自身が確かめる。"""

    def test_fails_when_the_stack_never_becomes_healthy(self):
        """受入基準: どのサービスが healthy になっていないかが分かる形で非0終了する。

        `ACCEPTANCE_HEALTH_TIMEOUT` が効いていないと、ここは既定の 900 秒を待って
        subprocess のタイムアウトで落ちる(= スクリプトが上限を渡していない)。
        """
        r = self.run_script("--yes", FAKE_UNHEALTHY="1")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "healthy でないのに成功した:\n" + out)
        self.assertIn("gateway", out, "どのサービスが healthy でないかが分からない")
        self.assertIn("後続の段階は実行されません", out)

    def test_fails_when_setup_status_is_not_200(self):
        r = self.run_script("--yes", FAKE_SETUP_CODE="502")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "setup-status が 502 なのに成功した:\n" + out)
        self.assertIn("setup-status", out)

    def test_fails_when_setup_is_not_needed(self):
        r = self.run_script("--yes", FAKE_SETUP_STATUS='{"needsSetup":false}')
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "needsSetup が false なのに成功した:\n" + out)
        self.assertIn("needsSetup", out)

    def test_fails_when_an_unexpected_keycloak_user_survives(self):
        r = self.run_script("--yes", FAKE_EXTRA_KC_USER="leftover@example.com")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "余計なユーザーが居るのに成功した:\n" + out)
        self.assertIn("leftover@example.com", out)

    def test_accepts_only_the_service_account(self):
        """`service-account-letsblog-services` だけは残っていてよい(#965 §4-4)。"""
        r = self.run_script("--yes", FAKE_EXTRA_KC_USER="service-account-letsblog-services")
        self.assertEqual(0, r.returncode, self.out(r))

    def test_fails_when_a_schema_still_has_rows(self):
        r = self.run_script("--yes", FAKE_DIRTY_SCHEMA="lbs_content")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "スキーマにデータが残っているのに成功した:\n" + out)
        self.assertIn("lbs_content", out)

    def test_checks_the_test_schemas_were_recreated(self):
        """`*_test` は mysql_data ごと消えるので、02-create-test-schemas.sh による再作成を確認する。"""
        r = self.run_script("--yes")
        self.assertEqual(0, r.returncode, self.out(r))
        self.assertIn("_test", self.out(r))

    def test_removes_host_side_artifacts_of_the_previous_run(self):
        for rel in ("apps/web/test-results", "apps/web/playwright-report"):
            d = os.path.join(REPO_ROOT, rel)
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, ".at-probe"), "w", encoding="utf-8") as f:
                f.write("x")
        self.run_script("--yes")
        for rel in ("apps/web/test-results", "apps/web/playwright-report"):
            with self.subTest(path=rel):
                self.assertFalse(
                    os.path.exists(os.path.join(REPO_ROOT, rel, ".at-probe")),
                    "%s が消えていない" % rel,
                )


class HelperScriptsReceiveTheSharedComposeProjectNameExplicitly(RebuildScriptHarness):
    """#1297: `release-verify-tag.py` の隔離チェックアウトは basename が `checkout-XXXX` に
    なる。`wait-for-stack-healthy.sh`・`setup-shared-host-proxy.sh` はどちらも
    `COMPOSE_PROJECT_NAME` が設定されていなければ `basename "$REPO_ROOT"` へ落ちるので、
    `rebuild-acceptance-env.sh` は自分の `COMPOSE_PROJECT`(常に `lets_blog_server`)を
    ヘルパー呼び出しへ明示的に渡さなければならない。basename に依存させないことを検証する
    ため、実際に basename が `checkout-` で始まる別ディレクトリへスクリプトのコピーを置き、
    2つのヘルパーをスタブへ差し替えて、スタブが受け取った環境変数を検査する。
    """

    def setUp(self):
        super().setUp()
        self.checkout = tempfile.mkdtemp(prefix="checkout-")
        self.addCleanup(shutil.rmtree, self.checkout, ignore_errors=True)
        # scripts/ の他のファイル(check-worktree-match.py の `from paths import classify` が
        # 辿る .claude/hooks を含む)はシンボリックリンクで実体を共有する。rebuild-acceptance-env.sh
        # 自身もシンボリックリンクにすることで、実装フェーズでの本物への修正がそのまま反映される。
        os.symlink(os.path.join(REPO_ROOT, ".claude"), os.path.join(self.checkout, ".claude"))
        checkout_scripts = os.path.join(self.checkout, "scripts")
        os.makedirs(checkout_scripts)
        real_scripts = os.path.join(REPO_ROOT, "scripts")
        for entry in os.listdir(real_scripts):
            if entry in ("wait-for-stack-healthy.sh", "setup-shared-host-proxy.sh"):
                continue
            os.symlink(os.path.join(real_scripts, entry), os.path.join(checkout_scripts, entry))

        with open(os.path.join(self.checkout, ".env"), "w", encoding="utf-8") as f:
            f.write(
                "MYSQL_ROOT_PASSWORD=secret\n"
                "KEYCLOAK_ADMIN_USERNAME=admin\n"
                "KEYCLOAK_ADMIN_PASSWORD=admin\n"
            )

        self.helper_log = os.path.join(self.tmp, "helper-calls.log")
        self.write_stub_helper(checkout_scripts, "wait-for-stack-healthy.sh")
        self.write_stub_helper(checkout_scripts, "setup-shared-host-proxy.sh")

    def write_stub_helper(self, checkout_scripts, name):
        """本物のヘルパーの代わりに、受け取った環境変数を記録して非0で即終了するスタブを置く。

        非0で終わらせるのは、スタブより先の手順(healthy待ちの後始末や検証)まで
        本物のdocker/mysql/kcadmを模す必要をなくすため。呼び出し元は
        `|| { echo エラー; exit 1; }` / `if ! ...; then ...; exit 1; fi` の形で
        非0を正しくエラーとして扱うので、ここで打ち切っても呼び出しの検証には影響しない。
        """
        p = os.path.join(checkout_scripts, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(
                "#!/bin/bash\n"
                'echo "%s COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-<unset>}" >> %s\n'
                "exit 1\n" % (name, self.helper_log)
            )
        os.chmod(p, 0o755)

    def run_isolated_script(self, *args, **env_overrides):
        env = dict(os.environ)
        env["PATH"] = self.bin + os.pathsep + env["PATH"]
        env["FAKE_STATE"] = self.state
        env["FAKE_DOCKER_LOG"] = self.log
        env["ACCEPTANCE_HEALTH_TIMEOUT"] = "10"
        env["INFRA_DIR"] = self.infra
        env["GITLAB_HEALTH_URL"] = ""
        env["AT_WORKTREE_CHECK_BYPASS"] = "1"
        env.update({k: str(v) for k, v in env_overrides.items()})
        return subprocess.run(
            ["bash", os.path.join(self.checkout, "scripts", "rebuild-acceptance-env.sh")]
            + list(args),
            capture_output=True,
            text=True,
            timeout=120,
            env=env,
            cwd=self.checkout,
        )

    def helper_log_content(self):
        if not os.path.exists(self.helper_log):
            return ""
        with open(self.helper_log, encoding="utf-8") as f:
            return f.read()

    def test_wait_for_stack_healthy_receives_the_shared_project_name(self):
        self.run_isolated_script("--yes")
        content = self.helper_log_content()
        self.assertIn(
            "wait-for-stack-healthy.sh COMPOSE_PROJECT_NAME=lets_blog_server",
            content,
            "basename が checkout-... なクローンから実行しても、wait-for-stack-healthy.sh は "
            "COMPOSE_PROJECT_NAME=lets_blog_server を受け取るべき:\n" + content,
        )

    def test_setup_shared_host_proxy_receives_the_shared_project_name(self):
        self.run_isolated_script("--yes", FAKE_SHARED_HOST="1")
        content = self.helper_log_content()
        self.assertIn(
            "setup-shared-host-proxy.sh COMPOSE_PROJECT_NAME=lets_blog_server",
            content,
            "共有ホスト構成でも、setup-shared-host-proxy.sh は "
            "COMPOSE_PROJECT_NAME=lets_blog_server を受け取るべき:\n" + content,
        )


class RunningItTwiceGivesTheSameResult(RebuildScriptHarness):
    """受入基準: 2回連続で実行して、2回目も1回目と同じ結果になる。"""

    def test_second_run_succeeds_too(self):
        first = self.run_script("--yes")
        self.assertEqual(0, first.returncode, self.out(first))
        second = self.run_script("--yes")
        self.assertEqual(0, second.returncode, self.out(second))

    def test_second_run_leaves_no_probe_behind(self):
        self.run_script("--yes")
        self.run_script("--yes")
        self.assertEqual([], self.read_state("kcusers"))
        self.assertEqual(
            [], [d for d in self.read_state("mysqldbs") if d.startswith("at_wipe_probe_")]
        )
        self.assertEqual([], self.read_state("wpsites"))


class SharedHostProxyIsReapplied(RebuildScriptHarness):
    """#1038 の共有ホスト構成では、ネットワークを作り直すと infra-proxy の接続が切れる。

    `lbs-reverse-proxy` が 80/443 を公開していない = 共有ホスト構成である。
    この構成で `down` → `up` すると `https://localhost` が到達不能になるため、
    構築後に `scripts/setup-shared-host-proxy.sh` を再適用しなければならない
    (同スクリプトの「infra 側スタックを作り直したら、もう一度適用すること」と同じ事情)。
    """

    def test_uses_the_shared_host_override_when_ports_are_not_published(self):
        self.run_script("--yes", FAKE_SHARED_HOST="1")
        calls = "\n".join(self.docker_calls())
        self.assertIn("docker-compose.shared-host.yml", calls)

    def test_does_not_use_the_override_when_ports_are_published(self):
        self.run_script("--yes")
        calls = "\n".join(self.docker_calls())
        self.assertNotIn("docker-compose.shared-host.yml", calls)

    def test_mentions_reapplying_the_shared_host_setup(self):
        self.assertIn("setup-shared-host-proxy.sh", read(SCRIPT))

    def test_uses_the_shared_host_override_when_the_previous_stack_is_fully_stopped(self):
        """#1065: 直前のスタックが完全停止していても、80/443番を握る別コンテナ

        (infra-proxy等、`lbs-`で始まらないコンテナ)がいれば共有ホスト構成と判定する。
        `container_running "$REVERSE_PROXY_CONTAINER"` が偽になる状況でも検知できることが要点。
        """
        self.set_containers([])
        self.run_script("--yes", FAKE_OTHER_STACK_HOLDS_PORT="1")
        calls = "\n".join(self.docker_calls())
        self.assertIn("docker-compose.shared-host.yml", calls)

    def test_reapplication_does_not_gate_on_a_stack_that_is_still_starting(self):
        """**回帰させてはいけない検査(2026-09-04 の実測で踏んだ)。**

        `up -d --build` が返った直後、web / gateway はまだ起動途中で
        `https://localhost/` は 502 を返す。`setup-shared-host-proxy.sh` は
        既定で「適用後にベースURLへ届くこと」まで確認するので、そこで落ちる。
        しかしこれは共有プロキシの失敗ではなく、単に**まだ早い**だけである。

        到達性の判定はリトライを持つ `wait-for-stack-healthy.sh` に委ね、
        再適用そのものは接続の復旧だけを行うこと(`LBS_BASE_URL=` で確認を省く)。
        """
        r = self.run_script("--yes", FAKE_SHARED_HOST="1", FAKE_LOCALHOST_WARMUP="2")
        out = self.out(r)
        self.assertEqual(
            0,
            r.returncode,
            "構築直後の 502 で落ちている(健全性待ちのリトライへ委ねていない):\n" + out,
        )
        self.assertNotIn("共有プロキシの再適用に失敗", out)


class WiredIntoTheCleanRun(unittest.TestCase):
    """受入基準: `test:at:clean` がゼロ構築を通る / `test:at` と `test:at:fast` は変わらない。"""

    def setUp(self):
        self.pkg = json.loads(read(PACKAGE_JSON))["scripts"]

    def test_global_setup_runs_the_zero_build_script(self):
        text = read(GLOBAL_SETUP)
        self.assertIn("rebuild-acceptance-env.sh", text)

    def test_clean_run_enables_the_reset(self):
        self.assertIn("ACCEPTANCE_RESET=1", self.pkg["test:at:clean"])

    def test_fast_paths_do_not_reset(self):
        self.assertNotIn("ACCEPTANCE_RESET", self.pkg["test:at"])
        self.assertNotIn("ACCEPTANCE_RESET", self.pkg["test:at:fast"])

    def test_fast_paths_still_target_the_same_projects(self):
        self.assertIn("--project=at-destructive", self.pkg["test:at"])
        self.assertIn("--project=at-main", self.pkg["test:at:fast"])
        self.assertIn('--grep-invert "@slow"', self.pkg["test:at:fast"])

    def test_global_setup_allows_enough_time_for_a_cold_build(self):
        """ゼロ構築はイメージのビルドを含む。従来の 30 分では足りない。"""
        text = read(GLOBAL_SETUP)
        import re

        m = re.search(r"timeout:\s*([\d_]+)", text)
        self.assertIsNotNone(m, "globalSetup にタイムアウト指定が無い")
        self.assertGreaterEqual(
            int(m.group(1).replace("_", "")),
            3_600_000,
            "ゼロ構築(キャッシュ無しビルドを含む)に足りるタイムアウトになっていない",
        )


class Documentation(unittest.TestCase):
    """#965 §6: `docs/ACCEPTANCE_TESTING.md` §10 を改訂する。"""

    def setUp(self):
        self.doc = read(DOC)

    def section10(self):
        lines = self.doc.splitlines()
        start = next(i for i, l in enumerate(lines) if l.startswith("## 10."))
        end = next(
            (i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines)
        )
        return "\n".join(lines[start:end])

    def test_no_real_account_is_named_anywhere(self):
        """#965 §2/§6: 実アカウントを守るという理由づけは成り立たない。記述ごと消す。"""
        self.assertNotIn("s.tonouchi@gmail.com", self.doc)

    def test_stage_diagram_starts_with_the_teardown_and_rebuild(self):
        s = self.section10()
        self.assertIn("全撤去", s)
        self.assertRegex(s, r"全撤去.*ゼロ構築.*\n?.*at-setup")

    def test_has_a_destroyed_versus_preserved_volume_table(self):
        s = self.section10()
        self.assertIn("破棄するボリューム", s)
        self.assertIn("保全するボリューム", s)
        for v in DESTROYED_VOLUMES + PRESERVED_VOLUMES:
            with self.subTest(volume=v):
                self.assertIn(v, s, "ボリューム %s が §10 の表に無い" % v)

    def test_explains_why_comfyui_models_is_preserved(self):
        self.assertIn("モデル", self.section10())

    def test_documents_the_probe_mechanism(self):
        s = self.section10()
        self.assertIn("at-wipe-probe-", s)
        for keyword in ("kcadm", "mysql_data", "keycloak_postgres", "wordpress_sites"):
            with self.subTest(keyword=keyword):
                self.assertIn(keyword, s)

    def test_safety_section_states_the_no_valuable_account_premise(self):
        s = self.section10()
        self.assertIn("失って困るアカウントを置かない", s)
        self.assertIn("readonly", s, "引数で差し替えられないという安全装置(1)の記述が消えている")

    def test_requires_a_synthetic_provision_admin_email(self):
        s = self.section10()
        self.assertIn("E2E_PROVISION_ADMIN_EMAIL", s)
        self.assertIn("@letsblog.local", s)

    def test_records_measured_durations_with_and_without_build_cache(self):
        s = self.section10()
        self.assertIn("キャッシュ", s)
        import re

        self.assertRegex(
            s,
            r"ビルドキャッシュ(あり|なし)",
            "ビルドキャッシュあり/なしの実測が書かれていない",
        )
        self.assertTrue(
            re.search(r"\d+\s*分|\d+\s*秒", s), "実測所要時間の数値が書かれていない"
        )
        # 「あり」「なし」それぞれの行に実測値が入っていること。
        # 表の枠だけ作って埋め忘れる(あるいは仮の値を残す)のを防ぐ。
        for kind in ("ビルドキャッシュあり", "ビルドキャッシュなし"):
            row = next((l for l in s.splitlines() if kind in l), None)
            with self.subTest(kind=kind):
                self.assertIsNotNone(row, "%s の行が無い" % kind)
                self.assertRegex(
                    row, r"\d+\s*(分|秒)", "%s の行に実測値が入っていない: %s" % (kind, row)
                )
                self.assertNotIn("__", row, "仮の値が残っている: %s" % row)

    def test_explains_why_there_is_no_teardown_after_the_run(self):
        s = self.section10()
        self.assertIn("E2E_DB_CLEANUP", s)
        self.assertIn("次回実行の先頭", s)

    def test_names_the_new_script(self):
        self.assertIn("rebuild-acceptance-env.sh", self.section10())


class TeardownTargetsAllDeclaredProfiles(RebuildScriptHarness):
    """#1293: gpu プロファイル(comfyui)のコンテナも撤去の対象に含める。

    `docker-compose.yml` の comfyui は `profiles: ["gpu"]` を持つため、プロファイル指定
    無しの `compose down` では対象にならない。プロファイル込みの `lbs-comfyui` が
    Created/Exited のまま残ると、それが参照する comfyui_output の `volume rm` が失敗し、
    撤去(1/5)が途中で止まる(他のボリュームは既に破棄済みという中途半端な状態が残る)。
    """

    def test_compose_down_is_invoked_with_all_profiles(self):
        r = self.run_script("--yes")
        self.assertEqual(0, r.returncode, self.out(r))
        found = False
        for call in self.docker_calls():
            parts = call.split("\t")
            if parts[0] == "compose" and "down" in parts:
                found = True
                self.assertIn(
                    "--profile",
                    parts,
                    "compose down がプロファイル指定なしで呼ばれている(gpu プロファイルの"
                    "コンテナが撤去対象から漏れる、#1293):\n" + call,
                )
                self.assertLess(
                    parts.index("--profile"),
                    parts.index("down"),
                    "--profile が down より後ろに指定されている: " + call,
                )
        self.assertTrue(found, "compose down が呼ばれていない:\n" + "\n".join(self.docker_calls()))

    def test_dry_run_shows_profiled_services_are_included(self):
        r = self.run_script()
        out = self.out(r)
        self.assertEqual(0, r.returncode, out)
        self.assertIn("--profile", out, "ドライランがプロファイルを含めることを示していない(#1293)")
        self.assertIn(
            "comfyui", out, "ドライランが gpu プロファイルのサービス名を示していない(#1293)"
        )


class LeftoverGpuProfileContainerDoesNotBlockTeardown(RebuildScriptHarness):
    """受入基準: 残存する gpu プロファイルのコンテナ(lbs-comfyui, Created/Exited)がいても、
    撤去(1/5)が完走し comfyui_output が破棄される(#1293)。
    """

    def setUp(self):
        super().setUp()
        self.r = self.run_script("--yes", FAKE_LEFTOVER_GPU_CONTAINER="1")
        self.out_text = self.out(self.r)

    def test_succeeds(self):
        self.assertEqual(0, self.r.returncode, self.out_text)

    def test_comfyui_output_is_destroyed_and_recreated(self):
        self.assertNotEqual(
            "2020-01-01T00:00:00Z",
            self.volume_created_at("comfyui_output"),
            "残存する gpu プロファイルのコンテナのせいで comfyui_output が"
            "破棄・再作成されていない(#1293):\n" + self.out_text,
        )

    def test_protected_volume_is_not_destroyed(self):
        """受入基準: 保全対象(comfyui_models)は、gpu プロファイルの撤去に巻き込まれない。"""
        self.assertEqual(
            "2020-01-01T00:00:00Z",
            self.volume_created_at("comfyui_models"),
            "保全対象の comfyui_models まで作り直されている:\n" + self.out_text,
        )


class VolumeDestructionFailureNamesTheReferencingContainer(RebuildScriptHarness):
    """受入基準: ボリューム破棄が失敗したとき、参照しているコンテナ名をエラーに含める(#1293)。"""

    def test_error_names_the_referencing_container(self):
        r = self.run_script(
            "--yes",
            FAKE_UNDELETABLE="rabbitmq_data",
            FAKE_REFERENCING_CONTAINER="lbs-some-leftover",
        )
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, "破棄できていないのに成功した:\n" + out)
        self.assertIn("rabbitmq_data", out)
        self.assertIn(
            "lbs-some-leftover",
            out,
            "参照しているコンテナ名がエラーメッセージに含まれていない(#1293):\n" + out,
        )

    def test_falls_back_to_the_generic_message_when_no_container_is_found(self):
        """参照コンテナが見つからない失敗では、従来どおりの一般メッセージのままにする。"""
        r = self.run_script("--yes", FAKE_UNDELETABLE="rabbitmq_data")
        out = self.out(r)
        self.assertNotEqual(0, r.returncode, out)
        self.assertIn("まだ使用中の可能性があります", out)


if __name__ == "__main__":
    unittest.main()
