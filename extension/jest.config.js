/**
 * 拡張のユニットテスト設定。
 *
 * 'vscode' モジュールは拡張ホストからのみ解決できるため、テストでは
 * __mocks__/vscode.ts のスタブへ差し替える(moduleNameMapper)。
 */
/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  moduleNameMapper: {
    '^vscode$': '<rootDir>/src/__mocks__/vscode.ts',
  },
  collectCoverageFrom: ['src/**/*.ts', '!src/__mocks__/**', '!src/__tests__/**'],
  coverageThreshold: {
    // frontMatter / headingContext / config はロジックの中核のため100%を維持する。
    './src/frontMatter.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/headingContext.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/config.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
  },
};
