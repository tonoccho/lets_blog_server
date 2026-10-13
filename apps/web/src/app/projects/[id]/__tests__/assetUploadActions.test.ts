/**
 * @jest-environment node
 */

/**
 * issue #1764: アセット画像の全環境アップロードのServer Actionは、完了を待たずジョブとして受理した時点で返る。
 * `bulkInstallActions.test.ts`(#1702)と同じ方針で `../actions` を直接importして検証する。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startProjectAssetImageUploadJob = jest.fn();
const uploadProjectAssetImage = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  startProjectAssetImageUploadJob: (...args: unknown[]) => startProjectAssetImageUploadJob(...args),
  uploadProjectAssetImage: (...args: unknown[]) => uploadProjectAssetImage(...args),
}));

import { uploadProjectAssetImageAction } from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('uploadProjectAssetImageAction (#1764)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    startProjectAssetImageUploadJob.mockResolvedValue({ id: 61, status: 'running' });

    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ jobId: 61 });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startProjectAssetImageUploadJob).toHaveBeenCalledWith(7, 11);
  });

  it('同期APIは呼ばず、完了していないのでプロジェクトのページを再検証しない', async () => {
    startProjectAssetImageUploadJob.mockResolvedValue({ id: 61, status: 'running' });
    await uploadProjectAssetImageAction(7, 11);
    expect(uploadProjectAssetImage).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    startProjectAssetImageUploadJob.mockResolvedValue({ id: 61, status: 'failed' });

    const result = await uploadProjectAssetImageAction(7, 11);

    expect(result.jobId).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    startProjectAssetImageUploadJob.mockRejectedValueOnce(new Error('404 not found'));
    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ error: '404 not found' });
    startProjectAssetImageUploadJob.mockRejectedValueOnce('boom');
    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ error: 'boom' });
  });
});
