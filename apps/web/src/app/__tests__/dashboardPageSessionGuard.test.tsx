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

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));

const getServerSession = jest.fn();
jest.mock('next-auth', () => ({
  getServerSession: (...args: unknown[]) => getServerSession(...args),
}));
jest.mock('@/lib/auth', () => ({ authOptions: {} }));

const listSites = jest.fn();
const listPosts = jest.fn();
const listGenerationJobs = jest.fn();
const getConnectedServiceStatuses = jest.fn();
const getConnectedServiceStatusDetail = jest.fn();
const getContainerStatuses = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listSites: (...a: unknown[]) => listSites(...a),
  listPosts: (...a: unknown[]) => listPosts(...a),
  listGenerationJobs: (...a: unknown[]) => listGenerationJobs(...a),
  getConnectedServiceStatuses: (...a: unknown[]) => getConnectedServiceStatuses(...a),
  getConnectedServiceStatusDetail: (...a: unknown[]) => getConnectedServiceStatusDetail(...a),
  getContainerStatuses: (...a: unknown[]) => getContainerStatuses(...a),
}));

jest.mock('../ConnectedServiceStatusPanel', () => ({
  ConnectedServiceStatusPanel: () => null,
}));
jest.mock('../ContainerStatusPanel', () => ({
  ContainerStatusPanel: () => null,
}));

import DashboardPage from '../page';

describe('ダッシュボード(/)のセッション判定(issue #1234)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // これらのAPI呼び出し自体は本テストの関心ではないため、常に解決させる。
    // 未解決(=undefined)のままだと`.catch()`が呼べずTypeErrorになり、
    // 「リダイレクトされない」という本来見たい失敗理由を覆い隠してしまう。
    listSites.mockResolvedValue([]);
    listPosts.mockResolvedValue([]);
    listGenerationJobs.mockResolvedValue([]);
    getConnectedServiceStatuses.mockResolvedValue([]);
    getConnectedServiceStatusDetail.mockResolvedValue(null);
    getContainerStatuses.mockResolvedValue([]);
  });

  it('session.error が RefreshAccessTokenError のとき /login へ送り、データ取得を行わない', async () => {
    getServerSession.mockResolvedValue({
      user: { role: 'user' },
      error: 'RefreshAccessTokenError',
    });

    await expect(DashboardPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listSites).not.toHaveBeenCalled();
    expect(listPosts).not.toHaveBeenCalled();
    expect(listGenerationJobs).not.toHaveBeenCalled();
  });

  it('未ログイン(session が null)のときも /login へ送る', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(DashboardPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listSites).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    listSites.mockResolvedValue([{ id: 1 }]);
    listPosts.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    listGenerationJobs.mockResolvedValue([{ id: 1 }]);
    getConnectedServiceStatuses.mockResolvedValue([]);
    getConnectedServiceStatusDetail.mockResolvedValue([]);
    getContainerStatuses.mockResolvedValue([]);

    await expect(DashboardPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listSites).toHaveBeenCalled();
    expect(listPosts).toHaveBeenCalled();
    expect(listGenerationJobs).toHaveBeenCalled();
  });

  it('セッションが有効な非adminのときはリダイレクトせず、サービス詳細は取得しない(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });

    await expect(DashboardPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(getConnectedServiceStatusDetail).not.toHaveBeenCalled();
  });
});
