#!/usr/bin/env python3
"""JVM サービスの実行イメージ(eclipse-temurin:21-jre)が実際に起動することを検証する(#1108)。

    python3 scripts/smoke_jre_image.py                 # media だけ(既定)
    python3 scripts/smoke_jre_image.py ai media        # 名前で指定
    python3 scripts/smoke_jre_image.py all             # 全 JVM サービス
    python3 scripts/smoke_jre_image.py --changed       # develop との差分から対象を導出
    python3 scripts/smoke_jre_image.py --changed origin/develop

## 何を検出するか

`./gradlew test` はホストの full JDK で走るが、実行イメージは JRE で、`jdk.random` など
JRE に無いモジュールがある。JDK にしか無い API に依存すると、テストは全て緑のまま
イメージだけが起動不能になる(#1101)。ユニットテスト・カバレッジ・レビューでは構造的に
検出できないので、実イメージをビルドして `/actuator/health` が UP を返すまでを見る。

## どう動くか

実行ごとに一意の run id を採番し、専用ネットワーク・MySQL・RabbitMQ・サービスの
コンテナをすべて `lbs-smoke=<run id>` ラベル付きで作る。ホストのポートは公開せず、
固定のコンテナ名も使わない。終了時(失敗・Ctrl-C を含む)にそのラベルのものだけを消すので、
稼働中の開発環境(`lbs-*` コンテナ)には触れない。
"""

import argparse
import json
import os
import subprocess
import sys
import time
import uuid

LABEL = "lbs-smoke"
DEFAULT_TARGETS = ["media"]
# これらに触れる変更は全サービスの実行イメージに影響しうる。
SHARED_PREFIXES = ("packages/", "gradle/", "config/")
SHARED_FILES = ("build.gradle", "settings.gradle", "gradlew", "gradlew.bat")
MYSQL_IMAGE = "mysql:8.0"
RABBITMQ_IMAGE = "rabbitmq:3-alpine"
HEALTH_TIMEOUT_SEC = 240


def repo_root():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def available_services(root):
    base = os.path.join(root, "services")
    return sorted(
        name
        for name in os.listdir(base)
        if os.path.isfile(os.path.join(base, name, "Dockerfile"))
    )


def services_from_paths(paths, all_services):
    selected = set()
    for path in paths:
        if path in SHARED_FILES or path.startswith(SHARED_PREFIXES):
            return list(all_services)
        parts = path.split("/")
        if len(parts) > 2 and parts[0] == "services" and parts[1] in all_services:
            selected.add(parts[1])
    return sorted(selected)


def resolve_targets(names, all_services, changed_paths):
    if changed_paths is not None:
        return services_from_paths(changed_paths, all_services)
    if not names:
        return list(DEFAULT_TARGETS)
    if names == ["all"]:
        return list(all_services)
    unknown = [n for n in names if n not in all_services]
    if unknown:
        sys.exit(f"unknown service: {', '.join(unknown)} (available: {', '.join(all_services)})")
    return list(names)


def health_is_up(body):
    try:
        data = json.loads(body)
    except ValueError:
        return False
    return isinstance(data, dict) and data.get("status") == "UP"


def docker_env(env):
    out = dict(env)
    out.setdefault("DOCKER_CONFIG", os.path.join(env.get("HOME", ""), ".config", "docker-cli"))
    return out


def _name(run_id, part):
    return f"lbs-smoke-{run_id}-{part}"


def image_tag(run_id, service):
    return f"lbs-smoke-{run_id}/{service}:smoke"


def network_create_cmd(run_id):
    return ["docker", "network", "create", "--label", f"{LABEL}={run_id}", _name(run_id, "net")]


def _run_prefix(run_id, part):
    return [
        "docker", "run", "-d", "--label", f"{LABEL}={run_id}",
        "--name", _name(run_id, part), "--network", _name(run_id, "net"),
    ]


def mysql_run_cmd(run_id, password):
    return _run_prefix(run_id, "mysql") + [
        "-e", f"MYSQL_ROOT_PASSWORD={password}", "-e", "MYSQL_DATABASE=lbs_smoke", MYSQL_IMAGE,
    ]


def rabbitmq_run_cmd(run_id):
    return _run_prefix(run_id, "rabbitmq") + [RABBITMQ_IMAGE]


def service_run_cmd(run_id, service, image, password):
    return _run_prefix(run_id, service) + [
        "-e", f"SPRING_DATASOURCE_URL=jdbc:mysql://{_name(run_id, 'mysql')}:3306/lbs_smoke"
              "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
        "-e", "SPRING_DATASOURCE_USERNAME=root",
        "-e", f"SPRING_DATASOURCE_PASSWORD={password}",
        "-e", f"RABBITMQ_HOST={_name(run_id, 'rabbitmq')}",
        image,
    ]


def cleanup_filter(run_id):
    return f"label={LABEL}={run_id}"


def output_tail(text, lines):
    return "\n".join((text or "").splitlines()[-lines:])


def sh(cmd, env, check=True, quiet=False):
    return subprocess.run(
        cmd, env=env, check=check, text=True,
        stdout=subprocess.PIPE if quiet else None, stderr=subprocess.STDOUT if quiet else None,
    )


def cleanup(run_id, env):
    flt = cleanup_filter(run_id)
    ids = subprocess.run(["docker", "ps", "-aq", "--filter", flt], env=env, text=True, capture_output=True).stdout.split()
    if ids:
        subprocess.run(["docker", "rm", "-f", "-v", *ids], env=env, capture_output=True)
    nets = subprocess.run(["docker", "network", "ls", "-q", "--filter", flt], env=env, text=True, capture_output=True).stdout.split()
    if nets:
        subprocess.run(["docker", "network", "rm", *nets], env=env, capture_output=True)
    imgs = subprocess.run(
        ["docker", "images", "-q", f"lbs-smoke-{run_id}/*"], env=env, text=True, capture_output=True
    ).stdout.split()
    if imgs:
        subprocess.run(["docker", "rmi", "-f", *sorted(set(imgs))], env=env, capture_output=True)


def wait_healthy(container, env, timeout, probe):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", container], env=env, text=True, capture_output=True
        ).stdout.strip()
        if state != "true":
            return False, "container exited"
        res = subprocess.run(["docker", "exec", container, *probe], env=env, text=True, capture_output=True)
        if res.returncode == 0 and (probe[0] != "curl" or health_is_up(res.stdout)):
            return True, res.stdout.strip()
        time.sleep(3)
    return False, "timeout"


def smoke(services, root, env):
    run_id = uuid.uuid4().hex[:8]
    password = uuid.uuid4().hex
    results = {}
    timings = {}
    t0 = time.time()
    try:
        sh(network_create_cmd(run_id), env, quiet=True)
        sh(mysql_run_cmd(run_id, password), env, quiet=True)
        sh(rabbitmq_run_cmd(run_id), env, quiet=True)
        ok, _ = wait_healthy(
            _name(run_id, "mysql"), env, 120, ["mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", f"-p{password}"]
        )
        if not ok:
            sys.exit("MySQL did not become ready")
        ok, _ = wait_healthy(_name(run_id, "rabbitmq"), env, 120, ["rabbitmq-diagnostics", "-q", "ping"])
        if not ok:
            sys.exit("RabbitMQ did not become ready")
        print(f"dependencies ready: {time.time() - t0:.0f}s", flush=True)
        for svc in services:
            s0 = time.time()
            tag = image_tag(run_id, svc)
            print(f"[{svc}] building image", flush=True)
            try:
                sh(["docker", "build", "-q", "-f", f"services/{svc}/Dockerfile", "-t", tag, "--label", f"{LABEL}={run_id}",
                    root], env, quiet=True)
            except subprocess.CalledProcessError as e:
                print(f"[{svc}] FAIL (docker build); output tail:\n{output_tail(e.stdout, 40)}", flush=True)
                results[svc] = False
                continue
            built = time.time()
            sh(service_run_cmd(run_id, svc, tag, password), env, quiet=True)
            ok, detail = wait_healthy(
                _name(run_id, svc), env, HEALTH_TIMEOUT_SEC, ["curl", "-sf", "http://localhost:8080/actuator/health"]
            )
            timings[svc] = (built - s0, time.time() - built)
            results[svc] = ok
            if ok:
                print(f"[{svc}] PASS health={detail}", flush=True)
            else:
                print(f"[{svc}] FAIL ({detail}); container log tail:", flush=True)
                logs = subprocess.run(["docker", "logs", "--tail", "40", _name(run_id, svc)], env=env, text=True,
                                      capture_output=True)
                print(logs.stdout + logs.stderr, flush=True)
            subprocess.run(["docker", "rm", "-f", "-v", _name(run_id, svc)], env=env, capture_output=True)
    finally:
        cleanup(run_id, env)
    print(f"total: {time.time() - t0:.0f}s")
    for svc, (build, start) in timings.items():
        print(f"  {svc}: build {build:.0f}s, start-to-healthy {start:.0f}s")
    return results


def changed_paths(base, root):
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"], cwd=root, text=True, capture_output=True, check=True
    ).stdout
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=root, text=True, capture_output=True, check=True).stdout
    return out.split() + [line[3:] for line in dirty.splitlines()]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("services", nargs="*", help="service names, or 'all' (default: media)")
    parser.add_argument("--changed", nargs="?", const="origin/develop", metavar="BASE",
                        help="derive targets from git diff against BASE (default origin/develop)")
    args = parser.parse_args(argv)
    root = repo_root()
    everything = available_services(root)
    paths = changed_paths(args.changed, root) if args.changed else None
    targets = resolve_targets(args.services, everything, paths)
    if not targets:
        print("no JVM service affected; nothing to do")
        return 0
    results = smoke(targets, root, docker_env(os.environ))
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
