#!/usr/bin/env python3
"""`application.yml` が要求する環境変数を compose が渡しているかを検証する(#998)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ必要か

サービスの `application.yml` が `${VAR:}` と書くと、値が渡らなくても**起動は成功する**。
空文字が入るだけである。壊れるのはそのサービスが実際にその値を使ったときで、
しかもエラーは値の欠落を指さない。

#998 がまさにそれだった。`docker-compose.yml` の media ブロックにだけ
`KEYCLOAK_SERVICES_CLIENT_SECRET` が無く、media-service の Client Credentials が
Keycloak に `unauthorized_client` で拒否され、**画像生成に関わる公開APIが全滅**した。
出るエラーは「サービストークンの取得に失敗しました」で、compose の宣言漏れとは読めない。

identity と platform には同じ変数が渡っていた。**3つ書いて2つしか配線しなかった**
という種類の漏れであり、目視では見つからない。

## 何を検査するか

`services/*/src/main/resources/application.yml` が参照する環境変数のうち、
**既定値を持たないもの**と、**既定値が空のもの**を対象にする。

`${VAR:-something}` のように意味のある既定値があるものは、渡らなくても定義された
挙動になるので対象外。`${VAR}` と `${VAR:}` は「呼び出し側が渡す前提」であり、
compose 側に対応する行が無ければ契約違反である。
"""

import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
SERVICES = os.path.join(REPO_ROOT, "services")

# Spring の `${VAR}` / `${VAR:}` / `${VAR:default}`。
# 既定値が空(`:` の直後が行末か `}`)のものと、既定値が無いものを「要提供」とみなす。
PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(:([^}]*))?\}")

# 呼び出し側が渡さなくてよいもの。Spring Boot やコンテナ実行環境が供給する。
SUPPLIED_ELSEWHERE = {
    "PORT",
    "HOSTNAME",
    "HOME",
    "PATH",
    "USER",
    "PWD",
}


def required_vars(path):
    """この application.yml が呼び出し側からの提供を必要とする変数名。"""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    needed = set()
    for match in PLACEHOLDER.finditer(text):
        name, has_default, default = match.group(1), match.group(2), match.group(3)
        if name in SUPPLIED_ELSEWHERE:
            continue
        # `${VAR}` … 既定値なし / `${VAR:}` … 既定値が空。どちらも提供が要る。
        if has_default is None or default == "":
            needed.add(name)
    return needed


def compose_blocks():
    """compose のサービス名 -> そのブロックのテキスト。"""
    with open(COMPOSE, encoding="utf-8") as f:
        text = f.read()
    # `services:` 配下のインデント2のキーでブロックを切る。
    blocks = {}
    current, buf = None, []
    for line in text.splitlines():
        m = re.match(r"^  ([a-z][a-z0-9_-]*):\s*$", line)
        if m:
            if current:
                blocks[current] = "\n".join(buf)
            current, buf = m.group(1), []
            continue
        if current is not None:
            buf.append(line)
    if current:
        blocks[current] = "\n".join(buf)
    return blocks


def service_dirs():
    return sorted(
        name
        for name in os.listdir(SERVICES)
        if os.path.isfile(
            os.path.join(SERVICES, name, "src/main/resources/application.yml")
        )
    )


class ComposePassesRequiredVars(unittest.TestCase):
    """application.yml が要求する変数を compose が渡していること。"""

    def test_every_required_var_is_wired(self):
        blocks = compose_blocks()
        missing = []
        for service in service_dirs():
            path = os.path.join(SERVICES, service, "src/main/resources/application.yml")
            block = blocks.get(service)
            if block is None:
                # compose に無いサービスは、そもそも起動対象でない。ここでは扱わない。
                continue
            for var in sorted(required_vars(path)):
                if var not in block:
                    missing.append("%s: %s" % (service, var))
        self.assertEqual(
            [],
            missing,
            "application.yml が要求する変数が docker-compose.yml に渡っていない:\n  "
            + "\n  ".join(missing),
        )

    def test_the_keycloak_service_secret_reaches_every_service_that_declares_it(self):
        """#998 の具体ケース。回帰したらここで落ちる。

        まとめて検査する上のテストがあれば足りるが、この1件は**画像生成の全滅**という
        重い症状を出したので、名前を付けて残す。次に同じ変数が落ちたとき、
        失敗したテスト名から症状にたどり着けるようにするため。
        """
        var = "KEYCLOAK_SERVICES_CLIENT_SECRET"
        blocks = compose_blocks()
        declaring = [
            s
            for s in service_dirs()
            if var
            in required_vars(
                os.path.join(SERVICES, s, "src/main/resources/application.yml")
            )
        ]
        self.assertTrue(declaring, "%s を宣言するサービスが1つも無い" % var)
        for service in declaring:
            with self.subTest(service=service):
                self.assertIn(
                    var,
                    blocks.get(service, ""),
                    "compose の %s ブロックに %s が無い" % (service, var),
                )


class ParserSanity(unittest.TestCase):
    """検査そのものが空振りしていないことの確認。

    正規表現やブロック分割が壊れると、`missing` が常に空になって
    「違反なし」を報告し続ける。それは #998 が問題にした「静かに壊れる」の再導入である。
    """

    def test_service_blocks_are_found(self):
        blocks = compose_blocks()
        for expected in ("media", "identity", "platform", "gateway"):
            with self.subTest(service=expected):
                self.assertIn(expected, blocks)

    def test_placeholders_are_extracted(self):
        path = os.path.join(SERVICES, "media/src/main/resources/application.yml")
        self.assertIn("KEYCLOAK_SERVICES_CLIENT_SECRET", required_vars(path))

    def test_defaulted_vars_are_not_required(self):
        """`${VAR:something}` は既定値があるので提供不要。"""
        import tempfile

        with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False) as f:
            f.write("a: ${WITH_DEFAULT:fallback}\nb: ${NO_DEFAULT}\nc: ${EMPTY_DEFAULT:}\n")
            name = f.name
        try:
            got = required_vars(name)
            self.assertNotIn("WITH_DEFAULT", got)
            self.assertIn("NO_DEFAULT", got)
            self.assertIn("EMPTY_DEFAULT", got)
        finally:
            os.unlink(name)


if __name__ == "__main__":
    unittest.main()
