/**
 * @jest-environment node
 *
 * issue #1295 レビュー差し戻し(note 8148、[IMPORTANT]ロックタイムアウト不一致):
 * `uiQuality.steps.ts`の`keyboardOnlyCreateProject`が呼ぶ`withAccountLock(E2E_ADMIN_EMAIL, ...)`は
 * ブラウザのログインフォームへ実際に値を入力してsubmitする、`loginViaKeycloak`(`helpers.ts`)と
 * 同種の対話ログインである。`loginViaKeycloak`は`{ timeoutMs: 120_000 }`を明示しているのに、
 * このステップだけが`options`省略で既定の30秒(`account-lock.ts`の`DEFAULT_LOCK_TIMEOUT_MS`)の
 * ままだった。既定の並列度でロック待ちの列が伸びたとき、この経路だけ誤ってタイムアウトしうる。
 *
 * このテストは、`keyboardOnlyCreateProject`のログイン手順が`withAccountLock`を
 * `{ timeoutMs: 120_000 }`で呼ぶことを検証する。`loginViaKeycloak`との整合を見るための
 * 配線テストであり、ロック機構そのもの(直列化・アカウント独立性)は`account-lock.test.ts`で
 * 別途検証済み。
 *
 * このテストファイルは意図的に`e2e/`直下に置く(`e2e/steps/`には置かない)。
 * `apps/web/playwright.config.ts`の`BDD_COMMON.steps`は`e2e/steps/**\/*.ts`をそのまま
 * playwright-bddのステップローダーに渡すため、`e2e/steps/`配下に`*.test.ts`を置くと
 * `bddgen`がjestのグローバル(`jest.mock`等)を含むこのファイルをステップ定義として
 * 読み込もうとして`ReferenceError: jest is not defined`で落ちる(実際に`npm run test:at:clean`
 * で再現した)。既存の`helpers-login-lock.test.ts`・`account-lock.test.ts`も同じ理由で
 * `e2e/`直下に置かれている。
 *
 * `apps/web/e2e/**` は通常jestの対象外なので、CLI引数で明示的に上書きして実行する:
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/uiQuality-keyboard-login-lock.test.ts' \
 *     e2e/uiQuality-keyboard-login-lock.test.ts
 */

// このファイルにはimport/exportが無く、そのままではTypeScriptがスクリプトとして扱い、
// 他のe2e配下のテストファイル(同様にimport/exportを持たないもの)とトップレベルの
// 識別子(withAccountLockMock等)が衝突して`npx tsc --noEmit`が壊れる。
// 明示的なexportでモジュールにし、グローバルスコープを汚さないようにする。
export {};

jest.mock('@playwright/test', () => ({
  expect: jest.fn(() => ({
    toHaveURL: jest.fn().mockResolvedValue(undefined),
    toBeVisible: jest.fn().mockResolvedValue(undefined),
  })),
}));

jest.mock('./steps/fixtures', () => ({
  Given: jest.fn(),
  When: jest.fn(),
  Then: jest.fn(),
  Step: jest.fn(),
  Before: jest.fn(),
  After: jest.fn(),
}));

const withAccountLockMock = jest.fn(
  async (_email: string, fn: () => Promise<void>, _options?: { timeoutMs?: number }) => fn()
);

jest.mock('./support', () => ({
  E2E_ADMIN_EMAIL: 'e2e-admin@letsblog.local',
  E2E_ADMIN_PASSWORD: 'e2e-admin-password',
  createFixtureProject: jest.fn(),
  deleteFixtureProject: jest.fn(),
  expect: jest.fn(() => ({
    toHaveURL: jest.fn().mockResolvedValue(undefined),
    toBeVisible: jest.fn().mockResolvedValue(undefined),
  })),
  fetchAccessToken: jest.fn(),
  loginAsAdmin: jest.fn(),
  loginAsUser: jest.fn(),
  withAccountLock: (email: string, fn: () => Promise<void>, options?: { timeoutMs?: number }) =>
    withAccountLockMock(email, fn, options),
}));

function createFakeLocator() {
  return {
    focus: jest.fn().mockResolvedValue(undefined),
    fill: jest.fn().mockResolvedValue(undefined),
    click: jest.fn().mockResolvedValue(undefined),
    isVisible: jest.fn().mockResolvedValue(false),
  };
}

function createFakePage() {
  return {
    goto: jest.fn().mockResolvedValue(undefined),
    waitForLoadState: jest.fn().mockResolvedValue(undefined),
    locator: jest.fn(() => createFakeLocator()),
    keyboard: {
      type: jest.fn().mockResolvedValue(undefined),
      press: jest.fn().mockResolvedValue(undefined),
    },
    getByText: jest.fn(() => ({})),
  };
}

// eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock後にrequireする必要がある
const { When } = require('./steps/fixtures');

// eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock後にrequireする必要がある
require('./steps/uiQuality.steps');

function findStepHandler(mockFn: jest.Mock, stepText: string) {
  const call = mockFn.mock.calls.find(([text]: [string]) => text === stepText);
  if (!call) {
    throw new Error(`ステップが見つからない: ${stepText}`);
  }
  return call[1] as (args: Record<string, unknown>) => Promise<void>;
}

describe('keyboardOnlyCreateProject(issue #1295レビュー差し戻し: ロックタイムアウトをloginViaKeycloakと揃える)', () => {
  beforeEach(() => {
    withAccountLockMock.mockClear();
  });

  test('withAccountLockをloginViaKeycloakと同じ120秒タイムアウトで呼ぶ', async () => {
    const handler = findStepHandler(When, 'キーボードのみでログインしプロジェクトを新規作成して保存する');
    const ctx: Record<string, unknown> = {};
    const page = createFakePage();

    await handler({ ctx, page });

    expect(withAccountLockMock).toHaveBeenCalledTimes(1);
    expect(withAccountLockMock.mock.calls[0][0]).toBe('e2e-admin@letsblog.local');
    expect(withAccountLockMock.mock.calls[0][2]).toEqual({ timeoutMs: 120_000 });
  });
});
