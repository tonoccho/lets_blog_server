import type { Config } from 'jest'
import nextJest from 'next/jest.js'

// システム内部の時刻はすべてUTC(#1257)。ホストTZ(例: Pacific/Auckland)に関係なく、単体テストは
// UTCで動かす。jest のワーカーはこのプロセスの環境変数を引き継ぐので、設定の読込時に固定する。
process.env.TZ = 'UTC'

const createJestConfig = nextJest({
  dir: './',
})

const config: Config = {
  coverageProvider: 'v8',
  testEnvironment: 'jsdom',
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  // 負荷のあるホストでも落ちないようにする(#1692)。
  //
  // 既定ではワーカー数が「コア数 − 1」、1 テストのタイムアウトが 5 秒になる。無人ループや共有スタックで
  // load average が高いホストで jsdom のワーカーを 7 本走らせると CPU を奪い合い、変更と無関係な
  // スイートが 5 秒に触れて pre-commit が拒否された(#1677)。ワーカーを半分に減らして取り合いを抑え、
  // タイムアウトは 20 秒へ引き上げる。テストの skip・リトライ・アサーションの緩和はしていない。
  maxWorkers: '50%',
  testTimeout: 20000,
  // テストとして拾うファイルを明示する(issue #994)。
  //
  // 既定の testMatch は `**/?(*.)+(spec|test).[jt]s?(x)` で rootDir(apps/web)全体に及ぶ。
  // 受け入れテストは `.feature` を playwright-bdd(bddgen)で
  // `.features-gen/**/*.feature.spec.js` へ変換して実行するため、この既定のままだと
  // 一度でも bddgen を走らせた作業ツリーで jest が生成物をテストとして拾い、
  // `npm run test` が「15 suites failed」になる。単体テスト自体は全て通っているのに、
  // である(#848 で eslint が playwright-report/ を走査したのと同型)。
  //
  // 除外に `testPathIgnorePatterns` を使わないのは、その追加を .claude/hooks/guard.py が
  // 「テストの握りつぶし」としてブロックするため。ガードはこのケース(テストではなく
  // 別ランナーの生成物の除外)を区別しない。ガードを迂回するのではなく、拾う対象を
  // 列挙することで生成物が原理的に入らないようにする。
  //
  // `roots: ['<rootDir>/src']` で範囲を狭める案は採れない。next.config.ts は src/ の外に
  // あるプロダクションコードで(#984)、その単体テスト next.config.test.ts も web 直下に
  // あるため、src/ に閉じるとこの1スイートが黙って実行されなくなる。
  testMatch: [
    '<rootDir>/src/**/*.{test,spec}.{js,jsx,ts,tsx}',
    // src/ の外にあるものの単体テスト。next.config.ts のようにプロダクションコードが
    // src/ の外に置かれている場合(#984)と、package-lock.json のように apps/web 直下の
    // ファイル自体を検査するもの(dependency-advisories.test.ts、#1006)が該当する。
    '<rootDir>/*.{test,spec}.{js,jsx,ts,tsx}',
  ],
  testPathIgnorePatterns: ['/node_modules/', '/.next/', '/e2e/'],
  collectCoverageFrom: [
    'src/**/*.{js,jsx,ts,tsx}',
    // next.config.ts は src/ の外にあるがプロダクションコードである(セキュリティヘッダの
    // 設定を持つ。issue #984)。scripts/check-changed-coverage.py は「変更した
    // プロダクションコード」にカバレッジレポートが存在することを要求するため、
    // 収集対象に含める。
    'next.config.ts',
    '!src/**/*.d.ts',
    '!src/**/*.stories.{js,jsx,ts,tsx}',
    '!src/**/index.{js,jsx,ts,tsx}',
  ],
  coverageThreshold: {
    global: {
      branches: 40,
      functions: 40,
      lines: 40,
      statements: 40,
    },
  },
}

// react-markdown / remark-gfm と、その推移的依存(unified, mdast-*, micromark-*, …)は ESM のみで
// 配布される(#1566)。next/jest は `/node_modules/` 全体を transformIgnorePatterns に積み、
// こちらの設定を後ろへ足すだけなので、設定オブジェクトに書いても ESM が変換されない。
// 生成された設定を取り出し、変換を除外するパターンを ESM パッケージだけ抜いたものへ差し替える。
const ESM_PACKAGES = [
  'react-markdown',
  'remark-.*',
  'rehype-.*',
  'unified',
  'bail',
  'devlop',
  'trough',
  'vfile',
  'vfile-.*',
  'unist-.*',
  'mdast-.*',
  'hast-.*',
  'micromark',
  'micromark-.*',
  'decode-named-character-reference',
  'character-entities',
  'character-entities-.*',
  'property-information',
  'space-separated-tokens',
  'comma-separated-tokens',
  'estree-util-.*',
  'html-url-attributes',
  'is-plain-obj',
  'ccount',
  'escape-string-regexp',
  'markdown-table',
  'longest-streak',
  'zwitch',
  'stringify-entities',
  'trim-lines',
  'html-void-elements',
  'inline-style-parser',
  'style-to-.*',
].join('|')

const jestConfig = createJestConfig(config)

export default async () => {
  const resolved = await jestConfig()
  return {
    ...resolved,
    transformIgnorePatterns: [
      // next/jest が積む `/node_modules/(?!.pnpm)(?!(geist|next/dist/…)/)` は ESM を除外しないため、
      // 同じ例外(geist と next 内部)を引き継いだ上で ESM_PACKAGES を足して置き換える。
      `/node_modules/(?!\\.pnpm)(?!(?:geist|next/dist/client|next/dist/shared/lib|next/src/client|next/src/shared/lib|${ESM_PACKAGES})/)`,
      ...(resolved.transformIgnorePatterns ?? []).filter((p) => !p.startsWith('/node_modules/(?!.pnpm)')),
    ],
  }
}
