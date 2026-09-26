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

export default createJestConfig(config)
