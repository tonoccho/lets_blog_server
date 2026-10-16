/**
 * @jest-environment node
 */

/**
 * issue #1234: セッションが更新不能(`session.error === "RefreshAccessTokenError"`)なとき、
 * ダッシュボード(`/`)が「ログイン済みでデータ0件」の見た目のまま描画されず、
 * `requireSession()` と同じ判定でログイン画面へリダイレクトすることを固定する。
 *
 * <p>`apps/web/src/app/sites/__tests__/actionsAuthorization.test.ts` と同じ手法:
 * `next-auth` の `getServerSession` をモックし、`next/navigation` の `redirect` を
 * throw するモックに差し替えて「その先へ進まない」ことを検証する。
 */
jest.mock('server-only', () => ({}));
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth・子コンポーネントは差し替えない。差し替えるのは
// Next.js の実行基盤(next/navigation の redirect、next-auth、next/headers など)と、外部との通信(fetch)だけ(#1832)。
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));

import {
  getServerSessionMock as getServerSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
} from '@/lib/__tests__/testUtils/serverRuntime';

const EMPTY_LOG_PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 1 };
const DETAIL = 'GET /api/dashboard/service-status/detail';

/** ダッシュボードが呼ぶ gateway の API。呼ばれた URL は返り値の fetch モックから引く。 */
function dashboardApi(overrides: Record<string, () => Response> = {}) {
  return installFetch({
    'GET /api/sites': () => jsonResponse([]),
    'GET /api/posts': () => jsonResponse([]),
    'GET /api/operation-logs/unified?type=AI_JOB&page=0&size=1': () => jsonResponse(EMPTY_LOG_PAGE),
    'GET /api/dashboard/service-status': () => jsonResponse([]),
    [DETAIL]: () => jsonResponse(null),
    'GET /api/dashboard/container-status': () => jsonResponse([]),
    'GET /api/identity/me': () => jsonResponse(null),
    ...overrides,
  });
}

/** 業務 API(操作ログの記録を除く)に飛んだ URL。 */
function calledPaths(fetchMock: jest.Mock): string[] {
  return fetchMock.mock.calls
    .map(([url]) => String(url).replace(/^https?:\/\/[^/]+/, ''))
    .filter((path) => path !== '/api/operation-logs');
}

import DashboardPage from '../page';

describe('ダッシュボード(/)のセッション判定(issue #1234)', () => {
  beforeEach(() => {
    resetServerRuntime(null);
    redirect.mockClear();
  });

  it('session.error が RefreshAccessTokenError のとき /login へ送り、データ取得を行わない', async () => {
    getServerSession.mockResolvedValue({
      user: { role: 'user' },
      error: 'RefreshAccessTokenError',
    });
    const fetchMock = dashboardApi();

    await expect(DashboardPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(calledPaths(fetchMock)).toEqual([]);
  });

  it('未ログイン(session が null)のときも /login へ送る', async () => {
    getServerSession.mockResolvedValue(null);
    const fetchMock = dashboardApi();

    await expect(DashboardPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(calledPaths(fetchMock)).not.toContain('/api/sites');
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    const fetchMock = dashboardApi({
      'GET /api/sites': () => jsonResponse([{ id: 1 }]),
      'GET /api/posts': () => jsonResponse([{ id: 1 }, { id: 2 }]),
      'GET /api/operation-logs/unified?type=AI_JOB&page=0&size=1': () =>
        jsonResponse({ ...EMPTY_LOG_PAGE, totalElements: 1, totalPages: 1 }),
      [DETAIL]: () => jsonResponse([]),
    });

    await expect(DashboardPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(calledPaths(fetchMock)).toEqual(
      expect.arrayContaining([
        '/api/sites',
        '/api/posts',
        '/api/operation-logs/unified?type=AI_JOB&page=0&size=1',
      ])
    );
  });

  it('セッションが有効な非adminのときはリダイレクトせず、サービス詳細は取得しない(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    const fetchMock = dashboardApi();

    await expect(DashboardPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(calledPaths(fetchMock)).not.toContain('/api/dashboard/service-status/detail');
  });
});
