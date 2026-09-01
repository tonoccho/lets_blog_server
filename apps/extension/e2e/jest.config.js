/**
 * Layer 1(APIレベル受け入れテスト)の jest 設定(issue #942 / AT-16)。
 *
 * 単体テスト(../jest.config.js)とは別に持つ。単体テストは外部依存を持たず数秒で終わるが、
 * こちらは起動中のスタックへ実際に接続するため、実行対象・タイムアウト・並列度が異なる。
 */
/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  rootDir: '..',
  testEnvironment: 'node',
  roots: ['<rootDir>/e2e'],
  testMatch: ['**/e2e/acceptance.test.ts'],
  moduleNameMapper: {
    '^vscode$': '<rootDir>/src/__mocks__/vscode.ts',
  },
  // WordPressの構築・公開は分単位で時間がかかる(@slow)。
  testTimeout: 600_000,
  maxWorkers: 1,
};
