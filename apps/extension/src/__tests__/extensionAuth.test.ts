import {
  openedExternalUrls,
  progressRuns,
  shownErrors,
  shownInformations,
  setProgressStartsCancelled,
  shownWarnings,
} from '../__mocks__/vscode';
import { getActor, getAccessToken } from '../config';
import { Harness, startExtension } from './support/extensionHarness';
import { Reply, jsonReply } from './support/localHttpServer';
import { waitFor } from './support/panelKit';

/**
 * ログイン(Device Authorization Grant)・ログアウト。外部通信(Keycloak・gateway)は実サーバー、
 * VS Code(進捗通知・外部ブラウザ・通知)は代替。タイマーだけを偽物にして待ち時間を進める。
 */
let harness: Harness | undefined;

const DEVICE_PATH = '/auth/realms/letsblog/protocol/openid-connect/auth/device';
const TOKEN_PATH = '/auth/realms/letsblog/protocol/openid-connect/token';
const REVOKE_PATH = '/auth/realms/letsblog/protocol/openid-connect/revoke';

function jwt(claims: Record<string, unknown>): string {
  const encode = (value: unknown): string => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none' })}.${encode(claims)}.sig`;
}

function deviceReply(overrides: Record<string, unknown> = {}): Reply {
  return jsonReply({
    device_code: 'dev-1',
    user_code: 'ABCD-1234',
    verification_uri: 'https://auth.test/device',
    verification_uri_complete: 'https://auth.test/device?user_code=ABCD-1234',
    expires_in: 600,
    interval: 1,
    ...overrides,
  });
}

function successReply(claims: Record<string, unknown>): Reply {
  return jsonReply({ access_token: jwt(claims), refresh_token: 'refresh-1', expires_in: 3600 });
}

const pendingReply: Reply = jsonReply({ error: 'authorization_pending' }, 400);

beforeEach(() => {
  jest.useFakeTimers({
    doNotFake: ['Date', 'setImmediate', 'clearImmediate', 'nextTick', 'queueMicrotask', 'performance'],
  });
});

afterEach(async () => {
  jest.useRealTimers();
  await harness?.stop();
  harness = undefined;
});

/** ポーリングのトークン要求の回数。 */
function tokenRequests(): number {
  return harness!.requests(`POST ${TOKEN_PATH}`).length;
}

/** ポーリングの待機に入った(進捗の報告が n 回になった)まで待つ。 */
async function untilWaiting(n: number): Promise<void> {
  await waitFor(() => (progressRuns[0]?.reports.length ?? 0) >= n);
}

/** ログインを始め、最初のポーリング待機に入るまで進める。戻り値の `done` はログインの完了。 */
async function startLogin(): Promise<{ done: Promise<unknown> }> {
  const done = harness!.run('letsBlog.login');
  await untilWaiting(1);
  return { done };
}

describe('commandLogin', () => {
  it('承認されるまでポーリングし、トークンとログイン中のユーザーを保存して、ロールの表示名つきで通知する', async () => {
    harness = await startExtension({ login: false, projectId: null });
    let polls = 0;
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: () => {
        polls += 1;
        return polls === 1 ? pendingReply : successReply({ email: 'writer@example.com', realm_access: { roles: ['editor'] } });
      },
      'GET /api/metadata/roles': jsonReply([{ roleName: 'ROLE_EDITOR', displayName: '編集者' }]),
    });

    const { done: running } = await startLogin();
    expect(openedExternalUrls).toEqual(['https://auth.test/device?user_code=ABCD-1234']);
    expect(progressRuns[0].options.title).toContain('ABCD-1234');
    expect(progressRuns[0].options.cancellable).toBe(true);
    expect(progressRuns[0].reports[0].message).toContain('コード: ABCD-1234');

    await jest.advanceTimersByTimeAsync(1000);
    await waitFor(() => tokenRequests() === 1);
    await untilWaiting(2);
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(new URLSearchParams(harness.requests(`POST ${TOKEN_PATH}`)[0].body).get('device_code')).toBe('dev-1');
    expect(await getActor(harness.context)).toEqual({ email: 'writer@example.com', role: 'ROLE_EDITOR' });
    expect(await getAccessToken(harness.context)).toEqual(expect.any(String));
    expect(shownInformations).toEqual(["'writer@example.com' (編集者) としてログインしました。"]);
  });

  it('verification_uri_completeが無ければ verification_uri を開き、ロールが無ければ表示名を引かない', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply({ verification_uri_complete: undefined }),
      [`POST ${TOKEN_PATH}`]: successReply({ preferred_username: 'writer' }),
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(openedExternalUrls).toEqual(['https://auth.test/device']);
    expect(harness.requests('GET /api/metadata/roles')).toHaveLength(0);
    expect(await getActor(harness.context)).toEqual({ email: 'writer', role: '' });
    expect(shownInformations).toEqual(["'writer' としてログインしました。"]);
  });

  it('ロール一覧に該当が無い、または取得に失敗しても、表示名なしでログインは成功する', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: successReply({ email: 'a@b.test', realm_access: { roles: ['editor'] } }),
      'GET /api/metadata/roles': jsonReply([{ roleName: 'ROLE_OTHER', displayName: '他' }]),
    });
    let running = (await startLogin()).done;
    await jest.advanceTimersByTimeAsync(1000);
    await running;
    expect(shownInformations).toEqual(["'a@b.test' としてログインしました。"]);

    shownInformations.length = 0;
    progressRuns.length = 0;
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: successReply({ email: 'a@b.test', realm_access: { roles: ['editor'] } }),
      'GET /api/metadata/roles': { status: 403, body: 'forbidden' },
    });
    running = (await startLogin()).done;
    await jest.advanceTimersByTimeAsync(1000);
    await running;
    expect(shownInformations).toEqual(["'a@b.test' としてログインしました。"]);
  });

  it('slow_down を受けると、次のポーリングまでの間隔を 5 秒広げる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    let polls = 0;
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: () => {
        polls += 1;
        return polls === 1 ? jsonReply({ error: 'slow_down' }, 400) : successReply({ email: 'a@b.test' });
      },
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await waitFor(() => tokenRequests() === 1);
    await untilWaiting(2);

    await jest.advanceTimersByTimeAsync(5000);
    expect(tokenRequests()).toBe(1);
    await jest.advanceTimersByTimeAsync(1000);
    await running;
    expect(tokenRequests()).toBe(2);
  });

  it('拒否された場合は、やり直しを案内するエラーを出し、ログイン状態にしない', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: jsonReply({ error: 'access_denied' }, 400),
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(shownErrors[0]).toContain('ログインに失敗しました');
    expect(shownErrors[0]).toContain('ログインが拒否されました');
    expect(await getAccessToken(harness.context)).toBeUndefined();
  });

  it('サーバー側で認可コードが期限切れになった場合も、やり直しを案内する', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: jsonReply({ error: 'expired_token' }, 400),
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(shownErrors[0]).toContain('認可コードの有効期限が切れました');
  });

  it('手元の期限を過ぎていたら、トークンを問い合わせずに期限切れとして終える', async () => {
    jest.useRealTimers();
    jest.useFakeTimers({ doNotFake: ['setImmediate', 'clearImmediate', 'nextTick', 'queueMicrotask', 'performance'] });
    harness = await startExtension({ login: false, projectId: null });
    harness.api({ [`POST ${DEVICE_PATH}`]: deviceReply({ expires_in: 1 }) });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(shownErrors[0]).toContain('認可コードの有効期限が切れました');
    expect(tokenRequests()).toBe(0);
  });

  it('トークン取得が想定外のエラーなら、そのまま失敗として知らせる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: { status: 500, body: 'boom' },
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await running;

    expect(shownErrors[0]).toContain('トークンの取得に失敗しました (HTTP 500)');
  });

  it('待機中に進捗通知からキャンセルすると、キャンセルとして終わる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({ [`POST ${DEVICE_PATH}`]: deviceReply() });

    const { done: running } = await startLogin();
    progressRuns[0].cancel();
    await running;

    expect(shownErrors[0]).toBe('ログインに失敗しました: 操作をキャンセルしました。');
    expect(tokenRequests()).toBe(0);
  });

  it('進捗通知が始まる時点で既にキャンセルされていれば、トークンを問い合わせずにキャンセルとして終わる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({ [`POST ${DEVICE_PATH}`]: deviceReply() });
    setProgressStartsCancelled(true);

    await harness.run('letsBlog.login');

    expect(shownErrors[0]).toBe('ログインに失敗しました: 操作をキャンセルしました。');
    expect(tokenRequests()).toBe(0);
    expect(progressRuns[0].reports).toEqual([]);
  });

  it('トークン問い合わせの最中にキャンセルすると、その問い合わせを中断してキャンセルとして終わる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({
      [`POST ${DEVICE_PATH}`]: deviceReply(),
      [`POST ${TOKEN_PATH}`]: { status: 200, hang: true },
    });

    const { done: running } = await startLogin();
    await jest.advanceTimersByTimeAsync(1000);
    await waitFor(() => tokenRequests() === 1);
    progressRuns[0].cancel();
    await running;

    expect(shownErrors[0]).toBe('ログインに失敗しました: 操作をキャンセルしました。');
  });

  it('デバイス認可のリクエストに失敗したら、失敗として知らせる', async () => {
    harness = await startExtension({ login: false, projectId: null });
    harness.api({ [`POST ${DEVICE_PATH}`]: { status: 500, body: 'down' } });

    await harness.run('letsBlog.login');

    expect(shownErrors[0]).toContain('デバイス認可のリクエストに失敗しました (HTTP 500)');
    expect(progressRuns).toHaveLength(0);
  });
});

describe('commandLogout', () => {
  it('ログイン中なら Keycloak のセッションを終了し、端末側の資格情報を消して通知する', async () => {
    jest.useRealTimers();
    harness = await startExtension();
    harness.api({ [`POST ${REVOKE_PATH}`]: { status: 200 } });

    await harness.run('letsBlog.logout');

    expect(harness.requests(`POST ${REVOKE_PATH}`)).toHaveLength(1);
    expect(await getAccessToken(harness.context)).toBeUndefined();
    expect(shownInformations).toEqual(['ログアウトしました。']);
    expect(shownWarnings).toEqual([]);
  });

  it('Keycloak への通信に失敗しても端末側は削除し、サーバー側のセッションが残る可能性を警告する', async () => {
    jest.useRealTimers();
    harness = await startExtension();
    harness.api({ [`POST ${REVOKE_PATH}`]: { status: 500, body: 'down' } });

    await harness.run('letsBlog.logout');

    expect(await getAccessToken(harness.context)).toBeUndefined();
    expect(shownInformations).toEqual([]);
    expect(shownWarnings[0]).toContain('Keycloak側のセッション終了に失敗した');
  });

  it('未ログインでも、ログアウトしましたと通知するだけで通信しない', async () => {
    jest.useRealTimers();
    harness = await startExtension({ login: false });

    await harness.run('letsBlog.logout');

    expect(shownInformations).toEqual(['ログアウトしました。']);
    expect(harness.requests(`POST ${REVOKE_PATH}`)).toHaveLength(0);
  });
});
