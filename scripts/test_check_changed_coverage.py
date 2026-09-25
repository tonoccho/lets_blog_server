#!/usr/bin/env python3
"""`scripts/check-changed-coverage.py` のゲート判定の単体テスト(#988)。

`scripts/` は既存のどのテストランナー(jest / playwright-bdd / Gradle)の対象にも
なっていないため、`.claude/hooks/test_paths.py` と同じく Python 標準の unittest で回す。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

検査対象は `main()` の判定そのもの。git と各カバレッジレポートの読み取りは
差し替え、「変更ファイルの一覧」と「レポートの中身」だけを入力として与える。
"""

import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
SCRIPT = os.path.join(HERE, "check-changed-coverage.py")

_spec = importlib.util.spec_from_file_location("check_changed_coverage", SCRIPT)
ccc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ccc)


def run_main(changed, coverage=None):
    """変更ファイル一覧とカバレッジ実測値を与えて main() を回し、(終了コード, 標準出力) を返す。"""
    coverage = coverage or {}
    with mock.patch.object(ccc, "run", return_value=""), mock.patch.object(
        ccc, "changed_production_files", return_value=list(changed)
    ), mock.patch.object(ccc, "jacoco_branches", return_value={}), mock.patch.object(
        ccc, "jest_branches", return_value=dict(coverage)
    ), mock.patch.object(
        sys, "argv", ["check-changed-coverage.py"]
    ):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = ccc.main()
    return code, buf.getvalue()


class UnmeasurableProductionCode(unittest.TestCase):
    """カバレッジ計測の仕組みが原理的に存在しないプロダクションコード(#988)。

    `paths.is_production()` はテストファーストとフェーズ分離のための分類であり、
    「jest / JaCoCo で計測できるか」とは別軸である。後者に当たらないファイルは
    レポートが出ないので、レポート必須の対象から外れていなければならない。
    """

    def test_extension_webviews_only_does_not_fail(self):
        code, out = run_main(["apps/extension/webviews/diagramGallery.js"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_extension_webviews_vendor_bundle_does_not_fail(self):
        code, out = run_main(["apps/extension/webviews/vendor/prism/prism-bundle.min.js"])
        self.assertEqual(code, 0, out)

    def test_infra_e2e_stubs_only_does_not_fail(self):
        code, out = run_main(["infra/e2e-stubs/llm/server.js"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_infra_e2e_stubs_shared_lib_does_not_fail(self):
        code, out = run_main(["infra/e2e-stubs/lib/stub.js"])
        self.assertEqual(code, 0, out)

    def test_api_client_generated_controller_does_not_fail(self):
        """#1228: orval が生成する packages/api-client には JaCoCo も jest も無い。

        `packages/**/src` に単純にマッチさせると、Gradle サブプロジェクトを想定した
        パターンが非JVMの生成物ツリーまで「計測可能」と誤判定し、レポートが
        存在しないため exit 1 になっていた(#1211 で実地発生)。
        """
        code, out = run_main(
            [
                "packages/api-client/src/generated/ai/project-llm-model-controller/"
                "project-llm-model-controller.ts"
            ]
        )
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_api_client_generated_schema_does_not_fail(self):
        code, out = run_main(["packages/api-client/src/generated/ai/openAPIDefinition.schemas.ts"])
        self.assertEqual(code, 0, out)

    def test_api_client_generated_is_reported_as_unmeasurable(self):
        _, out = run_main(
            [
                "packages/api-client/src/generated/ai/project-llm-model-controller/"
                "project-llm-model-controller.ts"
            ]
        )
        self.assertIn("計測対象外", out)

    def test_next_config_does_not_fail(self):
        """`apps/web/next.config.ts` も src/ の外にあり jest の計測対象ではない。"""
        code, out = run_main(["apps/web/next.config.ts"])
        self.assertEqual(code, 0, out)

    def test_unmeasurable_files_are_reported_as_skipped(self):
        """黙って消えるのではなく、計測対象外として扱った旨が出力に残ること。"""
        _, out = run_main(["infra/e2e-stubs/llm/server.js"])
        self.assertIn("infra/e2e-stubs/llm/server.js", out)
        self.assertIn("計測対象外", out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_flyway_migration_sql_does_not_fail(self):
        """#1330: `services/<svc>/src/main/resources/db/migration/**/*.sql` は
        `^services/[^/]+/src/` に字面上マッチするが、JaCoCoはコンパイル済みJavaバイトコード
        しか計測できないため、SQLファイルにレポートは原理的に存在しない。
        """
        code, out = run_main(
            [
                "services/identity/src/main/resources/db/migration/"
                "V3__make_timezone_optional_override.sql"
            ]
        )
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_flyway_migration_sql_is_reported_as_unmeasurable(self):
        _, out = run_main(
            [
                "services/identity/src/main/resources/db/migration/"
                "V3__make_timezone_optional_override.sql"
            ]
        )
        self.assertIn("計測対象外", out)

    def test_service_resource_yaml_does_not_fail(self):
        """#1379: `services/<svc>/src/main/resources/**` の設定ファイルも同じ理由で
        レポートが存在しない。#1330 は `.sql` 1拡張子だけを免除したため、
        `application.yml` に差分が入った最初のブランチ(#1190)で
        `glab mr create` のカバレッジガードが止まった。
        """
        code, out = run_main(["services/gateway/src/main/resources/application.yml"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_service_resource_yaml_is_reported_as_unmeasurable(self):
        _, out = run_main(["services/gateway/src/main/resources/application.yml"])
        self.assertIn("services/gateway/src/main/resources/application.yml", out)
        self.assertIn("計測対象外", out)

    def test_extension_ts_only_does_not_fail(self):
        """#1272: 拡張ホスト層(コマンド登録)は jest で原理的に到達できない。"""
        code, out = run_main(["apps/extension/src/extension.ts"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_extension_ts_is_reported_as_unmeasurable(self):
        """黙って捨てず、計測対象外として一覧に載ること(要件2)。"""
        _, out = run_main(["apps/extension/src/extension.ts"])
        self.assertIn("apps/extension/src/extension.ts", out)
        self.assertIn("計測対象外", out)

    def test_panel_ts_generation_does_not_fail(self):
        """#1272: `*Panel.ts`(拡張ホストの生成部)も同様に免除する。"""
        code, out = run_main(["apps/extension/src/askAiPanel.ts"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_panel_ts_is_reported_as_unmeasurable(self):
        _, out = run_main(["apps/extension/src/askAiPanel.ts"])
        self.assertIn("apps/extension/src/askAiPanel.ts", out)
        self.assertIn("計測対象外", out)

    def test_completion_provider_ts_does_not_fail(self):
        """#1272: `*CompletionProvider.ts` も拡張ホスト層として免除する。"""
        code, out = run_main(["apps/extension/src/frontMatterCompletionProvider.ts"])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_completion_provider_ts_is_reported_as_unmeasurable(self):
        _, out = run_main(["apps/extension/src/frontMatterCompletionProvider.ts"])
        self.assertIn("apps/extension/src/frontMatterCompletionProvider.ts", out)
        self.assertIn("計測対象外", out)


class MeasurableProductionCodeStillGated(unittest.TestCase):
    """回帰ガード: 計測可能なツリーの扱いは従来どおりであること。"""

    JVM = "services/identity/src/main/java/com/example/identity/UserService.java"
    WEB = "apps/web/src/lib/format.ts"

    def test_jvm_missing_report_fails(self):
        code, out = run_main([self.JVM])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)
        self.assertIn(self.JVM, out)

    def test_web_missing_report_fails(self):
        code, out = run_main([self.WEB])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)

    def test_extension_src_missing_report_fails(self):
        code, out = run_main(["apps/extension/src/proofreadLogic.ts"])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)

    def test_web_panel_tsx_missing_report_still_fails(self):
        """#1272: `.tsx` の Panel は免除対象外。`apps/web` には jest で実測される

        `*Panel.tsx` が多数あり(例: BackupPanel.tsx)、これを拡張ホスト層の
        `*Panel.ts` と取り違えて免除してはならない。
        """
        web_panel = "apps/web/src/app/admin/backup/BackupPanel.tsx"
        code, out = run_main([web_panel])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)
        self.assertIn(web_panel, out)

    def test_packages_missing_report_fails(self):
        code, out = run_main(["packages/lbs-common/src/main/java/com/example/common/Json.java"])
        self.assertEqual(code, 1)

    def test_api_client_does_not_mask_a_measurable_jvm_package(self):
        """#1228 の免除と混在しても、実際にJaCoCoが計測するJVMパッケージは従来どおり落ちること。"""
        code, out = run_main(
            [
                "packages/api-client/src/generated/ai/openAPIDefinition.schemas.ts",
                "packages/lbs-common/src/main/java/com/example/common/Json.java",
            ]
        )
        self.assertEqual(code, 1)
        self.assertIn("packages/lbs-common", out)

    def test_jvm_below_threshold_fails(self):
        code, out = run_main([self.JVM], {self.JVM: (5, 5)})
        self.assertEqual(code, 1)
        self.assertIn("下回っています", out)

    def test_web_below_threshold_fails(self):
        code, out = run_main([self.WEB], {self.WEB: (2, 8)})
        self.assertEqual(code, 1)
        self.assertIn("下回っています", out)

    def test_web_at_threshold_passes(self):
        code, out = run_main([self.WEB], {self.WEB: (1, 9)})
        self.assertEqual(code, 0, out)

    def test_branchless_measurable_file_passes(self):
        code, out = run_main([self.WEB], {self.WEB: (0, 0)})
        self.assertEqual(code, 0, out)

    def test_exemption_does_not_mask_a_measurable_file(self):
        """免除対象と混在しても、計測可能な側の不足は従来どおり落ちること。"""
        code, out = run_main(["infra/e2e-stubs/llm/server.js", self.JVM])
        self.assertEqual(code, 1)
        self.assertIn(self.JVM, out)

    def test_exempt_and_covered_measurable_file_passes(self):
        code, out = run_main(
            ["apps/extension/webviews/diagramGallery.js", self.WEB], {self.WEB: (0, 10)}
        )
        self.assertEqual(code, 0, out)


class NoProductionChange(unittest.TestCase):
    def test_no_changed_production_files_skips(self):
        code, out = run_main([])
        self.assertEqual(code, 0, out)
        self.assertIn("スキップ", out)

    def test_only_unmeasurable_changes_is_not_treated_as_a_failure(self):
        code, out = run_main(
            ["infra/e2e-stubs/llm/server.js", "apps/extension/webviews/plan.js"]
        )
        self.assertEqual(code, 0, out)

    def test_git_diff_failure_still_fails(self):
        with mock.patch.object(ccc, "run", return_value=""), mock.patch.object(
            ccc, "changed_production_files", return_value=None
        ), mock.patch.object(sys, "argv", ["check-changed-coverage.py"]):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = ccc.main()
        self.assertEqual(code, 1)
        self.assertIn("git diff", buf.getvalue())


class MeasurabilityClassifier(unittest.TestCase):
    """`is_measurable()` は「計測できるか」だけを決め、プロダクション判定を代替しない。"""

    MEASURABLE = [
        "services/identity/src/main/java/com/example/identity/UserService.java",
        "services/content/src/main/kotlin/com/example/content/Post.kt",
        "packages/lbs-common/src/main/java/com/example/common/Json.java",
        "apps/web/src/app/page.tsx",
        "apps/extension/src/config.ts",
        "apps/mcp-server/src/tools/designSuggestion.js",
        # #1272: `.tsx` の Panel は拡張ホスト層の免除の対象外(apps/web に実測対象が多数ある)。
        "apps/web/src/app/admin/backup/BackupPanel.tsx",
    ]

    UNMEASURABLE = [
        "apps/extension/webviews/diagramGallery.js",
        "apps/extension/webviews/vendor/prism/prism-bundle.min.js",
        "infra/e2e-stubs/llm/server.js",
        "infra/e2e-stubs/lib/stub.js",
        "apps/web/next.config.ts",
        "packages/api-client/src/generated/ai/openAPIDefinition.schemas.ts",
        "packages/api-client/src/generated/ai/project-llm-model-controller/"
        "project-llm-model-controller.ts",
        "packages/api-client/src/index.ts",
        # #1272: VSCode拡張ホストに依存する層(docs/COVERAGE_TARGETS.md の表)。
        "apps/extension/src/extension.ts",
        "apps/extension/src/askAiPanel.ts",
        "apps/extension/src/frontMatterCompletionProvider.ts",
        # #1330: JaCoCoはコンパイル済みJavaバイトコードしか計測できず、Flyway移行(.sql)
        # にレポートは原理的に存在しない。
        "services/identity/src/main/resources/db/migration/V3__make_timezone_optional_override.sql",
        "services/content/src/main/resources/db/migration/V5__add_index.sql",
        # #1379: 同じ理由は `.sql` 以外のリソースにもそのまま当てはまる。#1330 は拡張子を
        # 1つだけ免除したため、`application.yml` に差分が入った最初のブランチで再発した。
        "services/gateway/src/main/resources/application.yml",
        "services/platform/src/main/resources/application-test.properties",
        "services/ai/src/main/resources/logback-spring.xml",
    ]

    # #1379: JaCoCo が計測できるのはコンパイル済み JVM バイトコードだけなので、
    # `services/*/src/` 配下で計測対象になりうるのは `.java` と `.kt` **だけ**である。
    # 拡張子を1つずつ免除していく形(#1330)は、新しい拡張子の差分が初めて入るたびに
    # `glab mr create` を止めるので、一般規則として持つ。
    NON_JVM_UNDER_SERVICES_SRC = [
        "services/gateway/src/main/resources/application.yml",
        "services/gateway/src/main/resources/application.yaml",
        "services/identity/src/main/resources/messages.properties",
        "services/content/src/main/resources/logback.xml",
        "services/ai/src/main/resources/prompts/default.txt",
        "services/media/src/main/resources/static/index.html",
        "services/platform/src/main/resources/schema.json",
        "services/identity/src/main/resources/templates/mail.ftl",
    ]

    JVM_UNDER_SERVICES_SRC = [
        "services/identity/src/main/java/com/example/identity/UserService.java",
        "services/content/src/main/kotlin/com/example/content/Post.kt",
    ]

    def test_non_jvm_files_under_services_src_are_unmeasurable(self):
        """#1379: `services/*/src/` 配下で `.java`/`.kt` 以外はレポートが原理的に出ない。

        拡張子の列挙ではなく一般規則であることを、ここで守る。
        """
        for path in self.NON_JVM_UNDER_SERVICES_SRC:
            with self.subTest(path=path):
                self.assertFalse(ccc.is_measurable(path))

    def test_jvm_sources_under_services_src_stay_measurable(self):
        """#1379: 一般規則にしても `.java`/`.kt` の免除漏れを作らないこと。"""
        for path in self.JVM_UNDER_SERVICES_SRC:
            with self.subTest(path=path):
                self.assertTrue(ccc.is_measurable(path))

    def test_measurable_trees(self):
        for path in self.MEASURABLE:
            with self.subTest(path=path):
                self.assertTrue(ccc.is_measurable(path))

    def test_unmeasurable_trees(self):
        for path in self.UNMEASURABLE:
            with self.subTest(path=path):
                self.assertFalse(ccc.is_measurable(path))

    def test_unmeasurable_files_are_still_production_code(self):
        """免除はカバレッジゲートに限る。フェーズ分離とテストファーストは効いたままであること。"""
        sys.path.insert(0, os.path.join(REPO_ROOT, ".claude", "hooks"))
        import paths  # noqa: E402

        for path in self.UNMEASURABLE:
            with self.subTest(path=path):
                self.assertTrue(paths.is_production(path))


class TypeOnlyModuleDetection(unittest.TestCase):
    """`emits_no_runtime_code()` の判定(#1116)。

    判定は正規表現による当て推量ではなく、**TypeScript 自身に emit させて**
    出力が空(`export {};` だけ)であることを見る。istanbul が計測するのは
    まさにその emit 結果なので、「計測エントリが存在しない」ことの根拠になる。

    誤判定は必ず「厳しすぎる側」へ倒れること — 判定できない場合(node や
    typescript が無い、ファイルが読めない、.ts 以外)は型のみとみなさず、
    従来どおりレポートを要求する。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ccc-typeonly-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, name, source):
        path = os.path.join(self.tmp, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(source)
        return name

    def detect(self, names):
        return ccc.emits_no_runtime_code(names, root=self.tmp)

    # --- 型のみ(実行時コードが1行も残らない) ---

    def test_type_and_interface_only(self):
        rel = self.write(
            "types.ts",
            "export type A = { a: number };\nexport interface B { b: string }\n",
        )
        self.assertEqual(self.detect([rel]), {rel})

    def test_type_only_tsx(self):
        rel = self.write("props.tsx", "export interface Props { children?: unknown }\n")
        self.assertEqual(self.detect([rel]), {rel})

    def test_imports_used_only_as_types_are_erased(self):
        """型としてしか使われない import は emit から消えるので型のみと判定する。"""
        rel = self.write(
            "aliases.ts",
            "import type { Foo } from './foo';\n"
            "import * as api from './api';\n"
            "export type B = Foo & api.Bar;\n",
        )
        self.assertEqual(self.detect([rel]), {rel})

    def test_declaration_only_module(self):
        rel = self.write("ambient.ts", "declare module 'foo' { export type X = 1; }\nexport {};\n")
        self.assertEqual(self.detect([rel]), {rel})

    def test_comment_only_module(self):
        rel = self.write("doc.ts", "// 説明だけのモジュール\n/* 何も出力しない */\n")
        self.assertEqual(self.detect([rel]), {rel})

    # --- 抜け穴防止: 実行時コードを1行でも持つなら型のみではない ---

    def test_exported_const_is_runtime_code(self):
        rel = self.write("const.ts", "export type A = number;\nexport const DEFAULT: A = 1;\n")
        self.assertEqual(self.detect([rel]), set())

    def test_function_is_runtime_code(self):
        rel = self.write("fn.ts", "export interface P { p: number }\nexport function f(p: P) { return p.p; }\n")
        self.assertEqual(self.detect([rel]), set())

    def test_class_is_runtime_code(self):
        rel = self.write("cls.ts", "export class C { m() { return 1; } }\n")
        self.assertEqual(self.detect([rel]), set())

    def test_enum_is_runtime_code(self):
        rel = self.write("enum.ts", "export enum E { A, B }\n")
        self.assertEqual(self.detect([rel]), set())

    def test_const_enum_is_runtime_code(self):
        """`const enum` も isolatedModules 下では実体が emit される。"""
        rel = self.write("constenum.ts", "export const enum CE { A }\n")
        self.assertEqual(self.detect([rel]), set())

    def test_side_effect_import_is_runtime_code(self):
        """副作用 import は型を書いていても実行時の振る舞いを持つ。"""
        rel = self.write("sideeffect.ts", "import './polyfill';\nexport type A = { a: number };\n")
        self.assertEqual(self.detect([rel]), set())

    def test_declaration_file_with_runtime_neighbour(self):
        """混在した入力でも、型のみのファイルだけが返ること。"""
        types = self.write("mixed/types.ts", "export type A = number;\n")
        code = self.write("mixed/logic.ts", "export const x = 1;\n")
        self.assertEqual(self.detect([types, code]), {types})

    # --- 宣言ファイル(.d.ts / .d.mts / .d.cts): 定義上ランタイムコードを emit しえない ---

    def test_declaration_file_is_type_only(self):
        """`.d.ts` は宣言ファイルであり、tsc は JS を1バイトも出力しない。"""
        rel = self.write(
            "shapes.d.ts",
            "export type A = { a: number };\nexport interface B { b: string }\n",
        )
        self.assertEqual(self.detect([rel]), {rel})

    def test_declaration_file_augmenting_a_module_is_type_only(self):
        """`declare module` によるモジュール拡張(最も普通の .d.ts の形)。"""
        rel = self.write(
            "augment.d.ts",
            'import type { X } from "lib";\n\ndeclare module "lib" {\n  interface Y { z: X }\n}\n',
        )
        self.assertEqual(self.detect([rel]), {rel})

    def test_declaration_file_with_ambient_value_export_is_type_only(self):
        """宣言された値を re-export していても、宣言ファイルからは実体が出ない。"""
        rel = self.write("ambient-value.d.ts", "declare const x: number;\nexport { x };\n")
        self.assertEqual(self.detect([rel]), {rel})

    def test_declaration_mts_and_cts_are_type_only(self):
        mts = self.write("esm.d.mts", "export type A = number;\n")
        cts = self.write("cjs.d.cts", "export interface B { b: string }\n")
        self.assertEqual(self.detect([mts, cts]), {mts, cts})

    def test_real_repository_declaration_file(self):
        """レビュー指摘の実例。`apps/web/src/types/next-auth.d.ts` は実在の宣言ファイル。"""
        rel = "apps/web/src/types/next-auth.d.ts"
        self.assertEqual(ccc.emits_no_runtime_code([rel]), {rel})

    # --- 判定できないときは厳しい側へ倒す ---

    def test_missing_file_is_not_type_only(self):
        self.assertEqual(self.detect(["nonexistent.ts"]), set())

    def test_missing_declaration_file_is_not_type_only(self):
        """拡張子だけで無条件に通さない。読めない宣言ファイルは免除しない。"""
        self.assertEqual(self.detect(["nonexistent.d.ts"]), set())

    def test_unparsable_declaration_file_is_not_type_only(self):
        """構文が壊れている宣言ファイルも免除しない(パースできることを確かめる)。"""
        rel = self.write("broken.d.ts", "export interface A {\n  b: ;;; @@@\n")
        self.assertEqual(self.detect([rel]), set())

    def test_javascript_is_never_type_only(self):
        rel = self.write("empty.js", "")
        self.assertEqual(self.detect([rel]), set())

    def test_node_unavailable_is_not_type_only(self):
        rel = self.write("types.ts", "export type A = number;\n")
        with mock.patch.object(ccc.subprocess, "run", side_effect=FileNotFoundError("node")):
            self.assertEqual(self.detect([rel]), set())

    def test_unparsable_output_is_not_type_only(self):
        rel = self.write("types.ts", "export type A = number;\n")
        fake = mock.Mock(returncode=0, stdout="typescript を解決できません", stderr="")
        with mock.patch.object(ccc.subprocess, "run", return_value=fake):
            self.assertEqual(self.detect([rel]), set())

    def test_helper_failure_is_not_type_only(self):
        rel = self.write("types.ts", "export type A = number;\n")
        fake = mock.Mock(returncode=3, stdout="", stderr="Cannot find module 'typescript'")
        with mock.patch.object(ccc.subprocess, "run", return_value=fake):
            self.assertEqual(self.detect([rel]), set())

    def test_timeout_is_bounded_well_inside_the_guard_timeout(self):
        """判定は `guard.py` の外側タイムアウト(120秒)より十分早く諦めること。

        外側が先に切れると `check_pr_coverage()` の `subprocess.run` が
        `TimeoutExpired` を投げ、フックが「判定できなかった」ではなく
        例外で終わる。内側が必ず先に諦めれば、厳しい側(=レポートを要求する)へ
        倒れた通常の失敗として報告できる。
        """
        rel = self.write("types.ts", "export type A = number;\n")
        captured = {}

        def fake_run(*args, **kwargs):
            captured.update(kwargs)
            return mock.Mock(returncode=0, stdout="[]", stderr="")

        with mock.patch.object(ccc.subprocess, "run", side_effect=fake_run):
            self.detect([rel])
        self.assertLessEqual(captured.get("timeout", 10**9), 60)

    def test_timeout_is_not_type_only(self):
        rel = self.write("types.ts", "export type A = number;\n")
        expired = ccc.subprocess.TimeoutExpired(cmd="node", timeout=1)
        with mock.patch.object(ccc.subprocess, "run", side_effect=expired):
            self.assertEqual(self.detect([rel]), set())

    def test_non_list_output_is_not_type_only(self):
        """JSON ではあるが配列でない応答も信用しない。"""
        rel = self.write("types.ts", "export type A = number;\n")
        fake = mock.Mock(returncode=0, stdout='{"types.ts": true}', stderr="")
        with mock.patch.object(ccc.subprocess, "run", return_value=fake):
            self.assertEqual(self.detect([rel]), set())

    def test_paths_outside_the_request_are_ignored(self):
        """問い合わせていないパスが返ってきても採用しない。"""
        rel = self.write("types.ts", "export type A = number;\n")
        fake = mock.Mock(returncode=0, stdout='["types.ts", "other.ts"]', stderr="")
        with mock.patch.object(ccc.subprocess, "run", return_value=fake):
            self.assertEqual(self.detect([rel]), {rel})

    def test_resolve_dirs_without_an_apps_directory(self):
        """apps/ が無いツリーでも候補ディレクトリの算出が壊れないこと。"""
        with mock.patch.object(ccc, "ROOT", os.path.join(self.tmp, "no-such-root")):
            dirs = ccc.typescript_resolve_dirs(self.tmp)
        self.assertEqual(dirs, [self.tmp])

    def test_resolve_dirs_lists_each_app(self):
        dirs = ccc.typescript_resolve_dirs(REPO_ROOT)
        self.assertIn(os.path.join(REPO_ROOT, "apps", "extension"), dirs)

    def test_no_candidates_does_not_invoke_node(self):
        """計測可能な .ts が無いときは判定を起動しない(ゲートの常用経路を遅くしない)。"""
        with mock.patch.object(ccc.subprocess, "run", side_effect=AssertionError("node を起動した")):
            self.assertEqual(ccc.emits_no_runtime_code([], root=self.tmp), set())
            self.assertEqual(ccc.emits_no_runtime_code(["a.java"], root=self.tmp), set())

    def test_real_repository_type_only_module(self):
        """#1116 の実例。`apps/extension/src/webviewMessages.ts` は全 export が型。"""
        rel = "apps/extension/src/webviewMessages.ts"
        self.assertEqual(ccc.emits_no_runtime_code([rel]), {rel})

    def test_real_repository_runtime_module(self):
        """同じツリーの実行時コードは型のみと判定されないこと。"""
        rel = "apps/extension/src/proofreadLogic.ts"
        self.assertEqual(ccc.emits_no_runtime_code([rel]), set())


class TypeOnlyModuleGate(unittest.TestCase):
    """型のみのモジュールに対するゲートの振る舞い(#1116)。"""

    TYPE_ONLY = "apps/extension/src/webviewMessages.ts"
    RUNTIME = "apps/extension/src/proofreadLogic.ts"
    DECLARATION = "apps/web/src/types/next-auth.d.ts"

    def test_type_only_module_without_report_passes(self):
        code, out = run_main([self.TYPE_ONLY])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)

    def test_type_only_module_is_reported_not_silently_dropped(self):
        _, out = run_main([self.TYPE_ONLY])
        self.assertIn(self.TYPE_ONLY, out)
        self.assertIn("型定義のみ", out)

    def test_type_only_report_is_distinct_from_the_unmeasurable_section(self):
        """`apps/*/webviews/` のような「計測できないが検証は必要」な層と混同しないこと。"""
        _, out = run_main([self.TYPE_ONLY])
        self.assertNotIn("受け入れテストで検証する層", out)

    def test_runtime_module_without_report_still_fails(self):
        """抜け穴防止の要。実行時コードを持つファイルはレポート無しで落ちること。"""
        code, out = run_main([self.RUNTIME])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)
        self.assertIn(self.RUNTIME, out)
        self.assertNotIn("型定義のみ", out)

    def test_type_only_does_not_mask_a_runtime_module(self):
        code, out = run_main([self.TYPE_ONLY, self.RUNTIME])
        self.assertEqual(code, 1)
        self.assertIn(self.RUNTIME, out)

    def test_type_only_path_with_a_report_is_still_measured(self):
        """レポートに現れているなら免除しない(閾値判定は従来どおり)。"""
        code, out = run_main([self.TYPE_ONLY], {self.TYPE_ONLY: (2, 8)})
        self.assertEqual(code, 1)
        self.assertIn("下回っています", out)

    def test_declaration_file_without_report_passes(self):
        """実在の `.d.ts` を変更しただけのブランチがゲートを通ること。"""
        code, out = run_main([self.DECLARATION])
        self.assertEqual(code, 0, out)
        self.assertNotIn("カバレッジレポートが見つかりません", out)
        self.assertIn(self.DECLARATION, out)
        self.assertIn("型定義のみ", out)

    def test_declaration_file_does_not_mask_a_runtime_module(self):
        code, out = run_main([self.DECLARATION, self.RUNTIME])
        self.assertEqual(code, 1)
        self.assertIn("カバレッジレポートが見つかりません", out)
        self.assertIn(self.RUNTIME, out)

    def test_only_type_only_changes_skips_the_gate(self):
        code, out = run_main([self.TYPE_ONLY, "apps/extension/webviews/diagramGallery.js"])
        self.assertEqual(code, 0, out)
        self.assertIn("スキップ", out)


class ChangedProductionFilesFiltering(unittest.TestCase):
    """`changed_production_files()`: 収集は `paths.is_production()` だけで決めること(#1072)。

    以前は `paths.is_production()` に加えて拡張子ホワイトリスト
    (`.java`/`.kt`/`.ts`/`.tsx`/`.js`/`.jsx`)でも絞り込んでいたため、
    `infra/` 配下の `.php` や `docker-compose*.yml` のような、拡張子ホワイトリストに
    無いプロダクションコードが計測対象外リストにすら現れず、「変更されたプロダクション
    コードはありません」と誤って報告されていた(#988 の計測対象外レポートに一度も
    載らない)。拡張子による絞り込みは `is_measurable()` が既に正しく担っており、
    ここで重複させる理由がない。
    """

    def fake_run(self, diff_output):
        def _run(args):
            if args[1] == "merge-base":
                return "deadbeef"
            if args[1] == "diff":
                return diff_output
            return ""

        return _run

    def test_php_file_under_infra_is_collected(self):
        """`.php` は拡張子ホワイトリストに無いが、`infra/` はプロダクション判定される。"""
        diff = "infra/wordpress/provision-agent/index.php\n"
        with mock.patch.object(ccc, "run", side_effect=self.fake_run(diff)):
            result = ccc.changed_production_files("origin/develop")
        self.assertIn("infra/wordpress/provision-agent/index.php", result)

    def test_docker_compose_yaml_is_collected(self):
        """`docker-compose.yml` も同様に拡張子ホワイトリストの外にある。"""
        diff = "docker-compose.yml\n"
        with mock.patch.object(ccc, "run", side_effect=self.fake_run(diff)):
            result = ccc.changed_production_files("origin/develop")
        self.assertIn("docker-compose.yml", result)

    def test_non_production_file_is_still_excluded(self):
        """フィルタを緩めても、プロダクションでないファイルは従来どおり含めない。"""
        diff = "docs/README.md\n"
        with mock.patch.object(ccc, "run", side_effect=self.fake_run(diff)):
            result = ccc.changed_production_files("origin/develop")
        self.assertEqual(result, [])

    def test_infra_php_change_is_reported_as_unmeasurable_not_skipped(self):
        """main() 全体で見たとき、`.php` の変更が「変更なし」ではなく計測対象外扱いになること。"""
        diff = "infra/wordpress/provision-agent/index.php\n"
        with mock.patch.object(ccc, "run", side_effect=self.fake_run(diff)), mock.patch.object(
            sys, "argv", ["check-changed-coverage.py"]
        ):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = ccc.main()
        out = buf.getvalue()
        self.assertEqual(code, 0, out)
        self.assertIn("infra/wordpress/provision-agent/index.php", out)
        self.assertIn("計測対象外", out)
        self.assertNotIn("変更されたプロダクションコードはありません", out)


class ChangedLinesByFile(unittest.TestCase):
    """`changed_lines_by_file()`: git diff の hunk から追加/変更行番号を得る(#1230)。"""

    def test_parses_added_line_ranges_and_skips_pure_deletions(self):
        diff_text = (
            "diff --git a/apps/web/src/lib/format.ts b/apps/web/src/lib/format.ts\n"
            "index 111..222 100644\n"
            "--- a/apps/web/src/lib/format.ts\n"
            "+++ b/apps/web/src/lib/format.ts\n"
            "@@ -10,0 +11,2 @@ function foo() {\n"
            "+  const a = 1;\n"
            "+  const b = 2;\n"
            "@@ -30,2 +33,0 @@ function bar() {\n"
            "-  const c = 3;\n"
            "-  const d = 4;\n"
        )

        def fake_run(args):
            if args[:2] == ["git", "diff"]:
                return diff_text
            return ""

        with mock.patch.object(ccc, "run", side_effect=fake_run):
            result = ccc.changed_lines_by_file("origin/develop")
        self.assertEqual(result["apps/web/src/lib/format.ts"], {11, 12})

    def test_single_line_hunk_without_a_count(self):
        diff_text = (
            "diff --git a/apps/web/src/lib/format.ts b/apps/web/src/lib/format.ts\n"
            "--- a/apps/web/src/lib/format.ts\n"
            "+++ b/apps/web/src/lib/format.ts\n"
            "@@ -5 +5 @@ function foo() {\n"
            "-  const a = 1;\n"
            "+  const a = 2;\n"
        )

        def fake_run(args):
            if args[:2] == ["git", "diff"]:
                return diff_text
            return ""

        with mock.patch.object(ccc, "run", side_effect=fake_run):
            result = ccc.changed_lines_by_file("origin/develop")
        self.assertEqual(result["apps/web/src/lib/format.ts"], {5})

    def test_diff_failure_returns_empty_map(self):
        with mock.patch.object(ccc, "run", return_value=None):
            result = ccc.changed_lines_by_file("origin/develop")
        self.assertEqual(result, {})


class LineScopedJacocoBranches(unittest.TestCase):
    """`jacoco_branches()`: 行番号でJaCoCoの`<line>`要素を絞り込む(#1230)。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ccc-linescope-jacoco-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.rel = "services/identity/src/main/java/com/example/identity/UserService.java"
        xml_dir = os.path.join(self.tmp, "services", "identity", "build", "reports", "jacoco", "test")
        os.makedirs(xml_dir)
        with open(os.path.join(xml_dir, "jacocoTestReport.xml"), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0"?>\n'
                "<report>\n"
                '  <package name="com/example/identity">\n'
                '    <sourcefile name="UserService.java">\n'
                # このIssueが変更した行(10行目): 完全カバー
                '      <line nr="10" mi="0" ci="2" mb="0" cb="2"/>\n'
                # 変更していない既存のレガシー行(50行目): 未カバー
                '      <line nr="50" mi="4" ci="0" mb="4" cb="0"/>\n'
                "    </sourcefile>\n"
                '    <counter type="BRANCH" missed="4" covered="2"/>\n'
                "  </package>\n"
                "</report>\n"
            )

    def test_only_changed_line_branches_are_counted(self):
        with mock.patch.object(ccc, "ROOT", self.tmp):
            result = ccc.jacoco_branches({self.rel: {10}})
        self.assertEqual(result[self.rel], (0, 2))

    def test_unchanged_line_branches_are_excluded(self):
        with mock.patch.object(ccc, "ROOT", self.tmp):
            result = ccc.jacoco_branches({self.rel: {50}})
        self.assertEqual(result[self.rel], (4, 0))

    def test_file_with_no_changed_line_reports_zero_branches(self):
        """変更行が分岐を持たない場合は (0, 0) になり、上位で除外できること。"""
        with mock.patch.object(ccc, "ROOT", self.tmp):
            result = ccc.jacoco_branches({self.rel: {999}})
        self.assertEqual(result[self.rel], (0, 0))


class LineScopedJestBranches(unittest.TestCase):
    """`jest_branches()`: branchMap の座標で変更行に絞り込む(#1230)。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ccc-linescope-jest-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.rel = "apps/web/src/lib/format.ts"
        self.src_path = os.path.join(self.tmp, *self.rel.split("/"))
        os.makedirs(os.path.dirname(self.src_path))
        with open(self.src_path, "w", encoding="utf-8") as f:
            f.write("// fixture\n")
        coverage_dir = os.path.join(self.tmp, "apps", "web", "coverage")
        os.makedirs(coverage_dir)
        data = {
            self.src_path: {
                "branchMap": {
                    "0": {
                        "loc": {"start": {"line": 5}, "end": {"line": 5}},
                        "locations": [
                            {"start": {"line": 5}, "end": {"line": 5}},
                            {"start": {"line": 5}, "end": {"line": 5}},
                        ],
                    },
                    "1": {
                        "loc": {"start": {"line": 50}, "end": {"line": 50}},
                        "locations": [
                            {"start": {"line": 50}, "end": {"line": 50}},
                            {"start": {"line": 50}, "end": {"line": 50}},
                        ],
                    },
                },
                "b": {"0": [1, 0], "1": [0, 0]},
            }
        }
        with open(os.path.join(coverage_dir, "coverage-final.json"), "w", encoding="utf-8") as f:
            json.dump(data, f)

    def test_only_branches_on_changed_lines_are_counted(self):
        with mock.patch.object(ccc, "ROOT", self.tmp):
            result = ccc.jest_branches({self.rel: {5}})
        self.assertEqual(result[self.rel], (1, 1))

    def test_branches_outside_changed_lines_are_excluded(self):
        """変更行がどの branchMap 座標とも重ならない場合は (0, 0) になること。"""
        with mock.patch.object(ccc, "ROOT", self.tmp):
            result = ccc.jest_branches({self.rel: {999}})
        self.assertEqual(result[self.rel], (0, 0))


class LineScopedGateEndToEnd(unittest.TestCase):
    """AC2/AC3: 変更行だけでカバレッジを判定する(#1230)。

    #1063 で実際に起きた事象そのもの: ファイル全体では基準を下回っていても、
    このIssueが変更した行が100%カバーなら通ること。逆に変更した行自体が
    カバーされていなければ、他の行がどれだけカバーされていても落ちること。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ccc-e2e-linescope-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.rel = "services/identity/src/main/java/com/example/identity/UserService.java"
        xml_dir = os.path.join(self.tmp, "services", "identity", "build", "reports", "jacoco", "test")
        os.makedirs(xml_dir)
        with open(os.path.join(xml_dir, "jacocoTestReport.xml"), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0"?>\n'
                "<report>\n"
                '  <package name="com/example/identity">\n'
                '    <sourcefile name="UserService.java">\n'
                '      <line nr="10" mi="0" ci="2" mb="0" cb="2"/>\n'
                '      <line nr="50" mi="4" ci="0" mb="4" cb="0"/>\n'
                "    </sourcefile>\n"
                '    <counter type="BRANCH" missed="4" covered="2"/>\n'
                "  </package>\n"
                "</report>\n"
            )

    def diff_text(self, changed_line):
        return (
            "diff --git a/%s b/%s\n" % (self.rel, self.rel)
            + "index 111..222 100644\n"
            + "--- a/%s\n" % self.rel
            + "+++ b/%s\n" % self.rel
            + "@@ -%d,0 +%d,1 @@ void m() {\n" % (changed_line - 1, changed_line)
            + "+  doSomething();\n"
        )

    def run_gate(self, changed_line):
        def fake_run(args):
            if args[:2] == ["git", "diff"]:
                return self.diff_text(changed_line)
            return ""

        with mock.patch.object(ccc, "ROOT", self.tmp), mock.patch.object(
            ccc, "run", side_effect=fake_run
        ), mock.patch.object(
            ccc, "changed_production_files", return_value=[self.rel]
        ), mock.patch.object(sys, "argv", ["check-changed-coverage.py"]):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = ccc.main()
        return code, buf.getvalue()

    def test_changed_line_fully_covered_passes_despite_low_file_wide_coverage(self):
        """全体は2/6=33%だが、変更したのは10行目(2/2)だけなので基準を満たす。"""
        code, out = self.run_gate(changed_line=10)
        self.assertEqual(code, 0, out)

    def test_changed_line_uncovered_fails_even_if_other_lines_are_covered(self):
        code, out = self.run_gate(changed_line=50)
        self.assertEqual(code, 1, out)
        self.assertIn("下回っています", out)


if __name__ == "__main__":
    unittest.main()
