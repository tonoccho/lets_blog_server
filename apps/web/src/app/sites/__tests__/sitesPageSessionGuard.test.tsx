/**
 * @jest-environment node
 */

/**
 * issue #1234: `/sites` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
 * 手法は `dashboardPageSessionGuard.test.tsx` と同じ。
 *
 * 自前の @/lib/session・@/lib/apiClient・SiteCreationPanel・SiteListTable は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));

import { installFetch, jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import SitesPage from '../page';

/** gateway の向こう側(各サービス)の代役。 */
const listSites = jest.fn();
const listProjects = jest.fn();
const listUsers = jest.fn();
const listSshKeyPairs = jest.fn();
const getMyProfile = jest.fn();
const getSiteAdminPath = jest.fn();

function setup(session: unknown) {
  jest.clearAllMocks();
  resetServerRuntime(session);
  installFetch({
    'GET /api/sites': async () => jsonResponse(await listSites()),
    'GET /api/projects': async () => jsonResponse(await listProjects()),
    'GET /api/users': async () => jsonResponse(await listUsers()),
    'GET /api/ssh-key-pairs': async () => jsonResponse(await listSshKeyPairs()),
    'GET /api/identity/me': async () => jsonResponse(await getMyProfile()),
    'GET /api/system-settings/site-admin-path': async () => jsonResponse(await getSiteAdminPath()),
  });
  // これらのAPI呼び出し自体は本テストの関心ではないため、常に解決させる(理由は
  // dashboardPageSessionGuard.test.tsxのコメント参照)。
  listSites.mockResolvedValue([]);
  listProjects.mockResolvedValue([]);
  listUsers.mockResolvedValue([]);
  listSshKeyPairs.mockResolvedValue([]);
  getMyProfile.mockResolvedValue(null);
  getSiteAdminPath.mockResolvedValue({ path: 'wp-admin' });
}

describe('/sites のセッション判定(issue #1234)', () => {
  it('session.error が RefreshAccessTokenError のとき /login へ送り、データ取得を行わない', async () => {
    setup({ user: { role: 'user' }, error: 'RefreshAccessTokenError' });

    await expect(SitesPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listSites).not.toHaveBeenCalled();
    expect(listProjects).not.toHaveBeenCalled();
    expect(listUsers).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    setup(null);

    await expect(SitesPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listSites).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    setup({ user: { role: 'admin' } });
    listSites.mockResolvedValue([]);
    listProjects.mockResolvedValue([{ id: 1 }]);
    listUsers.mockResolvedValue([]);
    listSshKeyPairs.mockResolvedValue([]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    await expect(SitesPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listSites).toHaveBeenCalled();
    expect(listProjects).toHaveBeenCalled();
    expect(listUsers).toHaveBeenCalled();
    expect(listSshKeyPairs).toHaveBeenCalled();
  });

  it('セッションが有効な非adminのときはリダイレクトせず、SSH鍵ペアは取得しない(退行なし)', async () => {
    setup({ user: { role: 'user' } });

    await expect(SitesPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listSshKeyPairs).not.toHaveBeenCalled();
  });
});
