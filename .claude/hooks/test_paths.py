#!/usr/bin/env python3
"""`.claude/hooks/paths.py` の分類器の単体テスト(#983)。

`.claude/` は既存のどのテストランナー(jest / playwright-bdd / Gradle)の対象にも
なっていないため、Python 標準の unittest で回す。

    python3 -m unittest discover -s .claude/hooks -t .claude/hooks -p 'test_*.py'

分類規則そのものは `paths.py` が唯一の定義である。ここには規則を書き写さず、
代表的なパスに対する期待値だけを固定する。
"""

import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import paths  # noqa: E402


class TestCodeClassification(unittest.TestCase):
    """テストコードとして扱うパス。"""

    TEST_PATHS = [
        "services/identity/src/test/java/com/example/identity/UserServiceTest.java",
        "packages/lbs-common/src/testFixtures/java/com/example/common/Fixtures.java",
        "apps/web/e2e/features/article/publish.feature",
        "apps/web/e2e/steps/article.steps.ts",
        # #942: 拡張の受け入れテスト(jest 上の Gherkin ランナー)。
        "apps/extension/e2e/features/auth/login.feature",
        "apps/extension/e2e/steps/auth.steps.ts",
        "apps/extension/e2e/support/gherkin.ts",
        "apps/extension/e2e/acceptance.test.ts",
        "apps/extension/e2e/jest.config.js",
        "apps/web/src/components/ArticleCard.test.tsx",
        "apps/mcp-server/src/tools/designSuggestion.test.js",
        "apps/web/src/lib/api.spec.ts",
        "apps/web/src/lib/__tests__/format.ts",
        "apps/web/src/lib/__mocks__/fetch.ts",
        # テストランナーの設定・セットアップはテストコード側。
        "apps/web/jest.config.ts",
        "apps/web/jest.setup.ts",
        "apps/extension/jest.config.js",
        "apps/web/playwright.config.ts",
        # フック自身の Python 単体テスト。
        ".claude/hooks/test_paths.py",
    ]

    def test_is_test_true(self):
        for path in self.TEST_PATHS:
            with self.subTest(path=path):
                self.assertTrue(paths.is_test(path))

    def test_test_code_is_never_production(self):
        for path in self.TEST_PATHS:
            with self.subTest(path=path):
                self.assertFalse(paths.is_production(path))


class ProductionCodeClassification(unittest.TestCase):
    """プロダクションコードとして扱うパス。"""

    PRODUCTION_PATHS = [
        # 既存の実装ソースツリー(回帰確認)。
        "apps/web/src/app/[locale]/page.tsx",
        "apps/extension/src/config.ts",
        "services/identity/src/main/java/com/example/identity/UserService.java",
        "packages/lbs-common/src/main/java/com/example/common/Json.java",
        # #983 の決定: 拡張の Webview 実装。
        "apps/extension/webviews/diagramGallery.js",
        "apps/extension/webviews/plan.html",
        "apps/extension/webviews/sectionGen.css",
        "apps/extension/webviews/vendor/prism/prism-bundle.min.js",
        # #983 の決定: infra 一式。
        "infra/nginx/conf.d/default.conf",
        "infra/nginx/nginx.conf",
        "infra/keycloak/realm-export.json",
        "infra/mysql/init/01-create-service-schemas.sh",
        "infra/wordpress/provision-agent/index.php",
        "infra/e2e-stubs/llm/server.js",
        # #983 の決定: compose ファイル一式。
        "docker-compose.yml",
        "docker-compose.override.yml",
        "docker-compose.e2e-stubs.yml",
        "docker-compose.host-tests.yml",
        # 実装判断: 実行時イメージ定義。
        "apps/web/Dockerfile",
        "services/identity/Dockerfile",
        "infra/wordpress/Dockerfile",
        # #1208: Dockerfile の ENTRYPOINT/CMD から起動される apps/*/ 直下のスクリプト。
        "apps/web/docker-entrypoint.sh",
        # 実装判断: 実行時・ビルド出力を決める設定。
        "apps/web/next.config.ts",
        "apps/web/postcss.config.mjs",
        # 実装判断: 利用者に見える UI 文言とプラグインの出荷 UI。
        "apps/web/messages/ja.json",
        "apps/penpot-plugin/ui.html",
        "apps/penpot-plugin/styles.css",
        "apps/penpot-plugin/manifest.json",
    ]

    def test_is_production_true(self):
        for path in self.PRODUCTION_PATHS:
            with self.subTest(path=path):
                self.assertTrue(paths.is_production(path))

    def test_production_code_is_never_test(self):
        for path in self.PRODUCTION_PATHS:
            with self.subTest(path=path):
                self.assertFalse(paths.is_test(path))


class NeutralClassification(unittest.TestCase):
    """テストでもプロダクションでもない「中立」パス。"""

    NEUTRAL_PATHS = [
        # ルール自身(意図的に据え置き。ルールを書き換えるコミットをブロックしない)。
        ".claude/CLAUDE.md",
        ".claude/hooks/guard.py",
        ".claude/hooks/paths.py",
        ".claude/skills/work-next/SKILL.md",
        # ドキュメント・スクリプト・CI 定義・開発ツール設定。
        "docs/adr/0009-sdk-api-client-not-consumed-by-web.md",
        "README.md",
        "LICENSE",
        "scripts/git-hooks/pre-commit",
        "scripts/check-changed-coverage.py",
        "config/checkstyle.xml",
        "config/orval.config.js",
        # 依存マニフェストとビルド定義。
        "apps/web/package.json",
        "apps/web/package-lock.json",
        "apps/extension/package.json",
        "build.gradle",
        "services/identity/build.gradle",
        "settings.gradle",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradlew",
        # 型・lint 設定。
        "apps/web/tsconfig.json",
        "apps/web/eslint.config.mjs",
        "apps/web/next-env.d.ts",
        # 生成物・静的アセット。
        "openapi/content.json",
        "apps/web/public/next.svg",
        # リポジトリメタとしての dotfile。列挙(paths.py の NEUTRAL_PATTERNS)に
        # 実在するものを一つずつ対応させる。ワイルドカードで一括に中立化しない。
        ".gitignore",
        "apps/mcp-server/.gitignore",
        "apps/penpot-plugin/.gitignore",
        "apps/web/.gitignore",
        ".dockerignore",
        "apps/web/.dockerignore",
        "apps/extension/.vscodeignore",
        ".env.example",
        "apps/mcp-server/.env.example",
        "apps/web/.env.local.example",
        ".node-version",
        "apps/extension/.vscode/extensions.json",
        # プロダクション扱いのディレクトリ配下でも、ドキュメントは中立。
        "infra/keycloak/README.md",
        "apps/extension/webviews/vendor/prism/LICENSE",
        # GitLab のリポジトリ設定(#1022)。開発のための道具立てで、
        # 出荷物には入らない。`.gitlab/` は Issue / MR のテンプレート置き場。
        ".gitlab-ci.yml",
        ".gitlab/issue_templates/default.md",
        ".gitlab/merge_request_templates/default.md",
        # #1321: リポジトリ直下の setup.sh。利用者がホストで直接実行する導入
        # スクリプトで、処理は既に中立の scripts/*.sh へ委譲している。どの
        # Dockerfile / docker-compose からも呼ばれない(#1208 の
        # apps/*/docker-entrypoint.sh とは異なる)。
        "setup.sh",
        # #1452(#1321 の3回目の再発): リポジトリ直下の update.sh。setup.sh と同型
        # (update.sh:119 で既に中立の scripts/wait-for-stack-healthy.sh へ委譲、
        # ENTRYPOINT/CMD からは呼ばれない)。
        "update.sh",
    ]

    def test_neutral_is_neither(self):
        for path in self.NEUTRAL_PATHS:
            with self.subTest(path=path):
                self.assertFalse(paths.is_test(path))
                self.assertFalse(paths.is_production(path))

    def test_neutral_paths_are_declared(self):
        """中立は「取りこぼし」ではなく、列挙された意図の結果であること。"""
        for path in self.NEUTRAL_PATHS:
            with self.subTest(path=path):
                self.assertTrue(paths.is_declared_neutral(path))

    def test_declared_neutral_is_false_for_classified_paths(self):
        self.assertFalse(paths.is_declared_neutral("infra/nginx/nginx.conf"))
        self.assertFalse(paths.is_declared_neutral("apps/web/src/app/page.tsx"))
        self.assertFalse(paths.is_declared_neutral("apps/web/e2e/steps/article.steps.ts"))

    def test_declared_neutral_is_false_for_unknown_path(self):
        """列挙のどれにも当たらないパスは「宣言された中立」ではない。"""
        self.assertFalse(paths.is_declared_neutral("some/unknown/place/thing.txt"))

    def test_declared_neutral_is_false_for_unknown_dotfile(self):
        """dotfile であることは中立の根拠にならない。

        中立は「意図して分類の外に置いたもの」の列挙であって、名前が `.` で始まる
        という構文上の性質ではない。将来 dotfile が増えたら、その都度
        `NEUTRAL_PATTERNS` に足す(=分類を決める)ことを強制する。
        """
        for path in [
            ".htpasswd",
            ".some-new-tool.json",
            "apps/web/.env.production",
            ".editorconfig",
        ]:
            with self.subTest(path=path):
                self.assertFalse(paths.is_declared_neutral(path))


class TrackedDotfileEnumeration(unittest.TestCase):
    """追跡中の dotfile が、名前ごとに列挙されて中立になっていること。"""

    def test_tracked_dotfiles_are_declared_neutral(self):
        out = subprocess.run(
            ["git", "ls-files"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        dotfiles = [
            p
            for p in out.splitlines()
            if p.strip() and os.path.basename(p).startswith(".")
        ]
        self.assertTrue(dotfiles)
        not_declared = [p for p in dotfiles if not paths.is_declared_neutral(p)]
        self.assertEqual(not_declared, [])


class ClassifySplit(unittest.TestCase):
    def test_classify_splits_and_drops_neutral(self):
        tests, prod = paths.classify(
            [
                "apps/web/e2e/features/article/publish.feature",
                "infra/nginx/conf.d/default.conf",
                "docs/architecture.md",
                "apps/web/src/app/page.tsx",
                "services/identity/src/test/java/UserServiceTest.java",
                ".claude/hooks/guard.py",
            ]
        )
        self.assertEqual(
            tests,
            [
                "apps/web/e2e/features/article/publish.feature",
                "services/identity/src/test/java/UserServiceTest.java",
            ],
        )
        self.assertEqual(
            prod,
            ["infra/nginx/conf.d/default.conf", "apps/web/src/app/page.tsx"],
        )

    def test_classify_empty(self):
        self.assertEqual(paths.classify([]), ([], []))

    def test_classify_neutral_only(self):
        self.assertEqual(paths.classify(["docs/a.md", ".claude/x.py"]), ([], []))


class RepositoryExhaustiveness(unittest.TestCase):
    """追跡中の全ファイルが、テスト / プロダクション / 列挙済みの中立 のいずれかであること。

    どれにも当たらないファイルがあるということは、分類が「意図して中立」ではなく
    「黙って中立」に落ちているということ(#983 の問題そのもの)。
    """

    def test_every_tracked_file_is_classified(self):
        out = subprocess.run(
            ["git", "ls-files"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        tracked = [p for p in out.splitlines() if p.strip()]
        self.assertTrue(tracked)
        unclassified = [
            p
            for p in tracked
            if not (paths.is_test(p) or paths.is_production(p) or paths.is_declared_neutral(p))
        ]
        self.assertEqual(unclassified, [])


class AgentWorktree(unittest.TestCase):
    r"""エージェントの git worktree 配下を、実体のパスとして分類すること(#1036)。

    Claude Code のサブエージェントは `.claude/worktrees/agent-<id>/` に
    **リポジトリ全体のコピー**を作る。パスの前方一致だけで判定すると、
    `NEUTRAL_PATTERNS` の `^\.claude/` が先にマッチし、**worktree 内の
    あらゆるファイルが中立**になる。

    `.claude/` を中立にしているのは「ルールと執行機構そのものを書き換える
    コミットをブロックしないため」(#983 の利用者決定)であって、worktree は
    その意図の対象外である。巻き込まれるとフェーズ分離もテストファーストも
    テスト無効化の検査も、worktree 内では一切効かなくなる。

    正しい扱いは「worktree の接頭辞を剥がして、中身のパスとして分類する」こと。
    除外(どの分類にも入れない)ではなくこちらを選ぶ理由は、万一 worktree 内の
    ファイルがステージされたときに**本来のガードが働く**ようにするため。
    """

    WORKTREE = ".claude/worktrees/agent-a1b2c3/"

    def test_production_code_in_a_worktree_is_production(self):
        path = self.WORKTREE + "apps/web/src/app/page.tsx"
        self.assertTrue(paths.is_production(path), "worktree 内のプロダクションコードが中立扱い")
        self.assertFalse(paths.is_declared_neutral(path))

    def test_test_code_in_a_worktree_is_test(self):
        path = self.WORKTREE + "services/media/src/test/java/X.java"
        self.assertTrue(paths.is_test(path))
        self.assertFalse(paths.is_production(path))

    def test_infra_in_a_worktree_is_production(self):
        self.assertTrue(paths.is_production(self.WORKTREE + "infra/nginx/nginx.conf"))

    def test_neutral_in_a_worktree_stays_neutral(self):
        """worktree 内の `.claude/` は、実体としても中立である。"""
        self.assertTrue(paths.is_declared_neutral(self.WORKTREE + ".claude/CLAUDE.md"))
        self.assertTrue(paths.is_declared_neutral(self.WORKTREE + "docs/setup.md"))

    def test_the_worktree_directory_itself_is_not_production(self):
        """接頭辞だけのパスは、剥がすと空になる。プロダクションではない。"""
        self.assertFalse(paths.is_production(".claude/worktrees/"))
        self.assertFalse(paths.is_production(".claude/worktrees/agent-a1b2c3"))

    def test_a_real_claude_path_is_unaffected(self):
        """本物の `.claude/` は従来どおり中立のままであること(退行の防止)。"""
        for path in (".claude/CLAUDE.md", ".claude/hooks/guard.py", ".claude/skills/work-next/SKILL.md"):
            with self.subTest(path=path):
                self.assertTrue(paths.is_declared_neutral(path))
                self.assertFalse(paths.is_production(path))


class WorktreeIsIgnored(unittest.TestCase):
    """`.claude/worktrees/` が git に追跡されないこと(#1036)。

    追跡対象外(untracked)なだけでは足りない。`git add -A` で1710ファイルが
    ステージされうる。無視されていることを確かめる。
    """

    def test_git_ignores_the_worktree_directory(self):
        result = subprocess.run(
            ["git", "check-ignore", "-q", ".claude/worktrees/agent-x/apps/web/src/app/page.tsx"],
            cwd=REPO_ROOT,
            capture_output=True,
        )
        self.assertEqual(
            0, result.returncode, ".claude/worktrees/ が .gitignore で無視されていない"
        )


if __name__ == "__main__":
    unittest.main()
