/**
 * @jest-environment node
 */

/**
 * issue #1409: カスタムタグのAI生成を非同期ジョブとして要求する Server Action。生成と同時に保存せず、
 * 受理されたジョブ(ID・状態)だけを返す。結果は処理キューの「結果を見る」で確認し「保存」で登録する。
 */
jest.mock('server-only', () => ({}));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startCustomTagGenerationJob = jest.fn();
const validateCustomTag = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startCustomTagGenerationJob: (...args: unknown[]) => startCustomTagGenerationJob(...args),
  validateCustomTag: (...args: unknown[]) => validateCustomTag(...args),
}));

import { generateCustomTagAction, validateCustomTagAction } from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('generateCustomTagAction(issue #1409)', () => {
  it('管理者を確認し、非同期の入口へ要求を渡して、ジョブIDと状態を返す', async () => {
    startCustomTagGenerationJob.mockResolvedValue({ id: 21, type: 'custom_tag_generation', status: 'running' });
    const input = { prompt: 'p', tagName: 't', projectId: 7 };

    await expect(generateCustomTagAction(input)).resolves.toEqual({ jobId: 21, status: 'running' });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startCustomTagGenerationJob).toHaveBeenCalledWith(input);
  });

  it('管理者でなければ(確認が投げれば)バックエンドを呼ばない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).rejects.toThrow('NEXT_REDIRECT');
    expect(startCustomTagGenerationJob).not.toHaveBeenCalled();
  });

  it('受理を拒否されたら理由を error として返し、Error 以外も文字列にする', async () => {
    startCustomTagGenerationJob.mockRejectedValueOnce(new Error('APIエラー (403): 権限がありません'));
    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).resolves.toEqual({
      error: 'APIエラー (403): 権限がありません',
    });

    startCustomTagGenerationJob.mockRejectedValueOnce('boom');
    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).resolves.toEqual({ error: 'boom' });
  });
});

describe('validateCustomTagAction(既存の挙動)', () => {
  it('検証結果を data で、失敗を error で返す', async () => {
    validateCustomTag.mockResolvedValueOnce({ isValid: true, errors: [], warnings: [] });
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).resolves.toEqual({
      data: { isValid: true, errors: [], warnings: [] },
    });

    validateCustomTag.mockRejectedValueOnce(new Error('x'));
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).resolves.toEqual({ error: 'x' });
  });
});
