/**
 * @jest-environment node
 */

/**
 * issue #1409: タグデザインのAI生成(プロジェクト個別・グローバル)を非同期ジョブとして要求する
 * Server Action。生成結果は保存せず、受理されたジョブ(ID・状態)だけを返す。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startTagDesignGenerationJob = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startTagDesignGenerationJob: (...args: unknown[]) => startTagDesignGenerationJob(...args),
  saveTagDesignSetting: jest.fn(),
}));

import { generateTagDesignAction } from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('generateTagDesignAction(issue #1409)', () => {
  it('プロジェクト個別: 管理者を確認し、非同期の入口へ渡して、ジョブIDと状態を返す', async () => {
    startTagDesignGenerationJob.mockResolvedValue({ id: 23, type: 'tag_design_generation', status: 'running' });

    await expect(generateTagDesignAction(7, 'TOC', '淡いグレー')).resolves.toEqual({ jobId: 23, status: 'running' });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startTagDesignGenerationJob).toHaveBeenCalledWith(7, 'TOC', '淡いグレー');
  });

  it('グローバル: projectId null のまま渡す', async () => {
    startTagDesignGenerationJob.mockResolvedValue({ id: 24, type: 'tag_design_generation', status: 'running' });

    await generateTagDesignAction(null, 'BLOGCARD', 'p');

    expect(startTagDesignGenerationJob).toHaveBeenCalledWith(null, 'BLOGCARD', 'p');
  });

  it('管理者でなければバックエンドを呼ばない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));
    await expect(generateTagDesignAction(7, 'TOC', 'p')).rejects.toThrow('NEXT_REDIRECT');
    expect(startTagDesignGenerationJob).not.toHaveBeenCalled();
  });

  it('受理を拒否されたら理由を error として返す(Error 以外も文字列にする)', async () => {
    startTagDesignGenerationJob.mockRejectedValueOnce(new Error('APIエラー (403)'));
    await expect(generateTagDesignAction(7, 'TOC', 'p')).resolves.toEqual({ error: 'APIエラー (403)' });
    startTagDesignGenerationJob.mockRejectedValueOnce('boom');
    await expect(generateTagDesignAction(7, 'TOC', 'p')).resolves.toEqual({ error: 'boom' });
  });
});
