/**
 * @jest-environment node
 *
 * issue #1295 レビュー差し戻し(note 8148、[IMPORTANT]ロックタイムアウト不一致):
 * `auth.steps.ts`の`loginIfPromptedForDeviceCode`が呼ぶ`withAccountLock(E2E_ADMIN_EMAIL, ...)`は
 * ブラウザのログインフォームへ実際に値を入力してsubmitする、`loginViaKeycloak`(`helpers.ts`)と
 * 同種の対話ログインである。`loginViaKeycloak`は`{ timeoutMs: 120_000 }`を明示しているのに、
 * このステップだけが`options`省略で既定の30秒(`account-lock.ts`の`DEFAULT_LOCK_TIMEOUT_MS`)の
 * ままだった。既定の並列度でロック待ちの列が伸びたとき、この経路だけ誤ってタイムアウトしうる。
 *
 * このテストは、`loginIfPromptedForDeviceCode`が`withAccountLock`を`{ timeoutMs: 120_000 }`で
 * 呼ぶことを、`管理者がブラウザでデバイス認可を承認する`ステップ経由で検証する。ロック機構
 * そのもの(直列化・アカウント独立性)は`account-lock.test.ts`で別途検証済み。
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
 *     --testMatch='**\/e2e/auth-device-login-lock.test.ts' \
 *     e2e/auth-device-login-lock.test.ts
 */

// このファイルにはimport/exportが無く、そのままではTypeScriptがスクリプトとして扱い、
// 他のe2e配下のテストファイル(同様にimport/exportを持たないもの)とトップレベルの
// 識別子(withAccountLockMock等)が衝突して`npx tsc --noEmit`が壊れる。
// 明示的なexportでモジュールにし、グローバルスコープを汚さないようにする。
export {};

jest.mock('next-auth/jwt', () => ({ decode: jest.fn(), encode: jest.fn() }));

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
  E2E_TEST_EMAIL: 'e2e-test@letsblog.local',
  E2E_TEST_PASSWORD: 'e2e-test-password',
  createFixtureProject: jest.fn(),
  expect: jest.fn(() => ({
    toBeVisible: jest.fn().mockResolvedValue(undefined),
  })),
  fetchAccessToken: jest.fn(),
  getNextAuthSecret: jest.fn(),
  loginViaKeycloak: jest.fn(),
  withAccountLock: (email: string, fn: () => Promise<void>, options?: { timeoutMs?: number }) =>
    withAccountLockMock(email, fn, options),
}));

jest.mock('./support/services', () => ({
  AUTH_GATED_PATHS: [],
  DOMAIN_SERVICES: [],
  requestServiceDirectly: jest.fn(),
}));

function createFakeLocator() {
  return {
    fill: jest.fn().mockResolvedValue(undefined),
    click: jest.fn().mockResolvedValue(undefined),
    isVisible: jest.fn().mockResolvedValue(false),
    first: jest.fn(function firstSelf(this: unknown) {
      return this;
    }),
  };
}

function createFakePage() {
  const usernameLocator = { ...createFakeLocator(), isVisible: jest.fn().mockResolvedValue(true) };
  return {
    goto: jest.fn().mockResolvedValue(undefined),
    waitForURL: jest.fn().mockResolvedValue(undefined),
    locator: jest.fn((selector: string) => {
      if (selector === '#username') {
        return usernameLocator;
      }
      return createFakeLocator();
    }),
  };
}

// eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock後にrequireする必要がある
const { When } = require('./steps/fixtures');

// eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock後にrequireする必要がある
require('./steps/auth.steps');

function findStepHandler(mockFn: jest.Mock, stepText: string) {
  const call = mockFn.mock.calls.find(([text]: [string]) => text === stepText);
  if (!call) {
    throw new Error(`ステップが見つからない: ${stepText}`);
  }
  return call[1] as (args: Record<string, unknown>) => Promise<void>;
}

describe('loginIfPromptedForDeviceCode(issue #1295レビュー差し戻し: ロックタイムアウトをloginViaKeycloakと揃える)', () => {
  beforeEach(() => {
    withAccountLockMock.mockClear();
  });

  test('withAccountLockをloginViaKeycloakと同じ120秒タイムアウトで呼ぶ', async () => {
    const handler = findStepHandler(When, '管理者がブラウザでデバイス認可を承認する');
    const ctx = {
      deviceAuthorization: {
        verification_uri_complete: 'https://keycloak.example/device/verify?user_code=ABCD-EFGH',
        verification_uri: 'https://keycloak.example/device/verify',
        user_code: 'ABCD-EFGH',
      },
    };
    const page = createFakePage();

    await handler({ page, ctx });

    expect(withAccountLockMock).toHaveBeenCalledTimes(1);
    expect(withAccountLockMock.mock.calls[0][0]).toBe('e2e-admin@letsblog.local');
    expect(withAccountLockMock.mock.calls[0][2]).toEqual({ timeoutMs: 120_000 });
  });
});
