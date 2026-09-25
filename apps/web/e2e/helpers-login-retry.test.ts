/**
 * @jest-environment node
 *
 * issue #1391: `@destructive` シナリオ(例: `diagram.steps.ts`の`stopService(ctx, 'penpot-frontend')`)
 * がコンテナを再起動した直後、共有Dockerブリッジネットワークの再構成に伴う一過性の接続断が、
 * 直後の最初の操作(`loginViaKeycloak`が`/login`へ遷移してNextAuthの`signIn("keycloak")`が
 * 発行する`/api/auth/csrf`・`/api/auth/providers`へのfetch、またはKeycloakへのトップレベル
 * 遷移そのもの)を1回だけ巻き込むことがある。
 *
 * 実測(実装報告に記録): `docker compose stop/start penpot-frontend`前後で
 *   - ホストから`https://localhost/`へのcurl/`requests.Session`(keep-alive)を200ms間隔で
 *     反復しても到達性は一度も失われなかった(#1391の「有力な手がかり」が示すホスト疎通確認は
 *     この断を検知できない)。
 *   - 実際に3回中3回失敗した`at-destructive`実行の`docker logs lbs-web`には、`signIn`が
 *     内部で呼ぶ`/api/auth/providers`・`/api/auth/csrf`への`fetch`が
 *     `[next-auth][error][CLIENT_FETCH_ERROR] Failed to fetch`で失敗した記録が残っている。
 *     NextAuthのこのクライアント側フローに再試行は無く、1回の一過性断が
 *     `/api/auth/error`(または`chrome-error://chromewebdata/`)という「それ以上進行しない」
 *     状態に固定され、以降のポーリングは(タイムアウトまで)同じURLを観測し続ける。
 *
 * このテストは、`loginViaKeycloak`が`/login`遷移後に行き止まりURL
 * (`chrome-error://` または `/api/auth/error`)へ落ちた場合だけ`/login`から撮り直して
 * 再試行し、それ以外の失敗(実際のログイン障害)では再試行しないことを検証する。
 * 実ブラウザ(Playwrightの本物の`Page`)を駆動する部分は`helpers-login-lock.test.ts`と同様に
 * `@playwright/test`の`expect`と`Page`の薄いフェイクで代替する。
 *
 * `apps/web/e2e/**` は通常jestの対象外なので、他のe2e単体テストと同様にCLI引数で
 * 明示的に上書きして実行する(実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/helpers-login-retry.test.ts' \
 *     e2e/helpers-login-retry.test.ts
 */

// このファイルをESモジュールとして扱わせる(#1391)。`helpers-login-lock.test.ts`が
// import/exportを持たずグローバルスクリプト扱いになっており、同名のトップレベル識別子
// (`withAccountLockMock`・`loginViaKeycloak`等)を持つ本ファイルも同様にグローバル扱いだと
// `tsc --noEmit`が"Cannot redeclare block-scoped variable"で衝突する(実測)。
export {};

const toHaveURLMock = jest.fn();
jest.mock('@playwright/test', () => ({
  expect: jest.fn(() => ({ toHaveURL: toHaveURLMock })),
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

/** `urlSequence`を`page.url()`の呼び出し順に返す薄いフェイク。 */
function createFakePage(urlSequence: string[]) {
  let urlCallCount = 0;
  return {
    goto: jest.fn().mockResolvedValue(undefined),
    waitForLoadState: jest.fn().mockResolvedValue(undefined),
    locator: jest.fn(() => createFakeLocator()),
    url: jest.fn(() => {
      const value = urlSequence[Math.min(urlCallCount, urlSequence.length - 1)];
      urlCallCount += 1;
      return value;
    }),
  };
}

describe('loginViaKeycloak(issue #1391: 一過性の行き止まりURLからの再試行)', () => {
  beforeEach(() => {
    withAccountLockMock.mockClear();
    toHaveURLMock.mockReset();
  });

  test('chrome-errorへ落ちた場合、/loginへ撮り直して再試行し成功する', async () => {
    // 1回目のtoHaveURL呼び出し(Keycloakのレルムパターン待ち)だけ失敗させ、以降は成功させる。
    toHaveURLMock
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった'))
      .mockResolvedValue(undefined);
    const page = createFakePage(['chrome-error://chromewebdata/', 'https://localhost/']);

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    // 1回目の失敗のあと、/login への goto をもう一度行っている(撮り直し)。
    expect(page.goto).toHaveBeenCalledTimes(2);
    expect(page.goto).toHaveBeenNthCalledWith(1, '/login', { waitUntil: 'commit' });
    expect(page.goto).toHaveBeenNthCalledWith(2, '/login', { waitUntil: 'commit' });
  });

  test('/api/auth/errorへ落ちた場合も同様に撮り直して再試行する', async () => {
    toHaveURLMock
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった'))
      .mockResolvedValue(undefined);
    const page = createFakePage(['https://localhost/api/auth/error', 'https://localhost/']);

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    expect(page.goto).toHaveBeenCalledTimes(2);
  });

  test('行き止まりURLでない失敗(実際のログイン障害)では再試行せずそのまま失敗する', async () => {
    toHaveURLMock.mockRejectedValue(new Error('Timeout: 30000ms 待っても一致しなかった'));
    const page = createFakePage(['https://localhost/login']);

    await expect(loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)).rejects.toThrow(
      /Timeout: 30000ms/
    );

    // 行き止まりURLではないので、/login への goto は1回だけ(再試行しない)。
    expect(page.goto).toHaveBeenCalledTimes(1);
  });

  test('1回目で成功する通常経路では再試行が起きず、gotoは1回だけ', async () => {
    toHaveURLMock.mockResolvedValue(undefined);
    const page = createFakePage(['https://localhost/auth/realms/letsblog/protocol/openid-connect/auth']);

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    expect(page.goto).toHaveBeenCalledTimes(1);
  });

  /**
   * issue #1391のレビュー指摘: 「2回目も失敗する」分岐を通すテストが無かった。
   *
   * ここを固定しておかないと、将来ループの上限や条件の順序を変えたときに、
   * 無限再試行になったり失敗を握り潰したりしても誰も気付けない。
   *
   * この再試行が**失敗の隠蔽ではない**ことの証拠でもある。Keycloakが実際に落ちている
   * ような永続的な障害では2回目も行き止まりURLに落ちるが、そこで`attempt >= maxAttempts`
   * により元のエラーがそのまま投げられ、シナリオは(30秒遅れて)ちゃんと失敗する。
   */
  /**
   * issue #1391のレビュー指摘: 「2回目も失敗する」分岐を通すテストが無かった。
   *
   * **モックの組み方に注意。** `toHaveURLMock.mockRejectedValue`(常に失敗)にすると、
   * ループを抜けたあとの `loginViaKeycloak` 内の別の `toHaveURL` も同じエラーで落ちるため、
   * `attempt >= maxAttempts` のガードを外しても `rejects.toThrow` が通ってしまう
   * (=テストが空振りになる。実際に一度この形で書いて、ガードを外しても6/6通ることを確認した)。
   *
   * **2回だけ失敗させて3回目以降は成功させる**ことで、ガードの有無を区別できる:
   *   - ガードあり: 2回目の失敗でそのまま throw → reject する
   *   - ガードなし: 2回目の失敗も握り潰してループが終わり、関数は正常に返る → reject しない
   */
  test('2回目も行き止まりURLなら、握り潰さずに元のエラーで失敗する', async () => {
    toHaveURLMock
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった'))
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった'))
      .mockResolvedValue(undefined);
    // 1回目・2回目とも chrome-error(=一過性ではなく継続している障害)。
    const page = createFakePage([
      'chrome-error://chromewebdata/',
      'chrome-error://chromewebdata/',
      'https://localhost/',
    ]);

    await expect(loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)).rejects.toThrow(
      /Timeout: 30000ms/
    );

    // 上限は2回。3回目を試していないことを goto の呼び出し回数で固定する。
    expect(page.goto).toHaveBeenCalledTimes(2);
  });
});

/**
 * issue #1403: 撮り直しの適用範囲を**ログイン導線全体**へ広げる。
 *
 * #1391 が入れた撮り直しは1段目(`/login` → Keycloakへのリダイレクト待ち)しか守って
 * いなかった。リリース検証 run 9(`20260924T050201Z-2030601`)では、認証も
 * コールバックも成功したうえで**最後の `GET /` だけ**がブラウザ側で中断され
 * (nginx が 499 を0バイトで記録)、2段目の `toHaveURL('/')`(`helpers.ts:198`)が
 * 30秒ポーリングして失敗した。`"GET / HTTP/1.1" 499` はその run 全体(355シナリオ、
 * 数百回のログイン)で**この1件だけ**で、偶発的なトップレベル遷移の中断である。
 *
 * 2回目の試行では、1回目で `login-actions/authenticate` が302を返していれば
 * Keycloak側のSSOセッションが既に成立しており、`/login` はフォームを出さずに
 * `/` まで素通しになる。この経路も扱えないと、存在しない `#username` を
 * 埋めようとして別の失敗になる。
 */
describe('loginViaKeycloak(issue #1403: 導線全体の撮り直し)', () => {
  beforeEach(() => {
    withAccountLockMock.mockClear();
    toHaveURLMock.mockReset();
  });

  test('2段目(コールバック→/の待機)が行き止まりURLに落ちたら撮り直す', async () => {
    toHaveURLMock
      .mockResolvedValueOnce(undefined) // 1回目の1段目: Keycloakのフォームまで到達
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった')) // 1回目の2段目で中断
      .mockResolvedValue(undefined); // 2回目は通る
    const page = createFakePage([
      'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth', // フォームあり
      'chrome-error://chromewebdata/', // 行き止まり判定
      'https://localhost/', // 2回目: SSO成立済みで素通し
    ]);

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    expect(page.goto).toHaveBeenCalledTimes(2);
    expect(page.goto).toHaveBeenNthCalledWith(2, '/login', { waitUntil: 'commit' });
  });

  test('2回目にKeycloakのフォームが出ない(SSO成立済み)場合、資格情報を再送しない', async () => {
    toHaveURLMock
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった'))
      .mockResolvedValue(undefined);
    const page = createFakePage([
      'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth',
      'chrome-error://chromewebdata/',
      'https://localhost/', // レルムURLではない = フォームは出ていない
    ]);

    await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    // フォーム操作は1回目のみ: #username / #password / #kc-login / #firstName の4回。
    // 2回目もフォームを触ると8回になる。
    expect(page.locator).toHaveBeenCalledTimes(4);
  });

  test('2段目が2回とも行き止まりなら、握り潰さずに元のエラーで失敗する', async () => {
    toHaveURLMock
      .mockResolvedValueOnce(undefined) // 1回目の1段目
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった')) // 1回目の2段目
      .mockResolvedValueOnce(undefined) // 2回目の1段目
      .mockRejectedValueOnce(new Error('Timeout: 30000ms 待っても一致しなかった')) // 2回目の2段目
      .mockResolvedValue(undefined);
    const page = createFakePage([
      'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth',
      'chrome-error://chromewebdata/',
      'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth',
      'chrome-error://chromewebdata/',
      'https://localhost/',
    ]);

    await expect(loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)).rejects.toThrow(
      /Timeout: 30000ms/
    );

    expect(page.goto).toHaveBeenCalledTimes(2);
  });
});
