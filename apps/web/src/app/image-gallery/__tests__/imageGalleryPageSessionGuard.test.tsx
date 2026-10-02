/**
 * @jest-environment node
 */

/**
 * issue #1234: `/image-gallery` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
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

const listGeneratedImages = jest.fn();
const getMyProfile = jest.fn();
const listGeneratedImageFolders = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listGeneratedImages: (...a: unknown[]) => listGeneratedImages(...a),
  listGeneratedImageFolders: (...a: unknown[]) => listGeneratedImageFolders(...a),
  getMyProfile: (...a: unknown[]) => getMyProfile(...a),
}));

jest.mock('../ImageGalleryGrid', () => ({ ImageGalleryGrid: () => null }));

import ImageGalleryPage from '../page';

describe('/image-gallery のセッション判定(issue #1234)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    listGeneratedImages.mockResolvedValue([]);
    getMyProfile.mockResolvedValue(null);
    listGeneratedImageFolders.mockResolvedValue([]);
  });

  it('session.error が RefreshAccessTokenError のとき /login へ送り、画像を取得しない(「生成画像がありません」を出さない)', async () => {
    getServerSession.mockResolvedValue({
      user: { role: 'user' },
      error: 'RefreshAccessTokenError',
    });

    await expect(ImageGalleryPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(ImageGalleryPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([{ id: 1 }]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    await expect(ImageGalleryPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listGeneratedImages).toHaveBeenCalled();
  });

  it('セッションが有効で画像が0件のときもリダイレクトしない(空状態表示。退行なし)', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);

    await expect(ImageGalleryPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
  });
});
