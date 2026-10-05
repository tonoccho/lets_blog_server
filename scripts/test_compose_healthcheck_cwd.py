#!/usr/bin/env python3
"""ヘルスチェックの実行 cwd がバインドマウントの中を指していないことを検査する(#1000)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

Docker のヘルスチェックは `docker exec` と同じ経路で走り、その cwd はコンテナの
`WorkingDir`(compose の `working_dir`、無ければイメージの `WORKDIR`)になる。
**ヘルスチェック側から cwd を指定する手段は無い**(`docker exec -w` に当たる項目が
`healthcheck:` に存在しない)。

runc 1.4.0 は exec 時の cwd がバインドマウント経由でマウント名前空間のルート外を
指す場合、プロセスの起動そのものを拒否する。

    OCI runtime exec failed: exec failed: unable to start container process:
    current working directory is outside of container mount namespace root
    -- possible container breakout detected

拒否されるのは**プロセスが動き出す前**である。したがって `test:` の中で `cd /` を
書いても意味が無い(そのシェルが起動できない)。回避できる唯一の場所は
`working_dir` — 実行 cwd をバインドマウントの外へ出すことだけである。

#1000 では `docker-compose.e2e-stubs.yml` の `x-stub-base` が
`working_dir: /app` と `./infra/e2e-stubs:/app:ro` を併用していたため、スタブ6本が
起動直後から恒常的に unhealthy になり、`depends_on: condition: service_healthy` を
持つ `ai` / `platform` / `analytics` が起動を待ち続けた。

## なぜ実環境の再現ではなく静的検査なのか

この症状は runc のバージョンに依存する(1.4.0 で発生、1.4.3 では発生しない)。
「今のホストで healthy になる」ことは、この構成が安全であることを何も保証しない。
検査すべきなのは compose の宣言そのもの、すなわち
**ヘルスチェックを持つサービスの cwd がバインドマウントの中を指していないこと**である。

## 静的検査が届かない範囲

`working_dir` も `build:` も無いサービス(自前でビルドしない外部イメージ)の `WORKDIR` は
compose ファイルからは分からない。それらは `UNKNOWN_WORKDIR_SERVICES` に列挙し、
実機の `docker inspect` で確認した結果を根拠にしている(#1000 の調査時点で
mysql / keycloak / reverse-proxy はいずれも `WorkingDir=/` で、バインドマウントの外)。
新たに該当するサービスが増えたら、この列挙に無いものとして落ちる。
黙って検査対象から抜け落ちるのを防ぐためである。
"""

import os
import re
import unittest

import yaml  # Ubuntu の python3-yaml。compose のアンカー(<<)を解決するために使う。

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

COMPOSE_FILES = [
    "docker-compose.yml",
    "docker-compose.e2e-stubs.yml",
    "docker-compose.host-tests.yml",
    "docker-compose.shared-host.yml",
]

# `working_dir` も `build:` も持たないためコンテナの WORKDIR が compose から読めないもの。
# 「(compose ファイル, サービス名)」で挙げる。実機で確認済みであることが登録の条件。
UNKNOWN_WORKDIR_SERVICES = {
    ("docker-compose.yml", "mysql"),           # WorkingDir=/
    ("docker-compose.yml", "keycloak"),        # WorkingDir=/
    ("docker-compose.yml", "reverse-proxy"),   # WorkingDir=/
}

# e2e スタブの実体がホスト側のどこにあるか(バインドマウントの左辺)。
STUB_COMPOSE = "docker-compose.e2e-stubs.yml"

WORKDIR_LINE = re.compile(r"^\s*WORKDIR\s+(\S+)", re.IGNORECASE)


class ComposeLoader(yaml.SafeLoader):
    """compose 独自のタグ(`!override` / `!reset`)を素の値として読むローダー。

    `docker-compose.shared-host.yml` がマージ規則の指定に使っている。
    値そのものは通常の YAML なので、タグを外して中身だけを読めばよい。
    """


def _ignore_tag(loader, tag_suffix, node):
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node)
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node)
    return loader.construct_scalar(node)


ComposeLoader.add_multi_constructor("!", _ignore_tag)


def load(compose_file):
    path = os.path.join(REPO_ROOT, compose_file)
    with open(path, encoding="utf-8") as f:
        return yaml.load(f, Loader=ComposeLoader) or {}


def services(compose_file):
    return (load(compose_file).get("services") or {})


def bind_targets(service):
    """このサービスがホストのパスを持ち込んでいるコンテナ側パスの一覧。

    `volumes:` の `host:container[:opts]` のうち、左辺が `.` か `/` で始まるものだけを
    バインドマウントとみなす。名前付きボリュームは runc の検査対象ではない
    (コンテナのマウント名前空間の内側にある)。
    """
    targets = []
    for entry in service.get("volumes") or []:
        if isinstance(entry, dict):
            if entry.get("type") == "bind" and entry.get("target"):
                targets.append(entry["target"])
            continue
        parts = str(entry).split(":")
        if len(parts) < 2:
            continue
        source, target = parts[0], parts[1]
        if source.startswith(".") or source.startswith("/"):
            targets.append(target)
    return targets


def dockerfile_workdir(dockerfile_path):
    """Dockerfile の最後の WORKDIR。無ければ None。"""
    if not os.path.isfile(dockerfile_path):
        return None
    found = None
    with open(dockerfile_path, encoding="utf-8") as f:
        for line in f:
            m = WORKDIR_LINE.match(line)
            if m:
                found = m.group(1)
    return found


def build_dockerfile(service):
    """`build:` からこのサービスの Dockerfile の絶対パスを求める。無ければ None。"""
    build = service.get("build")
    if build is None:
        return None
    if isinstance(build, str):
        return os.path.join(REPO_ROOT, build, "Dockerfile")
    context = build.get("context", ".")
    dockerfile = build.get("dockerfile", "Dockerfile")
    if os.path.isabs(dockerfile):
        return dockerfile
    # compose は dockerfile を context からの相対で解決する。
    # このリポジトリは context がリポジトリルートのときリポジトリ相対で書いている
    # (services/<svc>/Dockerfile)ので、どちらでも同じ結果になる。
    return os.path.join(REPO_ROOT, context, dockerfile)


def effective_workdir(service):
    """ヘルスチェックの exec が使う cwd。compose から決まらなければ None。"""
    if service.get("working_dir"):
        return service["working_dir"]
    dockerfile = build_dockerfile(service)
    if dockerfile:
        return dockerfile_workdir(dockerfile)
    return None


def is_inside(path, mount):
    """`path` が `mount` そのもの、またはその配下か。"""
    path = os.path.normpath(path)
    mount = os.path.normpath(mount)
    return path == mount or path.startswith(mount.rstrip("/") + "/")


def healthchecked_services_with_binds():
    """(compose ファイル, サービス名, サービス定義) のうち、ヘルスチェックとバインドを両方持つもの。"""
    found = []
    for compose_file in COMPOSE_FILES:
        for name, service in services(compose_file).items():
            if not isinstance(service, dict):
                continue
            if not service.get("healthcheck"):
                continue
            if not bind_targets(service):
                continue
            found.append((compose_file, name, service))
    return found


class HealthcheckCwdIsOutsideBindMounts(unittest.TestCase):
    """#1000 の本体。ヘルスチェックの cwd がバインドマウントの中にあってはならない。"""

    def test_no_healthchecked_service_runs_with_cwd_inside_a_bind_mount(self):
        violations = []
        for compose_file, name, service in healthchecked_services_with_binds():
            workdir = effective_workdir(service)
            if workdir is None:
                continue
            for target in bind_targets(service):
                if is_inside(workdir, target):
                    violations.append(
                        "%s: %s は working_dir=%s がバインドマウント %s の中にある"
                        % (compose_file, name, workdir, target)
                    )
        self.assertEqual(
            [],
            violations,
            "ヘルスチェックの docker exec は working_dir を cwd にする。"
            "その cwd がバインドマウント経由だと runc 1.4.0 が exec を拒否し、"
            "サービスは恒常的に unhealthy になる(#1000)。"
            "working_dir をマウントの外へ出し、command を絶対パスにすること:\n  "
            + "\n  ".join(violations),
        )

    def test_every_healthchecked_bind_service_has_a_known_working_dir(self):
        """WORKDIR が compose から読めないサービスは、実機確認済みとして列挙されていること。

        列挙に無いものが現れたら、その時点で検査対象から静かに抜け落ちている。
        """
        unknown = []
        for compose_file, name, service in healthchecked_services_with_binds():
            if effective_workdir(service) is None:
                if (compose_file, name) not in UNKNOWN_WORKDIR_SERVICES:
                    unknown.append("%s: %s" % (compose_file, name))
        self.assertEqual(
            [],
            unknown,
            "WORKDIR を compose から決められないサービスがある。"
            "`docker inspect <container> --format '{{.Config.WorkingDir}}'` で確認し、"
            "バインドマウントの外にあることを確かめてから "
            "UNKNOWN_WORKDIR_SERVICES へ追加すること:\n  " + "\n  ".join(unknown),
        )


class StubCommandsResolveThroughTheMount(unittest.TestCase):
    """スタブの `command` が指すファイルが実在すること。

    `working_dir` を変えると、相対パスで書かれた `command` は別の場所を指す。
    #1000 の修正はまさに `working_dir` を動かすので、起動パスの追随漏れを
    ここで検出する(コンテナを起動しなくても分かる)。
    """

    def test_each_stub_command_points_at_an_existing_server_js(self):
        missing = []
        for name, service in services(STUB_COMPOSE).items():
            command = service.get("command")
            if not command or not isinstance(command, list):
                continue
            if command[0] != "node" or len(command) < 2:
                continue
            script = command[1]
            workdir = effective_workdir(service) or "/"
            container_path = (
                script if os.path.isabs(script) else os.path.normpath(
                    os.path.join(workdir, script)
                )
            )
            host_path = None
            for entry in service.get("volumes") or []:
                source, target = str(entry).split(":")[0], str(entry).split(":")[1]
                if is_inside(container_path, target):
                    rel = os.path.relpath(container_path, os.path.normpath(target))
                    host_path = os.path.normpath(os.path.join(REPO_ROOT, source, rel))
            if host_path is None or not os.path.isfile(host_path):
                missing.append(
                    "%s: command=%s (working_dir=%s) -> %s"
                    % (name, " ".join(command), workdir, host_path or "解決できない")
                )
        self.assertEqual(
            [],
            missing,
            "スタブの command が実在しないファイルを指している:\n  " + "\n  ".join(missing),
        )

    def test_all_stubs_are_covered(self):
        names = {
            n
            for n, s in services(STUB_COMPOSE).items()
            if isinstance(s, dict) and s.get("command")
        }
        self.assertEqual(
            {
                "llm-stub",
                "ga-stub",
                "adsense-stub",
                "brave-stub",
                "image-stub",
                "github-stub",
                "x-stub",
                # Threads API のスタブ(#1579)。
                "threads-stub",
                # ComfyUI スタブ(#1106)。GPU を持たないホストでも画像生成の経路を
                # 検証できるようにするため、7本目として追加した。
                "comfyui-stub",
                # Docker Engine API のスタブ(#1399)。演算デバイス切り替えの向き先だけを差し替える。
                "docker-engine-stub",
            },
            names,
        )


class CheckerSanity(unittest.TestCase):
    """検査そのものが空振りしていないことの確認。

    バインドの抽出や WORKDIR の解決が壊れると、違反が常に空になって
    「問題なし」を報告し続ける。#1000 が起きた状態を再現しても検出できることまで見る。
    """

    def test_the_pattern_that_broke_the_stubs_is_detected(self):
        broken = {
            "working_dir": "/app",
            "volumes": ["./infra/e2e-stubs:/app:ro"],
            "healthcheck": {"test": ["CMD", "wget", "-q", "-O", "-", "http://x/health"]},
        }
        workdir = effective_workdir(broken)
        self.assertEqual("/app", workdir)
        self.assertTrue(any(is_inside(workdir, t) for t in bind_targets(broken)))

    def test_a_workdir_outside_the_mount_is_not_flagged(self):
        fixed = {
            "working_dir": "/",
            "volumes": ["./infra/e2e-stubs:/app:ro"],
        }
        self.assertFalse(any(is_inside("/", t) for t in bind_targets(fixed)))

    def test_named_volumes_are_not_treated_as_bind_mounts(self):
        service = {"volumes": ["mysql_data:/var/lib/mysql"]}
        self.assertEqual([], bind_targets(service))

    def test_workdir_is_read_from_the_dockerfile_when_compose_is_silent(self):
        """web は compose ではなくイメージ側で WORKDIR を決めている。"""
        self.assertEqual(
            "/app", dockerfile_workdir(os.path.join(REPO_ROOT, "apps/web/Dockerfile"))
        )

    def test_the_scan_finds_the_services_it_is_meant_to_protect(self):
        found = {
            (f, n) for f, n, _ in healthchecked_services_with_binds()
        }
        for expected in [
            ("docker-compose.yml", "web"),
            (STUB_COMPOSE, "llm-stub"),
            (STUB_COMPOSE, "brave-stub"),
            (STUB_COMPOSE, "ga-stub"),
            (STUB_COMPOSE, "image-stub"),
            (STUB_COMPOSE, "adsense-stub"),
            (STUB_COMPOSE, "github-stub"),
            (STUB_COMPOSE, "comfyui-stub"),
        ]:
            with self.subTest(service=expected):
                self.assertIn(expected, found)


if __name__ == "__main__":
    unittest.main()
