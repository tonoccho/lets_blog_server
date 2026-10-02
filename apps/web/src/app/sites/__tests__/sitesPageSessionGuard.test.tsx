/**
 * @jest-environment node
 */

/**
 * issue #1234: `/sites` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
 * 手法は `dashboardPageSessionGuard.test.tsx` と同じ。
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
const listProjects = jest.fn();
const listUsers = jest.fn();
const listSshKeyPairs = jest.fn();
const getMyProfile = jest.fn();
const getSiteAdminPath = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listSites: (...a: unknown[]) => listSites(...a),
  listProjects: (...a: unknown[]) => listProjects(...a),
  listUsers: (...a: unknown[]) => listUsers(...a),
  listSshKeyPairs: (...a: unknown[]) => listSshKeyPairs(...a),
  getMyProfile: (...a: unknown[]) => getMyProfile(...a),
  getSiteAdminPath: (...a: unknown[]) => getSiteAdminPath(...a),
}));

jest.mock('../SiteCreationPanel', () => ({ SiteCreationPanel: () => null }));
jest.mock('../SiteListTable', () => ({ SiteListTable: () => null }));

import SitesPage from '../page';

describe('/sites のセッション判定(issue #1234)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // これらのAPI呼び出し自体は本テストの関心ではないため、常に解決させる(理由は
    // dashboardPageSessionGuard.test.tsxのコメント参照)。
    listSites.mockResolvedValue([]);
    listProjects.mockResolvedValue([]);
    listUsers.mockResolvedValue([]);
    listSshKeyPairs.mockResolvedValue([]);
    getMyProfile.mockResolvedValue(null);
    getSiteAdminPath.mockResolvedValue({ path: 'wp-admin' });
  });

  it('session.error が RefreshAccessTokenError のとき /login へ送り、データ取得を行わない', async () => {
    getServerSession.mockResolvedValue({
      user: { role: 'user' },
      error: 'RefreshAccessTokenError',
    });

    await expect(SitesPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listSites).not.toHaveBeenCalled();
    expect(listProjects).not.toHaveBeenCalled();
    expect(listUsers).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(SitesPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listSites).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    listSites.mockResolvedValue([{ id: 1 }]);
    listProjects.mockResolvedValue([{ id: 1 }]);
    listUsers.mockResolvedValue([{ id: 1 }]);
    listSshKeyPairs.mockResolvedValue([{ id: 1 }]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    await expect(SitesPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listSites).toHaveBeenCalled();
    expect(listProjects).toHaveBeenCalled();
    expect(listUsers).toHaveBeenCalled();
    expect(listSshKeyPairs).toHaveBeenCalled();
  });

  it('セッションが有効な非adminのときはリダイレクトせず、SSH鍵ペアは取得しない(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });

    await expect(SitesPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listSshKeyPairs).not.toHaveBeenCalled();
  });
});
