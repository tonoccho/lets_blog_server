/**
 * @jest-environment node
 */

/**
 * issue #1408: アセット画像生成パネルを非同期経路(`POST /api/ai/image/jobs`)へ切り替えるための
 * Server Actionの検証。`../actions` は他のコンポーネントテストから常にモックされるため、
 * ここではモックせずに直接importする。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startProjectImageJob = jest.fn();
const getGenerationJob = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  startProjectImageJob: (...args: unknown[]) => startProjectImageJob(...args),
  getGenerationJob: (...args: unknown[]) => getGenerationJob(...args),
}));

import { fetchImageJobResultAction, requestProjectImageJobAction } from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('requestProjectImageJobAction(issue #1408)', () => {
  it('管理者を確認し、projectId を足して非同期の入口へ渡し、ジョブIDと状態を返す', async () => {
    startProjectImageJob.mockResolvedValue({ id: 12, type: 'image_generation', status: 'running' });

    await expect(requestProjectImageJobAction(7, { prompt: 'cat', batchSize: 2 })).resolves.toEqual({
      jobId: 12,
      status: 'running',
    });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startProjectImageJob).toHaveBeenCalledWith({ prompt: 'cat', batchSize: 2, projectId: 7 });
  });

  it('受理を拒否されたら、その理由を error として返す(Error 以外も文字列にする)', async () => {
    startProjectImageJob.mockRejectedValueOnce(new Error('APIエラー (400): CHATGPT は10枚まで'));
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).resolves.toEqual({
      error: 'APIエラー (400): CHATGPT は10枚まで',
    });

    startProjectImageJob.mockRejectedValueOnce('plain');
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).resolves.toEqual({ error: 'plain' });
  });

  it('管理者でなければ API を呼ばない', async () => {
    requireAdminSession.mockRejectedValue(new Error('redirect'));
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).rejects.toThrow('redirect');
    expect(startProjectImageJob).not.toHaveBeenCalled();
  });
});

describe('fetchImageJobResultAction(issue #1408)', () => {
  it('完了したジョブの結果から、そのジョブが生成した画像のIDだけを返す', async () => {
    getGenerationJob.mockResolvedValue({ id: 9, status: 'done', resultPayload: '{"imageIds":[4,5,6],"count":3}' });

    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ images: [{ id: 4 }, { id: 5 }, { id: 6 }] });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(getGenerationJob).toHaveBeenCalledWith(9);
  });

  it('結果に画像が無ければ空配列を返す', async () => {
    getGenerationJob.mockResolvedValue({ id: 9, status: 'done', resultPayload: null });
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ images: [] });
  });

  it('完了していないジョブは、結果を出さずその旨を error として返す', async () => {
    getGenerationJob.mockResolvedValue({ id: 9, status: 'running', resultPayload: null });
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({
      error: 'このジョブはまだ完了していないか、失敗しています。',
    });
  });

  it('ジョブが取得できなければ(他人のジョブの404など)その理由を error として返す', async () => {
    getGenerationJob.mockRejectedValueOnce(new Error('APIエラー (404)'));
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ error: 'APIエラー (404)' });

    getGenerationJob.mockRejectedValueOnce('plain');
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ error: 'plain' });
  });
});
