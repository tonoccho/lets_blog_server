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
  // 閾値は「純ロジック(サーバーもVSCode APIも介さない層)」にファイル単位で掛ける。
  // 拡張ホストが要る層(extension.ts・各Panelの生成部・CompletionProvider)は
  // 単体テストで到達できないため数値目標を置かず、Layer 1 受け入れテスト(e2e/)と
  // MANUAL_ACCEPTANCE_CHECKLIST.md が受け持つ(docs/COVERAGE_TARGETS.md)。
  coverageThreshold: {
    // frontMatter / headingContext / config はロジックの中核のため100%を維持する。
    './src/frontMatter.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/headingContext.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/config.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    // issue #942(AT-16 Layer 2)で 90%(C1/C2)以上へ引き上げた純ロジック。
    './src/multipart.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/articleScaffold.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/webviewPanelBase.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/urlPaste.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/issueParser.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/markdownSources.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/webviewSecurity.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/jwtClaims.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/apiBaseUrl.ts': { branches: 100, functions: 100, lines: 100, statements: 100 },
    './src/cache.ts': { branches: 90, functions: 90, lines: 90, statements: 90 },
    './src/proofreadLogic.ts': { branches: 90, functions: 90, lines: 90, statements: 90 },
    './src/bodyCustomTagCompletionLogic.ts': { branches: 90, functions: 90, lines: 90, statements: 90 },
    './src/frontMatterCompletionLogic.ts': { branches: 90, functions: 90, lines: 90, statements: 90 },
    './src/downstreamServices.ts': { branches: 90, functions: 90, lines: 90, statements: 90 },
  },
};
