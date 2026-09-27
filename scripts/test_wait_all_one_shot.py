#!/usr/bin/env python3
"""`wait-for-stack-healthy.sh --all` がワンショットジョブの終了状態を正しく判定すること(#1439)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_wait_all_one_shot.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** の文書化された例外
(`scripts/test_compose_project_resolution.py` と同じ理由)。対象は開発者向けシェルスクリプトの
待機判定であり、製品の画面には現れない。受け入れテストを走らせる手段そのものが対象でもある。

## 検査の仕方

docker は PATH 上のスタブで、`ps` に対し `FAKE_PS` の内容を返す。全サービスが healthy で、
`ollama-model-init` だけ状態を変えて `--all` を実行する。あわせて、docker-compose.yml の
`restart: "no"` のサービスが全て ONE_SHOT_JOBS に載っていることを検査する(判定漏れの再発防止)。
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
WAIT_SCRIPT = os.path.join(REPO_ROOT, "scripts", "wait-for-stack-healthy.sh")
COMPOSE_FILE = os.path.join(REPO_ROOT, "docker-compose.yml")

HEALTHY_SERVICES = ["reverse-proxy", "web", "gateway", "keycloak", "mysql"]
JOB = "ollama-model-init"

FAKE_DOCKER = """#!/bin/bash
case "$*" in
  *" ps "*) cat "$FAKE_PS" ;;
esac
exit 0
"""


def ps_lines(job_state, job_exit_code, extra=()):
    rows = [
        {"Service": s, "State": "running", "Health": "healthy", "ExitCode": 0}
        for s in HEALTHY_SERVICES
    ]
    rows.append({"Service": JOB, "State": job_state, "Health": "", "ExitCode": job_exit_code})
    rows.extend(extra)
    return "\n".join(json.dumps(r) for r in rows)


def restart_no_services(compose_text):
    """docker-compose.yml の services 直下で `restart: "no"` を持つサービス名を返す。"""
    found, in_services, current = [], False, None
    for line in compose_text.splitlines():
        if re.match(r"^services:\s*$", line):
            in_services = True
            continue
        if in_services and re.match(r"^\S", line) and not line.startswith("#"):
            in_services = False
        if not in_services:
            continue
        m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*$", line)
        if m:
            current = m.group(1)
            continue
        if current and re.match(r"^    restart:\s*[\"']?no[\"']?\s*(#.*)?$", line):
            found.append(current)
    return found


class WaitAllOneShotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        bindir = os.path.join(self.tmp, "bin")
        os.makedirs(bindir)
        docker = os.path.join(bindir, "docker")
        with open(docker, "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(docker, 0o755)
        self.bindir = bindir
        self.ps = os.path.join(self.tmp, "ps.json")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_wait(self, *args, ps):
        with open(self.ps, "w", encoding="utf-8") as f:
            f.write(ps)
        env = dict(os.environ)
        env.update(
            {
                "PATH": self.bindir + os.pathsep + env["PATH"],
                "FAKE_PS": self.ps,
                "E2E_SKIP_HTTP_CHECK": "1",
                "COMPOSE_PROJECT_NAME": "lets_blog_server",
            }
        )
        return subprocess.run(
            ["bash", WAIT_SCRIPT, "--timeout", "1", *args],
            capture_output=True, text=True, timeout=60, env=env, cwd=REPO_ROOT,
        )

    def test_all_ok_when_job_exited_zero(self):
        r = self.run_wait("--all", ps=ps_lines("exited", 0))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_all_fails_when_job_exited_nonzero(self):
        r = self.run_wait("--all", ps=ps_lines("exited", 1))
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(JOB, r.stderr)

    def test_all_keeps_waiting_while_job_running(self):
        r = self.run_wait("--all", ps=ps_lines("running", 0))
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(JOB, r.stderr)

    def test_all_keeps_waiting_while_job_created(self):
        r = self.run_wait("--all", ps=ps_lines("created", 0))
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(JOB, r.stderr)

    def test_default_mode_targets_unchanged(self):
        # 既定モードは REQUIRED_SERVICES だけを見る。ジョブが exited でも判定に影響しない。
        services = (
            "reverse-proxy web gateway keycloak keycloak-postgres mysql rabbitmq "
            "identity media ai content analytics project publishing platform log-writer"
        ).split()
        rows = [
            {"Service": s, "State": "running", "Health": "healthy", "ExitCode": 0}
            for s in services
        ]
        rows.append({"Service": JOB, "State": "exited", "Health": "", "ExitCode": 1})
        r = self.run_wait(ps="\n".join(json.dumps(x) for x in rows))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        # 必須サービスが1つ落ちれば従来どおり失敗する。
        rows[1]["State"] = "exited"
        rows[1]["Health"] = "unhealthy"
        r = self.run_wait(ps="\n".join(json.dumps(x) for x in rows))
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)

    def test_every_restart_no_service_is_registered_as_one_shot(self):
        with open(COMPOSE_FILE, encoding="utf-8") as f:
            jobs = restart_no_services(f.read())
        self.assertIn(JOB, jobs, "パーサが restart: \"no\" のサービスを拾えていない")
        with open(WAIT_SCRIPT, encoding="utf-8") as f:
            m = re.search(r'^ONE_SHOT_JOBS="([^"]*)"', f.read(), re.M)
        self.assertIsNotNone(m, "wait-for-stack-healthy.sh に ONE_SHOT_JOBS=\"...\" が無い")
        registered = set(m.group(1).split())
        self.assertEqual(sorted(set(jobs) - registered), [])

    def test_restart_no_parser(self):
        text = (
            'services:\n  a:\n    restart: "no"\n  b:\n    restart: unless-stopped\n'
            "  # c:\n  c:\n    image: x\n    restart: no # 説明\nvolumes:\n  d:\n    restart: \"no\"\n"
        )
        self.assertEqual(restart_no_services(text), ["a", "c"])


if __name__ == "__main__":
    unittest.main()
