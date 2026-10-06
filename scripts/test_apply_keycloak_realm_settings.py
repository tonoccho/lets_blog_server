#!/usr/bin/env python3
"""`apply-keycloak-bruteforce-protection.sh` が `editUsernameAllowed` を稼働中レルムへ
反映することの検証(#1592)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

Web UI から到達できない運用スクリプトなので Gherkin ではなくここで表現する
(CLAUDE.md → Test-First Implementation の文書化された例外)。スクリプトを一時ディレクトリへ
複製し、偽の `curl` を PATH の先頭に置いて Keycloak を模す。偽 curl は状態ファイルに
レルム表現を持ち、PUT で置き換え、PUT の回数を数える。
"""

import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = "apply-keycloak-bruteforce-protection.sh"

FAKE_CURL = r"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
state_path = os.environ["FAKE_KC_STATE"]
state = json.load(open(state_path))
url = [a for a in args if a.startswith("http")][0]
method = args[args.index("-X") + 1] if "-X" in args else "GET"
if "openid-connect/token" in url:
    print(json.dumps({"access_token": "tok"}))
elif method == "PUT":
    state["realm"] = json.loads(args[args.index("-d") + 1])
    state["puts"] += 1
    json.dump(state, open(state_path, "w"))
    if "-w" in args:
        sys.stdout.write("204")
else:
    print(json.dumps(state["realm"]))
"""


class ApplyRealmSettingsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        os.makedirs(os.path.join(self.tmp, "scripts"))
        os.makedirs(os.path.join(self.tmp, "infra", "keycloak"))
        os.makedirs(os.path.join(self.tmp, "bin"))
        shutil.copy(os.path.join(HERE, SCRIPT), os.path.join(self.tmp, "scripts", SCRIPT))
        shutil.copy(
            os.path.join(REPO_ROOT, "infra", "keycloak", "realm-export.json"),
            os.path.join(self.tmp, "infra", "keycloak", "realm-export.json"),
        )
        with open(os.path.join(self.tmp, ".env"), "w") as f:
            f.write("KEYCLOAK_ADMIN_USERNAME=admin\nKEYCLOAK_ADMIN_PASSWORD=pw\n")
        curl = os.path.join(self.tmp, "bin", "curl")
        with open(curl, "w") as f:
            f.write(FAKE_CURL)
        os.chmod(curl, os.stat(curl).st_mode | stat.S_IXUSR)
        self.state_path = os.path.join(self.tmp, "state.json")
        self.write_state(
            {
                "realm": "letsblog",
                "editUsernameAllowed": False,
                "bruteForceProtected": True,
                "failureFactor": 99,
                "clients": [{"clientId": "letsblog-web", "secret": "keep"}],
            }
        )

    def write_state(self, realm):
        with open(self.state_path, "w") as f:
            json.dump({"realm": realm, "puts": 0}, f)

    def state(self):
        with open(self.state_path) as f:
            return json.load(f)

    def run_script(self):
        env = dict(os.environ, PATH=os.path.join(self.tmp, "bin") + ":" + os.environ["PATH"],
                   FAKE_KC_STATE=self.state_path)
        return subprocess.run(
            ["bash", os.path.join(self.tmp, "scripts", SCRIPT)],
            env=env, capture_output=True, text=True,
        )

    def test_flips_edit_username_allowed_and_keeps_other_settings(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        realm = self.state()["realm"]
        self.assertIs(realm["editUsernameAllowed"], True)
        self.assertEqual(realm["clients"], [{"clientId": "letsblog-web", "secret": "keep"}])
        self.assertEqual(realm["realm"], "letsblog")

    def test_second_run_makes_no_change(self):
        self.assertEqual(self.run_script().returncode, 0)
        puts_after_first = self.state()["puts"]
        second = self.run_script()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(self.state()["puts"], puts_after_first)
        self.assertIn("変更なし", second.stdout)

    def test_value_comes_from_realm_export_not_hardcoded(self):
        path = os.path.join(self.tmp, "infra", "keycloak", "realm-export.json")
        with open(path) as f:
            export = json.load(f)
        export["editUsernameAllowed"] = False
        with open(path, "w") as f:
            json.dump(export, f)
        self.write_state({"realm": "letsblog", "editUsernameAllowed": True})
        self.assertEqual(self.run_script().returncode, 0)
        self.assertIs(self.state()["realm"]["editUsernameAllowed"], False)


if __name__ == "__main__":
    unittest.main()
