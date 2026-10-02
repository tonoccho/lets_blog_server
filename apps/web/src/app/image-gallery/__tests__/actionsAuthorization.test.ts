/**
 * @jest-environment node
 */

/**
 * issue #824: 生成画像ギャラリーの Server Action の認可が実行時に効いていること。
 *
 * <p>これらは `requireSession()`(admin 限定にしない)。`/image-gallery` は
 * `proxy.ts` の `ADMIN_ONLY_PREFIXES` に含まれずログイン済みなら誰でも開ける画面で、
 * 画面側も `isAdmin` による出し分けをしていないため。
 *
 * <p>所有者による絞り込みをしていないのは、`GeneratedImage` に所有者を表す列が無く
 * (現状のスキーマでは表現できない)、ギャラリーがプロジェクト横断で全件を表示するため。
 * `projectId` はあるので、将来プロジェクトメンバーシップによる絞り込みは可能。
 * バックエンド側の認可欠落は #830 で追跡している。
 */
jest.mock('server-only', () => ({}));

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));

const getServerSession = jest.fn();
jest.mock('next-auth', () => ({
  getServerSession: (...args: unknown[]) => getServerSession(...args),
}));
jest.mock('@/lib/auth', () => ({ authOptions: {} }));

const getGeneratedImage = jest.fn();
const deleteGeneratedImage = jest.fn();
const updateGeneratedImageTags = jest.fn();
const listGeneratedImages = jest.fn();
const bulkDeleteGeneratedImages = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getGeneratedImage: (...a: unknown[]) => getGeneratedImage(...a),
  deleteGeneratedImage: (...a: unknown[]) => deleteGeneratedImage(...a),
  updateGeneratedImageTags: (...a: unknown[]) => updateGeneratedImageTags(...a),
  listGeneratedImages: (...a: unknown[]) => listGeneratedImages(...a),
  bulkDeleteGeneratedImages: (...a: unknown[]) => bulkDeleteGeneratedImages(...a),
}));

import {
  bulkDeleteGeneratedImagesAction,
  deleteGeneratedImageAction,
  fetchGalleryImagesPageAction,
  getGeneratedImageAction,
  updateGeneratedImageTagsAction,
} from '../actions';

describe('image-gallery の Server Action の認可(issue #824)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('未ログイン', () => {
    beforeEach(() => getServerSession.mockResolvedValue(null));

    it('getGeneratedImageAction は /login へ送り、取得しない', async () => {
      await expect(getGeneratedImageAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
      expect(getGeneratedImage).not.toHaveBeenCalled();
    });

    it('deleteGeneratedImageAction は /login へ送り、削除しない', async () => {
      await expect(deleteGeneratedImageAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
      expect(deleteGeneratedImage).not.toHaveBeenCalled();
    });

    it('updateGeneratedImageTagsAction は /login へ送り、更新しない', async () => {
      await expect(updateGeneratedImageTagsAction(1, ['tag'])).rejects.toThrow(
        'NEXT_REDIRECT:/login'
      );
      expect(updateGeneratedImageTags).not.toHaveBeenCalled();
    });
  });

  /** アクセストークンのリフレッシュに失敗した状態も未ログインと同じ扱いになる。 */
  it('session.error が立っていれば /login へ送る', async () => {
    getServerSession.mockResolvedValue({ user: {}, error: 'RefreshAccessTokenError' });

    await expect(deleteGeneratedImageAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(deleteGeneratedImage).not.toHaveBeenCalled();
  });

  describe('ログイン済み(非admin)', () => {
    beforeEach(() => getServerSession.mockResolvedValue({ user: { role: 'user' } }));

    it('admin でなくても取得・削除・タグ更新ができる', async () => {
      getGeneratedImage.mockResolvedValue({ id: 1 });
      updateGeneratedImageTags.mockResolvedValue({ id: 1, tags: ['t'] });

      await getGeneratedImageAction(1);
      await deleteGeneratedImageAction(1);
      await updateGeneratedImageTagsAction(1, ['t']);

      expect(getGeneratedImage).toHaveBeenCalledWith(1);
      expect(deleteGeneratedImage).toHaveBeenCalledWith(1);
      expect(updateGeneratedImageTags).toHaveBeenCalledWith(1, ['t']);
      expect(redirect).not.toHaveBeenCalled();
    });
  });
});

/**
 * issue #1472: 次ページ取得の Server Action はログイン必須(#824 の方針)で、ページサイズは
 * サーバ側で固定し、クライアントから任意の limit を指定させない。
 */
describe('fetchGalleryImagesPageAction(issue #1472)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('未ログインは /login へ送り、取得しない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(fetchGalleryImagesPageAction(24, null)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('tag なしは絞り込まずに、固定のページサイズと offset で取得する', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([{ id: 1 }]);

    const result = await fetchGalleryImagesPageAction(48, null);

    expect(result).toEqual([{ id: 1 }]);
    expect(listGeneratedImages).toHaveBeenCalledWith(undefined, { limit: 24, offset: 48, tag: undefined });
  });

  it('tag があればそのまま渡す', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);

    await fetchGalleryImagesPageAction(0, '猫');

    expect(listGeneratedImages).toHaveBeenCalledWith(undefined, { limit: 24, offset: 0, tag: '猫' });
  });
});

/** issue #1492: 一括削除の Server Action もログイン必須(#824 の方針)。認可の本体は media-service が id ごとに行う。 */
describe('bulkDeleteGeneratedImagesAction(issue #1492)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('未ログインは /login へ送り、削除しない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(bulkDeleteGeneratedImagesAction([1, 2])).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(bulkDeleteGeneratedImages).not.toHaveBeenCalled();
  });

  it('ログイン済みなら id を渡して結果を返し、ギャラリーを再検証する', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    const result = { deletedCount: 2, failedCount: 0, deletedIds: [1, 2], failures: {} };
    bulkDeleteGeneratedImages.mockResolvedValue(result);

    await expect(bulkDeleteGeneratedImagesAction([1, 2])).resolves.toEqual(result);

    expect(bulkDeleteGeneratedImages).toHaveBeenCalledWith([1, 2]);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });
});
