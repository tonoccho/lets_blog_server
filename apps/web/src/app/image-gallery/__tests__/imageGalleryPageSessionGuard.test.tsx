/**
 * @jest-environment node
 */

/**
 * issue #1234: `/image-gallery` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
 * 手法は `dashboardPageSessionGuard.test.tsx` と同じ。
 *
 * 自前の @/lib/session・@/lib/apiClient・ImageGalleryGrid は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1834)。
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

import { renderToStaticMarkup } from 'react-dom/server';
import { installFetch, jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import ImageGalleryPage from '../page';

/** バックエンド(gateway の向こう)の代役。 */
const listGeneratedImages = jest.fn();
const getMyProfile = jest.fn();
const listGeneratedImageFolders = jest.fn();

function setup(session: unknown) {
  jest.clearAllMocks();
  resetServerRuntime(session);
  installFetch({
    'GET /api/generated-images?limit=24&offset=0': async () => jsonResponse(await listGeneratedImages()),
    'GET /api/generated-images/folders': async () => jsonResponse(await listGeneratedImageFolders()),
    'GET /api/identity/me': async () => jsonResponse(await getMyProfile()),
  });
  listGeneratedImages.mockResolvedValue([]);
  getMyProfile.mockResolvedValue(null);
  listGeneratedImageFolders.mockResolvedValue([]);
}

describe('/image-gallery のセッション判定(issue #1234)', () => {
  it('session.error が RefreshAccessTokenError のとき /login へ送り、画像を取得しない(「生成画像がありません」を出さない)', async () => {
    setup({ user: { role: 'user' }, error: 'RefreshAccessTokenError' });

    await expect(ImageGalleryPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    setup(null);

    await expect(ImageGalleryPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    setup({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([
      { id: 1, projectId: 1, prompt: 'a cute cat', checkpoint: 'm', createdAt: '2026-08-01T00:00:00Z', tags: [], provider: 'COMFYUI', folderId: null },
    ]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    const html = renderToStaticMarkup(await ImageGalleryPage());

    expect(redirect).not.toHaveBeenCalled();
    expect(listGeneratedImages).toHaveBeenCalled();
    expect(html).toContain('a cute cat');
  });

  it('セッションが有効で画像が0件のときもリダイレクトしない(空状態表示。退行なし)', async () => {
    setup({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);

    const html = renderToStaticMarkup(await ImageGalleryPage());

    expect(redirect).not.toHaveBeenCalled();
    expect(html).toContain('生成画像がありません');
  });
});
