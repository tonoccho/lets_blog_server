#!/usr/bin/env python3
"""`packages/api-client/src/generated/**` がコミット済み `openapi/*.json` と
1バイトも違わずに再生成できることを固定する(#1041)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ Gherkin ではないのか

`CLAUDE.md` → **Test-First Implementation** は、Web UI から到達できない受入基準を
「サービス/スクリプトレベルのテストで表現する」ことを明示的な例外として認めている。

本Issueの対象は `openapi/*.json`(生成の入力)と `packages/api-client/src/generated/**`
(orvalの生成物)自体の整合であり、`@lets-blog/api-client` はまだ `apps/web` から
実際には利用されていない(#750 未着手)。したがってブラウザから観測できる振る舞いが無く、
Web UI からの到達経路が構造的に存在しない。`scripts/test_api_client_generation_job_reexports.py`
(#1299)と同じ理由づけによる、文書化された例外である。

## 何を検知するか、何を検知しないか

このテストが検知するのは「`openapi/*.json` を更新したのに `orval` を再実行し忘れた
(または `packages/api-client/src/generated/**` を手で編集した)」という、
**仕様→生成物**の間のドリフトである。`orval@8.27.0` はネットワークもDockerも必要とせず
(`npx --yes orval@8.27.0` がキャッシュ済みの場合は完全オフラインでも動く)、通常のCIで
そのまま実行できる。

このテストが検知**できない**のは、本Issueの実際の根本原因だった「**稼働中サービス→
コミット済み仕様**」の間のドリフトである。これを機械的に検知するには、9サービスの
docker-composeスタックをまるごと起動した状態が要る。このリポジトリのCIは
lint/型チェック/単体テストのために全サービスを常時起動してはおらず(起動するのは
`at-setup`〜`at-destructive`という別建ての、E2E受け入れテスト専用の重い環境である)、
そこへ「specを取り直して差分が無いこと」という無関係な検査を割り込ませると、
受け入れテストの実行時間と安定性を損なう。加えて `packages/api-client` は #750 まで
`apps/web` から実際に呼ばれないため、稼働中サービスとの乖離が今すぐ利用者に見える
壊れ方をするわけでもない。したがって「仕様→稼働中サービス」のドリフト検知は
自動CI化せず、`docs/API_CLIENT_GENERATION.md` の手順に従って
`./scripts/generate-api-client.sh` を随時手動で回すことに委ねる(#1041 の要件4の判断)。
このテストは、その手動実行が正しく行われた結果(=コミット済み仕様と生成物が一致した
状態)を、以後のコミットで壊さないための下限を固定するものである。
"""

import filecmp
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
OPENAPI_DIR = os.path.join(REPO_ROOT, "openapi")
ORVAL_CONFIG = os.path.join(REPO_ROOT, "config", "orval.config.js")
GENERATED_DIR = os.path.join(REPO_ROOT, "packages", "api-client", "src", "generated")

# scripts/generate-api-client.sh がバージョンを固定している理由と同じ(#1006)。
# ここでも同じバージョンで再生成しないと、pin自体の検証にならない。
ORVAL_VERSION = "8.27.0"


def dir_diff(a, b):
    """2つのディレクトリツリーの差分ファイルパス(相対)の一覧を返す。空なら完全一致。"""
    diffs = []

    def walk(dcmp, rel):
        for name in dcmp.left_only:
            diffs.append(os.path.join(rel, name) + " (再生成後にのみ存在)")
        for name in dcmp.right_only:
            diffs.append(os.path.join(rel, name) + " (コミット済み側にのみ存在)")
        for name in dcmp.diff_files:
            diffs.append(os.path.join(rel, name) + " (内容が一致しない)")
        for name, sub in dcmp.subdirs.items():
            walk(sub, os.path.join(rel, name))

    walk(filecmp.dircmp(a, b), "")
    return sorted(diffs)


class GeneratedClientMatchesCommittedSpecs(unittest.TestCase):
    """受入基準相当: コミット済み `openapi/*.json` から orval で再生成した結果が、
    コミット済み `packages/api-client/src/generated/**` と1バイトも変わらないこと。

    実際の `packages/api-client/src/generated/**` は一切書き換えない
    (一時ディレクトリへ出力して比較するだけ)。
    """

    def test_regenerating_from_committed_openapi_specs_produces_no_diff(self):
        tmp = tempfile.mkdtemp(prefix="orval-sync-check-")
        try:
            tmp_config_dir = os.path.join(tmp, "config")
            tmp_openapi_dir = os.path.join(tmp, "openapi")
            tmp_generated_dir = os.path.join(tmp, "packages", "api-client", "src", "generated")
            os.makedirs(tmp_config_dir)
            os.makedirs(tmp_generated_dir, exist_ok=True)
            shutil.copy(ORVAL_CONFIG, os.path.join(tmp_config_dir, "orval.config.js"))
            shutil.copytree(OPENAPI_DIR, tmp_openapi_dir)

            result = subprocess.run(
                [
                    "npx",
                    "--yes",
                    "orval@%s" % ORVAL_VERSION,
                    "--config",
                    os.path.join(tmp_config_dir, "orval.config.js"),
                ],
                cwd=tmp,
                capture_output=True,
                text=True,
                timeout=300,
            )
            self.assertEqual(
                0,
                result.returncode,
                "orvalの実行が失敗した(npx/ネットワークを確認):\n"
                + result.stdout
                + result.stderr,
            )

            diffs = dir_diff(tmp_generated_dir, GENERATED_DIR)
            self.assertEqual(
                [],
                diffs,
                "openapi/*.json から orval@%s で再生成した結果が "
                "packages/api-client/src/generated/** と一致しない。\n"
                "openapi/*.json を更新したら ./scripts/generate-api-client.sh で\n"
                "生成物も更新すること(docs/API_CLIENT_GENERATION.md):\n  %s"
                % (ORVAL_VERSION, "\n  ".join(diffs)),
            )
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class DirDiffReportsDrift(unittest.TestCase):
    """`dir_diff` が3種類のドリフトをすべて報告すること(#1604)。
    比較自体が緩むと、同期テストが生成物のズレを見逃す。"""

    def test_reports_changed_missing_and_extra_files(self):
        tmp = tempfile.mkdtemp(prefix="dir-diff-")
        try:
            left = os.path.join(tmp, "left")
            right = os.path.join(tmp, "right")
            for d in (left, right):
                os.makedirs(os.path.join(d, "sub"))
            for d, name, body in (
                (left, "same.ts", "a"),
                (right, "same.ts", "a"),
                (left, "changed.ts", "a"),
                (right, "changed.ts", "b"),
                (left, os.path.join("sub", "only-left.ts"), "x"),
                (right, "only-right.ts", "y"),
            ):
                with open(os.path.join(d, name), "w") as f:
                    f.write(body)

            self.assertEqual(
                [
                    "changed.ts (内容が一致しない)",
                    "only-right.ts (コミット済み側にのみ存在)",
                    "sub/only-left.ts (再生成後にのみ存在)",
                ],
                dir_diff(left, right),
            )
            self.assertEqual([], dir_diff(left, left))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
