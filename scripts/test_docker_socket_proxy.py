#!/usr/bin/env python3
"""docker-socket-proxy の権限設定を固定する(#1587、#1399 の要件2の読み替え)。

    python3 -m unittest scripts.test_docker_socket_proxy

## なぜ必要か

tecnativa/docker-socket-proxy の既定の haproxy 設定は、先頭で
`http-request deny unless METH_GET || { env(POST) -m bool }` と全 POST を拒否してから
ALLOW_START / ALLOW_STOP を評価するため、`POST=0` のままでは start / stop も通らない。
`POST=1` にすると create / exec / restart / kill などまで通る。そこで frontend だけを差し替えた
haproxy.cfg.template を保持して read-only でマウントし、
「GET /containers/json(と /containers/<id>/json)と、POST /containers/<id>/(start|stop) だけ許し、
他はすべて拒否」にする。テンプレートの差し替えはイメージ内部のパスに依存するので、
イメージはダイジェストで固定する。

## 検査すること

1. イメージが `:latest` ではなくダイジェスト固定である。
2. テンプレートが read-only で、イメージの haproxy.cfg.template の位置へマウントされている。
3. テンプレートの frontend は許可規則だけを列挙し、環境変数(env())に依存せず、
   最後が無条件の `http-request deny` である。許可規則の正規表現は実際のパスで評価する。
"""

import json
import os
import re
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

TEMPLATE_REL = "infra/docker-socket-proxy/haproxy.cfg.template"
TEMPLATE_PATH = os.path.join(REPO_ROOT, TEMPLATE_REL)
IMAGE_TEMPLATE_TARGET = "/usr/local/etc/haproxy/haproxy.cfg.template"
DIGEST_RE = re.compile(r"^tecnativa/docker-socket-proxy@sha256:[0-9a-f]{64}$")

ALLOW_RE = re.compile(
    r"^\s*http-request allow if (?P<conds>.+)$"
)
PATH_REG_RE = re.compile(r"\{ path,url_dec -m reg -i (?P<re>\S+) \}")


def proxy_service():
    r = subprocess.run(
        ["docker", "compose", "-f", "docker-compose.yml", "config", "--format", "json"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return json.loads(r.stdout)["services"]["docker-socket-proxy"]


def frontend_lines(text):
    """`frontend dockerfrontend` セクションの、コメントと空行を除いた行を返す。"""
    lines = text.splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("frontend dockerfrontend"))
    body = []
    for l in lines[start + 1:]:
        if l and not l[0].isspace():
            break
        if l.strip() and not l.strip().startswith("#"):
            body.append(l.strip())
    return body


def allowed(rules, method, path):
    """allow 規則のいずれかが (method, path) に当てはまれば True。"""
    for conds in rules:
        wants_get = "METH_GET" in conds
        wants_post = "{ method POST }" in conds
        m = PATH_REG_RE.search(conds)
        if not m:
            return True  # パス条件のない allow は何でも通す(検査側の別のテストが落とす)
        if wants_get and method != "GET":
            continue
        if wants_post and method != "POST":
            continue
        if re.search(m.group("re"), path, re.I):
            return True
    return False


class DockerSocketProxyComposeTest(unittest.TestCase):
    def test_image_is_pinned_by_digest(self):
        image = proxy_service()["image"]
        self.assertRegex(image, DIGEST_RE)
        self.assertNotIn(":latest", image)

    def test_template_is_mounted_read_only_over_the_image_template(self):
        mounts = [v for v in proxy_service().get("volumes", []) if v.get("target") == IMAGE_TEMPLATE_TARGET]
        self.assertEqual(1, len(mounts))
        mount = mounts[0]
        self.assertEqual("bind", mount["type"])
        self.assertEqual(TEMPLATE_PATH, mount["source"])
        self.assertTrue(mount.get("read_only"))

    def test_permission_env_vars_are_not_relied_upon(self):
        env = proxy_service().get("environment", {})
        for name in ("POST", "CONTAINERS", "ALLOW_START", "ALLOW_STOP"):
            self.assertNotIn(name, env)


class HaproxyTemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(TEMPLATE_PATH, encoding="utf-8") as f:
            cls.text = f.read()
        cls.body = frontend_lines(cls.text)
        cls.allow = [l for l in cls.body if ALLOW_RE.match(l)]
        cls.rules = [ALLOW_RE.match(l).group("conds") for l in cls.allow]

    def test_frontend_ends_with_unconditional_deny(self):
        http_requests = [l for l in self.body if l.startswith("http-request")]
        self.assertEqual("http-request deny", http_requests[-1])
        self.assertEqual(1, http_requests.count("http-request deny"))

    def test_frontend_has_only_allow_rules_before_deny(self):
        http_requests = [l for l in self.body if l.startswith("http-request")]
        self.assertEqual(self.allow, http_requests[:-1])
        self.assertEqual(3, len(self.allow))

    def test_frontend_does_not_depend_on_environment_variables(self):
        self.assertNotIn("env(", "\n".join(self.body))

    def test_image_defaults_and_backends_are_kept(self):
        for needle in ("backend dockerbackend", "backend docker-events", "defaults", "errorfile 403"):
            self.assertIn(needle, self.text)
        self.assertIn("bind ${BIND_CONFIG}", self.body)

    def test_allowed_requests(self):
        for method, path in [
            ("GET", "/containers/json"),
            ("GET", "/v1.43/containers/json"),
            ("GET", "/containers/abc123_x-y.z/json"),
            ("POST", "/containers/abc123/start"),
            ("POST", "/v1.43/containers/lbs-comfyui/stop"),
        ]:
            self.assertTrue(allowed(self.rules, method, path), (method, path))

    def test_denied_requests(self):
        for method, path in [
            ("POST", "/containers/create"),
            ("DELETE", "/containers/abc123"),
            ("POST", "/containers/abc123/exec"),
            ("POST", "/containers/abc123/restart"),
            ("POST", "/containers/abc123/kill"),
            ("POST", "/containers/abc123/pause"),
            ("POST", "/images/create"),
            ("GET", "/containers/abc123/logs"),
            ("GET", "/images/json"),
            ("GET", "/info"),
            ("POST", "/containers/json"),
            ("GET", "/containers/abc123/start"),
            ("POST", "/containers/abc123/start/../exec"),
            ("POST", "/containers/abc123/startx"),
            ("POST", "/containers/abc123/stop/extra"),
        ]:
            self.assertFalse(allowed(self.rules, method, path), (method, path))

    def test_helper_frontend_lines_stops_at_next_section(self):
        text = "frontend dockerfrontend\n    bind x\n    # c\n\n    http-request deny\nbackend b\n    server s\n"
        self.assertEqual(["bind x", "http-request deny"], frontend_lines(text))

    def test_helper_allowed_without_path_condition_is_open(self):
        self.assertTrue(allowed(["METH_GET"], "GET", "/anything"))
        self.assertFalse(allowed(["METH_GET { path,url_dec -m reg -i ^/a$ }"], "POST", "/a"))
        self.assertFalse(allowed(["{ path,url_dec -m reg -i ^/a$ }"], "GET", "/b"))
        self.assertTrue(allowed(["{ path,url_dec -m reg -i ^/a$ }"], "GET", "/a"))


if __name__ == "__main__":
    unittest.main()
