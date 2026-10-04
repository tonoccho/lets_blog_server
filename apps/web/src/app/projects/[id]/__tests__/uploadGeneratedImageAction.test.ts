/**
 * @jest-environment node
 */

/**
 * issue #1599: アセット画像パネルの「画像をアップロード」Server Actionの検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここではモックせずに直接importする。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const uploadGeneratedImage = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  uploadGeneratedImage: (...args: unknown[]) => uploadGeneratedImage(...args),
}));

import { uploadGeneratedImageAction } from '../actions';

function formWith(file?: File): FormData {
  const formData = new FormData();
  if (file) formData.append('file', file);
  return formData;
}

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('uploadGeneratedImageAction(issue #1599)', () => {
  it('管理者を確認し、ファイルを projectId 付きで登録して画像IDを返し、ギャラリーを再検証する', async () => {
    uploadGeneratedImage.mockResolvedValue({ id: 31, provider: 'UPLOAD' });
    const file = new File([new Uint8Array([1, 2])], 'a.png', { type: 'image/png' });

    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({ imageId: 31 });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(uploadGeneratedImage).toHaveBeenCalledWith(7, expect.any(File));
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });

  it('ファイルが無い・空なら API を呼ばずエラーを返す', async () => {
    await expect(uploadGeneratedImageAction(7, formWith())).resolves.toEqual({
      error: '画像ファイルを選択してください。',
    });
    await expect(uploadGeneratedImageAction(7, formWith(new File([], 'empty.png')))).resolves.toEqual({
      error: '画像ファイルを選択してください。',
    });
    expect(uploadGeneratedImage).not.toHaveBeenCalled();
  });

  it('API が拒否したら、その理由を error として返し、再検証しない(Error 以外も文字列にする)', async () => {
    uploadGeneratedImage.mockRejectedValueOnce(new Error('APIエラー (400): 対応していない画像形式です'));
    const file = new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' });
    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({
      error: 'APIエラー (400): 対応していない画像形式です',
    });

    uploadGeneratedImage.mockRejectedValueOnce('plain');
    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ API を呼ばない', async () => {
    requireAdminSession.mockRejectedValue(new Error('redirect'));
    const file = new File([new Uint8Array([1])], 'a.png', { type: 'image/png' });
    await expect(uploadGeneratedImageAction(7, formWith(file))).rejects.toThrow('redirect');
    expect(uploadGeneratedImage).not.toHaveBeenCalled();
  });
});
