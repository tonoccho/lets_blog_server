/**
 * @jest-environment node
 */

/**
 * issue #1234: `/posts` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
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

const listPosts = jest.fn();
const getMyProfile = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listPosts: (...a: unknown[]) => listPosts(...a),
  getMyProfile: (...a: unknown[]) => getMyProfile(...a),
}));

jest.mock('../PostsTable', () => ({ PostsTable: () => null }));

import PostsPage from '../page';

describe('/posts のセッション判定(issue #1234)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    listPosts.mockResolvedValue([]);
    getMyProfile.mockResolvedValue(null);
  });

  it('session.error が RefreshAccessTokenError のとき /login へ送り、投稿を取得しない(「全0件を表示」を出さない)', async () => {
    getServerSession.mockResolvedValue({
      user: { role: 'user' },
      error: 'RefreshAccessTokenError',
    });

    await expect(PostsPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listPosts).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(PostsPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listPosts).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listPosts.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    await expect(PostsPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listPosts).toHaveBeenCalled();
  });

  it('セッションが有効で投稿が0件のときもリダイレクトしない(空状態表示。退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listPosts.mockResolvedValue([]);

    await expect(PostsPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
  });
});
