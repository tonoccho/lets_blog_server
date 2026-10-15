#!/usr/bin/env python3
"""settings.gradle の include と services/*/Dockerfile のビルドコンテキストの整合(#1857)。

settings.gradle が無条件に include するプロジェクトのディレクトリは、全サービスの
Dockerfile のビルドステージが COPY していなければならない(無いと Gradle が
"Configuring project ':plugins' without an existing directory" で落ちる)。
Dockerfile がコピーしない `plugins/*` は、存在するディレクトリだけを include する
(インデントされた=条件付きの include)ことで許される。docker/Gradle は起動しない静的検査。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import glob
import os
import re
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def unconditional_includes(text):
    """行頭から始まる `include 'a:b'` だけ(条件付きはインデントされる)。"""
    out = []
    for line in text.splitlines():
        m = re.match(r"include\s+['\"]([^'\"]+)['\"]", line)
        if m:
            out.append(m.group(1).replace(":", "/"))
    return out


def build_copies(dockerfile_text):
    """`--from` でない COPY のコピー元(最初のランタイムステージ前まで含め全て)。"""
    srcs = []
    for line in dockerfile_text.splitlines():
        m = re.match(r"COPY\s+(?!--from)(.+)", line)
        if m:
            parts = m.group(1).split()
            srcs.extend(p.rstrip("/") for p in parts[:-1] if not p.startswith("--"))
    return srcs


def covered(path, srcs):
    # ディレクトリが存在すればよい: path 配下か、path 配下のファイル(build.gradle 等)をコピーしている
    return any(path == s or path.startswith(s + "/") or s.startswith(path + "/") for s in srcs)


class SettingsGradleMatchesDockerfiles(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(REPO_ROOT, "settings.gradle"), encoding="utf-8") as f:
            self.settings = f.read()
        self.dockerfiles = sorted(glob.glob(os.path.join(REPO_ROOT, "services", "*", "Dockerfile")))

    def test_dockerfiles_found(self):
        self.assertGreaterEqual(len(self.dockerfiles), 10)

    def test_unconditional_includes_are_copied_by_every_service_dockerfile(self):
        for df in self.dockerfiles:
            with open(df, encoding="utf-8") as f:
                srcs = build_copies(f.read())
            for inc in unconditional_includes(self.settings):
                self.assertTrue(
                    covered(inc, srcs),
                    f"{os.path.relpath(df, REPO_ROOT)} は settings.gradle が無条件に include する "
                    f"'{inc}' を COPY していない",
                )

    def test_plugins_are_included_only_when_the_directory_exists(self):
        self.assertRegex(self.settings, r"(?s)plugins.*(exists|isDirectory)")


if __name__ == "__main__":
    unittest.main()
