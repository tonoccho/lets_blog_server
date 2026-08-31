import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  //
  // 注意(issue #848): ここを明示的に列挙すると eslint-config-next の既定の ignore を
  // 「上書き」する。そのため、.gitignore にあるビルド/テストの生成物ディレクトリは
  // 必ずこちらにも入れること。入れ忘れると、生成物が存在する開発者の手元でだけ
  // lint が壊れる(例: Playwright を一度でも実行すると playwright-report/trace/ の
  // ミニファイ済みバンドルが react-hooks/rules-of-hooks で 257 件のエラーになった)。
  // 生成物はコミットされないため、この不整合は .gitignore 側からは気付けない。
  // E2E / 受け入れテストは React ではない(issue #929)。
  // Playwright のフィクスチャは `async ({}, use) => { await use(value); }` という形をとるが、
  // react-hooks/rules-of-hooks は識別子 `use` を React の use フックだと誤認して
  // 「コンポーネントでもカスタムフックでもない関数からフックを呼んでいる」と報告する。
  // 誤検知なので e2e/ 配下だけルールを外す。ここを広げる(src/ を含める等)と、
  // 本物のフック違反まで見逃すことになるので範囲は e2e/ に限ること。
  {
    files: ["e2e/**/*.ts"],
    rules: {
      "react-hooks/rules-of-hooks": "off",
    },
  },
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",

    // 生成物(web/.gitignore の "# testing" 節に対応。追加時は両方を更新する):
    "coverage/**",
    "test-results/**",
    "playwright-report/**",
    // Playwright のマージ用レポート。既定の出力先で、blob レポーター使用時に作られる。
    "blob-report/**",
    // Playwright のブラウザ/キャッシュ類。
    ".playwright/**",
    // playwright-bdd が .feature から生成する Playwright テスト(issue #926)。
    ".features-gen/**",
  ]),
]);

export default eslintConfig;
