#!/usr/bin/env python3
"""PlantUML・draw.io・phpMyAdmin が compose profiles のオプトイン起動であることの検証(#1741)。

    python3 -m unittest scripts.test_compose_optin_profiles

compose の設定は Web UI から到達できないため、Gherkin ではなく契約テストで表す
(CLAUDE.md → Test-First Implementation)。`docker compose config` は静的で、
コンテナもイメージの pull も要らない。

- profile を指定しない(`COMPOSE_PROFILES` 未設定)`config --services` に対象3サービスが無い
- 該当 profile(`diagram` / `db-tools`)を有効にすると含まれる
- 常時起動するサービスから対象サービスへの `depends_on` が無い
"""

import os
import subprocess
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")

PROFILE_OF = {"plantuml": "diagram", "drawio": "diagram", "phpmyadmin": "db-tools"}
OPT_IN = set(PROFILE_OF)


def config_services(*profiles):
    env = {k: v for k, v in os.environ.items() if k != "COMPOSE_PROFILES"}
    args = ["docker", "compose", "-f", COMPOSE]
    for p in profiles:
        args += ["--profile", p]
    args += ["config", "--services"]
    r = subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise AssertionError("docker compose config が失敗した:\n" + r.stdout + r.stderr)
    return set(r.stdout.split())


def depends_on_names(svc):
    deps = svc.get("depends_on") or []
    return set(deps if isinstance(deps, list) else deps.keys())


class OptInProfilesTest(unittest.TestCase):
    def test_default_excludes_opt_in_services(self):
        services = config_services()
        for name in sorted(OPT_IN):
            with self.subTest(service=name):
                self.assertNotIn(name, services, f"{name} が既定の起動に含まれている")

    def test_profile_enables_each_service(self):
        for name, profile in PROFILE_OF.items():
            with self.subTest(service=name, profile=profile):
                self.assertIn(name, config_services(profile))

    def test_profile_does_not_enable_other_group(self):
        self.assertNotIn("phpmyadmin", config_services("diagram"))
        self.assertNotIn("plantuml", config_services("db-tools"))
        self.assertNotIn("drawio", config_services("db-tools"))

    def test_always_on_services_do_not_depend_on_opt_in(self):
        with open(COMPOSE, encoding="utf-8") as f:
            services = yaml.safe_load(f)["services"]
        for name, svc in services.items():
            if name in OPT_IN or svc.get("profiles"):
                continue
            with self.subTest(service=name):
                bad = depends_on_names(svc) & OPT_IN
                self.assertFalse(bad, f"常時起動の {name} が {sorted(bad)} に depends_on している")


if __name__ == "__main__":
    unittest.main()
