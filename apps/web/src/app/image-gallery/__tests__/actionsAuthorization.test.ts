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
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));

/**
 * 自前の @/lib/apiClient・@/lib/session・@/lib/auth は差し替えない(#1834)。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけで、バックエンド(media-service)の代役は ./galleryBackend の `backend`。
 */
import { backend, installGallery } from './galleryBackend';
import { getServerSessionMock } from '@/lib/__tests__/testUtils/serverRuntime';

const getGeneratedImage = backend.getImage;
const deleteGeneratedImage = backend.deleteImage;
const updateGeneratedImageTags = backend.updateTags;
const listGeneratedImages = backend.listImages;
const bulkDeleteGeneratedImages = backend.bulkDelete;
const createGeneratedImageFolder = backend.createFolder;
const setGeneratedImageFolder = backend.setFolder;
const renameGeneratedImageFolder = backend.renameFolder;
const getGeneratedImageFolderDeleteImpact = backend.folderDeleteImpact;
const deleteGeneratedImageFolder = backend.deleteFolder;
const editGeneratedImage = backend.editImage;
/** セッションの状態を差し替える(未ログインは null)。 */
const getServerSession = getServerSessionMock;

import {
  bulkDeleteGeneratedImagesAction,
  createGeneratedImageFolderAction,
  setGeneratedImageFolderAction,
  renameGeneratedImageFolderAction,
  getGeneratedImageFolderDeleteImpactAction,
  deleteGeneratedImageFolderAction,
  deleteGeneratedImageAction,
  editGeneratedImageAction,
  fetchGalleryImagesPageAction,
  getGeneratedImageAction,
  updateGeneratedImageTagsAction,
} from '../actions';

describe('image-gallery の Server Action の認可(issue #824)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
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
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
  });

  it('未ログインは /login へ送り、取得しない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(fetchGalleryImagesPageAction(24, null, null, null)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listGeneratedImages).not.toHaveBeenCalled();
  });

  it('tag なしは絞り込まずに、固定のページサイズと offset で取得する', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([{ id: 1 }]);

    const result = await fetchGalleryImagesPageAction(48, null, null, null);

    expect(result).toEqual([{ id: 1 }]);
    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '48' });
  });

  it('tag があればそのまま渡す', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);

    await fetchGalleryImagesPageAction(0, '猫', null, null);

    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '0', tag: '猫' });
  });
});

/** issue #1647: 種別(UPLOAD / AI)は一覧APIの source として渡す。null は絞り込みなし(source を付けない)。 */
describe('fetchGalleryImagesPageAction の種別絞り込み(issue #1647)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);
  });

  it.each(['UPLOAD', 'AI'] as const)('%s は tag・フォルダと併せて source として渡す', async (source) => {
    await fetchGalleryImagesPageAction(24, '猫', 5, source);

    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '24', tag: '猫', folderId: '5', source });
  });

  it('null は source を付けない', async () => {
    await fetchGalleryImagesPageAction(0, null, null, null);

    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '0' });
  });
});

/** issue #1492: 一括削除の Server Action もログイン必須(#824 の方針)。認可の本体は media-service が id ごとに行う。 */
describe('bulkDeleteGeneratedImagesAction(issue #1492)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
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

/** issue #1493: フォルダでの絞り込みは一覧APIの条件(folderId / unfiled)として渡す。 */
describe('fetchGalleryImagesPageAction のフォルダ絞り込み(issue #1493)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    listGeneratedImages.mockResolvedValue([]);
  });

  it('フォルダidは folderId として渡す', async () => {
    await fetchGalleryImagesPageAction(0, null, 5, null);

    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '0', folderId: '5' });
  });

  it('"unfiled" は unfiled=true として渡し、folderId は付けない', async () => {
    await fetchGalleryImagesPageAction(0, '猫', 'unfiled', null);

    expect(backend.listQuery).toHaveBeenCalledWith({ limit: '24', offset: '0', tag: '猫', unfiled: 'true' });
  });
});

/** issue #1493: フォルダの作成・所属変更の Server Action はログイン必須。admin 判定は media-service が行い、403 はそのまま伝わる。 */
describe('フォルダの Server Action(issue #1493)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
  });

  it('未ログインは /login へ送り、作成も所属変更もしない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(createGeneratedImageFolderAction('海', null)).rejects.toThrow('NEXT_REDIRECT:/login');
    await expect(setGeneratedImageFolderAction(1, 2)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(createGeneratedImageFolder).not.toHaveBeenCalled();
    expect(setGeneratedImageFolder).not.toHaveBeenCalled();
  });

  it('作成は結果を返し、一覧を再検証する', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    createGeneratedImageFolder.mockResolvedValue({ id: 3, name: '海', parentId: 1 });

    await expect(createGeneratedImageFolderAction('海', 1)).resolves.toEqual({ id: 3, name: '海', parentId: 1 });

    expect(createGeneratedImageFolder).toHaveBeenCalledWith('海', 1);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });

  it('所属変更は結果を返し、一覧を再検証する', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    setGeneratedImageFolder.mockResolvedValue({ id: 1, folderId: null });

    await expect(setGeneratedImageFolderAction(1, null)).resolves.toEqual({ id: 1, folderId: null });

    expect(setGeneratedImageFolder).toHaveBeenCalledWith(1, null);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });

  it('media-service が403を返したら、再検証せずそのまま伝える', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    createGeneratedImageFolder.mockRejectedValue(new Error('APIエラー (403): forbidden'));

    await expect(createGeneratedImageFolderAction('海', null)).rejects.toThrow('403');
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

/** issue #1494: フォルダの改名・削除影響範囲・削除の Server Action はログイン必須。admin 判定は media-service が行い、403 はそのまま伝わる。 */
describe('フォルダの改名・削除の Server Action(issue #1494)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
  });

  it('未ログインは /login へ送り、何も呼ばない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(renameGeneratedImageFolderAction(1, '海')).rejects.toThrow('NEXT_REDIRECT:/login');
    await expect(getGeneratedImageFolderDeleteImpactAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
    await expect(deleteGeneratedImageFolderAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(renameGeneratedImageFolder).not.toHaveBeenCalled();
    expect(getGeneratedImageFolderDeleteImpact).not.toHaveBeenCalled();
    expect(deleteGeneratedImageFolder).not.toHaveBeenCalled();
  });

  it('改名・削除は結果を返し一覧を再検証する。影響範囲の取得は再検証しない', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'admin' } });
    renameGeneratedImageFolder.mockResolvedValue({ id: 1, name: '海', parentId: null });
    getGeneratedImageFolderDeleteImpact.mockResolvedValue({ descendantFolderCount: 2, imageCount: 3 });
    deleteGeneratedImageFolder.mockResolvedValue(undefined);

    await expect(renameGeneratedImageFolderAction(1, '海')).resolves.toEqual({ id: 1, name: '海', parentId: null });
    expect(revalidatePath).toHaveBeenCalledTimes(1);
    await expect(getGeneratedImageFolderDeleteImpactAction(1)).resolves.toEqual({ descendantFolderCount: 2, imageCount: 3 });
    expect(revalidatePath).toHaveBeenCalledTimes(1);
    await expect(deleteGeneratedImageFolderAction(1)).resolves.toBeUndefined();

    expect(renameGeneratedImageFolder).toHaveBeenCalledWith(1, '海');
    expect(deleteGeneratedImageFolder).toHaveBeenCalledWith(1);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
    expect(revalidatePath).toHaveBeenCalledTimes(2);
  });

  it('media-service が403を返したら、再検証せずそのまま伝える', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    renameGeneratedImageFolder.mockRejectedValue(new Error('APIエラー (403): forbidden'));
    deleteGeneratedImageFolder.mockRejectedValue(new Error('APIエラー (403): forbidden'));

    await expect(renameGeneratedImageFolderAction(1, '海')).rejects.toThrow('403');
    await expect(deleteGeneratedImageFolderAction(1)).rejects.toThrow('403');
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

/** issue #1655: 画像の編集(新しい画像として保存)の Server Action もログイン必須。権限は media-service が判定する。 */
describe('editGeneratedImageAction(issue #1655)', () => {
  beforeEach(() => {
    installGallery();
    redirect.mockClear();
    revalidatePath.mockClear();
  });

  it('未ログインは /login へ送り、編集しない', async () => {
    getServerSession.mockResolvedValue(null);

    await expect(editGeneratedImageAction(1, ['ROTATE_CW'], null, null)).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(editGeneratedImage).not.toHaveBeenCalled();
  });

  it('ログイン済みなら編集を依頼し、ギャラリーを再検証して結果を返す', async () => {
    getServerSession.mockResolvedValue({ user: { role: 'user' } });
    editGeneratedImage.mockResolvedValue({ id: 9 });
    const crop = { x: 1, y: 2, width: 3, height: 4 };
    const adjustment = { brightness: 20, contrast: -10 };

    const result = await editGeneratedImageAction(1, ['ROTATE_CW'], crop, adjustment);

    expect(result).toEqual({ id: 9 });
    expect(editGeneratedImage).toHaveBeenCalledWith(1, ['ROTATE_CW'], crop, adjustment);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });
});
