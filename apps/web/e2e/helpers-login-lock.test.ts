/**
 * @jest-environment node
 *
 * issue #1295フォローアップ(QAのFAIL、note 7391): `loginViaKeycloak`(ブラウザ対話ログイン、
 * `letsblog-web`クライアント、Authorization Codeフロー)は、`fetchAccessToken`
 * (パスワードグラント)向けに実装されていたアカウント単位クロスプロセスロックを一切通らず、
 * 実際の受け入れテスト実行で`user_temporarily_disabled`を2件再現した。
 *
 * このテストは、`loginViaKeycloak`が実際のログイン手順(フォーム操作)を
 * `./account-lock`の`withAccountLock`(対象アカウントのメールアドレスをキーにした
 * クロスプロセスロック)で囲んで実行することを検証する。実ブラウザ(Playwrightの本物の`Page`)
 * を駆動する部分自体は`@playwright/test`の`expect`と`Page`の薄いフェイクで代替し、
 * ロックの配線(どのアカウントでロックを取るか、ロック獲得後にログイン手順が走るか)だけを見る。
 * ロック機構そのもの(直列化・アカウント独立性)の検証は`account-lock.test.ts`で行う。
 *
 * `apps/web/e2e/**` は通常jestの対象外なので、他のe2e単体テストと同様にCLI引数で
 * 明示的に上書きして実行する(実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/helpers-login-lock.test.ts' \
 *     e2e/helpers-login-lock.test.ts
 */

jest.mock('@playwright/test', () => ({
  expect: jest.fn(() => ({ toHaveURL: jest.fn().mockResolvedValue(undefined) })),
}));

const withAccountLockMock = jest.fn(async (_email: string, fn: () => Promise<void>) => fn());
jest.mock('./account-lock', () => ({
  withAccountLock: (email: string, fn: () => Promise<void>) => withAccountLockMock(email, fn),
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock後にrequireする必要がある
const { loginViaKeycloak, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD } = require('./helpers');

function createFakeLocator() {
  return {
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
    // issue #1403: `loginViaKeycloak` はKeycloakのログイン画面が出ているかどうかを
    // URLで判定するようになったため、フェイクにも `url()` が要る。ここは
    // 「ログイン手順がロックの中で走る」ことだけを見るテストなので、
    // フォーム操作を通る側(レルムURL)を返しておく。
    url: jest.fn(() => 'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth'),
  };
}

describe('loginViaKeycloak(issue #1295フォローアップ: アカウント単位ロックの配線)', () => {
  beforeEach(() => {
    withAccountLockMock.mockClear();
  });

  test('実ログイン手順の実行をwithAccountLockで、対象アカウントのメールアドレスをキーに囲む', async () => {
    const page = createFakePage();

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    expect(withAccountLockMock).toHaveBeenCalledTimes(1);
    expect(withAccountLockMock.mock.calls[0][0]).toBe(E2E_ADMIN_EMAIL);
    // ロック獲得後のコールバック内で実際のログイン手順(ナビゲーション・フォーム入力)が走る。
    expect(page.goto).toHaveBeenCalledWith('/login', { waitUntil: 'commit' });
  });

  test('ロックが獲得できなければ実ログイン手順は一切実行されない', async () => {
    withAccountLockMock.mockImplementationOnce(async () => {
      throw new Error('ロック待ちがタイムアウトしました');
    });
    const page = createFakePage();

    await expect(loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)).rejects.toThrow(
      /ロック待ちがタイムアウトしました/
    );
    expect(page.goto).not.toHaveBeenCalled();
  });
});
