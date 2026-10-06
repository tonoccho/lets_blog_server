/**
 * @jest-environment node
 */

/**
 * issue #1409: 静的コンテンツの生成を非同期ジョブとして要求する Server Action と、
 * 結果を確認したあとの「保存」(既存の static_content への書き込み)の Server Action。
 */
jest.mock('server-only', () => ({}));

const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startStaticContentGenerationJob = jest.fn();
const saveStaticContent = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startStaticContentGenerationJob: (...args: unknown[]) => startStaticContentGenerationJob(...args),
  saveStaticContent: (...args: unknown[]) => saveStaticContent(...args),
}));

import { generateStaticContentAction, saveStaticContentAction } from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('generateStaticContentAction(issue #1409)', () => {
  it('管理者を確認し、非同期の入口へサイトと種別を渡して、ジョブIDと状態を返す', async () => {
    startStaticContentGenerationJob.mockResolvedValue({ id: 22, type: 'static_content_generation', status: 'running' });

    await expect(generateStaticContentAction(3, 'PRIVACY_POLICY')).resolves.toEqual({ jobId: 22, status: 'running' });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startStaticContentGenerationJob).toHaveBeenCalledWith(3, 'PRIVACY_POLICY');
  });

  it('管理者でなければバックエンドを呼ばない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));
    await expect(generateStaticContentAction(3, 'PRIVACY_POLICY')).rejects.toThrow('NEXT_REDIRECT');
    expect(startStaticContentGenerationJob).not.toHaveBeenCalled();
  });

  it('受理を拒否されたら理由を error として返す(Error 以外も文字列にする)', async () => {
    startStaticContentGenerationJob.mockRejectedValueOnce(new Error('APIエラー (404): サイトがありません'));
    await expect(generateStaticContentAction(3, 'OPERATOR_INFO')).resolves.toEqual({
      error: 'APIエラー (404): サイトがありません',
    });
    startStaticContentGenerationJob.mockRejectedValueOnce('boom');
    await expect(generateStaticContentAction(3, 'OPERATOR_INFO')).resolves.toEqual({ error: 'boom' });
  });
});

describe('saveStaticContentAction(issue #1409)', () => {
  it('管理者を確認し、本文を既存の保存APIへ渡して、保存された静的コンテンツを返し、サイト編集画面を再検証する', async () => {
    const saved = { id: 1, siteId: 3, contentType: 'OPERATOR_INFO', body: '本文', createdAt: 'a', updatedAt: 'b' };
    saveStaticContent.mockResolvedValue(saved);

    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '本文')).resolves.toEqual({ content: saved });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(saveStaticContent).toHaveBeenCalledWith(3, 'OPERATOR_INFO', '本文');
    expect(revalidatePath).toHaveBeenCalledWith('/sites/3/edit');
  });

  it('管理者でなければ保存しない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '本文')).rejects.toThrow('NEXT_REDIRECT');
    expect(saveStaticContent).not.toHaveBeenCalled();
  });

  it('保存に失敗したら理由を error として返し、再検証しない', async () => {
    saveStaticContent.mockRejectedValueOnce(new Error('APIエラー (400): 本文は必須です'));
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '')).resolves.toEqual({
      error: 'APIエラー (400): 本文は必須です',
    });
    saveStaticContent.mockRejectedValueOnce('boom');
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', 'x')).resolves.toEqual({ error: 'boom' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
