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
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const getServerSession = jest.fn();
jest.mock('next-auth', () => ({
  getServerSession: (...args: unknown[]) => getServerSession(...args),
}));
jest.mock('@/lib/auth', () => ({ authOptions: {} }));

const getGeneratedImage = jest.fn();
const deleteGeneratedImage = jest.fn();
const updateGeneratedImageTags = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getGeneratedImage: (...a: unknown[]) => getGeneratedImage(...a),
  deleteGeneratedImage: (...a: unknown[]) => deleteGeneratedImage(...a),
  updateGeneratedImageTags: (...a: unknown[]) => updateGeneratedImageTags(...a),
}));

import {
  deleteGeneratedImageAction,
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
