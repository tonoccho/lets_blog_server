/**
 * @jest-environment node
 */

/**
 * issue #1409: 静的コンテンツの生成を非同期ジョブとして要求する Server Action と、
 * 結果を確認したあとの「保存」(既存の static_content への書き込み)の Server Action。
 *
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1834)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { generateStaticContentAction, saveStaticContentAction } from '../actions';
import { ADMIN_SESSION, USER_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

/** バックエンド(gateway の向こう)の代役。 */
const startStaticContentGenerationJob = jest.fn();
const saveStaticContent = jest.fn();

function setup(session: unknown = ADMIN_SESSION) {
  jest.clearAllMocks();
  resetServerRuntime(session);
  startStaticContentGenerationJob.mockReset();
  saveStaticContent.mockReset();
  installFetch({
    'POST /api/sites/:id/static-content/generate/jobs': async (_u, init, [id]) => {
      try {
        return jsonResponse(await startStaticContentGenerationJob(Number(id), JSON.parse(String(init.body)).contentType));
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 404);
      }
    },
    'PUT /api/sites/:id/static-content/:type': async (_u, init, [id, type]) => {
      try {
        return jsonResponse(await saveStaticContent(Number(id), type, JSON.parse(String(init.body)).body));
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 400);
      }
    },
  });
}

beforeEach(() => setup());

describe('generateStaticContentAction(issue #1409)', () => {
  it('管理者を確認し、非同期の入口へサイトと種別を渡して、ジョブIDと状態を返す', async () => {
    startStaticContentGenerationJob.mockResolvedValue({ id: 22, type: 'static_content_generation', status: 'running' });

    await expect(generateStaticContentAction(3, 'PRIVACY_POLICY')).resolves.toEqual({ jobId: 22, status: 'running' });

    expect(startStaticContentGenerationJob).toHaveBeenCalledWith(3, 'PRIVACY_POLICY');
  });

  it('管理者でなければバックエンドを呼ばない', async () => {
    setup(USER_SESSION);
    await expect(generateStaticContentAction(3, 'PRIVACY_POLICY')).rejects.toThrow('NEXT_REDIRECT');
    expect(startStaticContentGenerationJob).not.toHaveBeenCalled();
  });

  it('受理を拒否されたら理由を error として返す(Error 以外も文字列にする)', async () => {
    startStaticContentGenerationJob.mockRejectedValueOnce(new Error('サイトがありません'));
    await expect(generateStaticContentAction(3, 'OPERATOR_INFO')).resolves.toEqual({
      error: 'APIエラー (404): サイトがありません',
    });
    installFetch({
      'POST /api/sites/:id/static-content/generate/jobs': () => {
        throw 'boom';
      },
    });
    await expect(generateStaticContentAction(3, 'OPERATOR_INFO')).resolves.toEqual({ error: 'boom' });
  });
});

describe('saveStaticContentAction(issue #1409)', () => {
  it('管理者を確認し、本文を既存の保存APIへ渡して、保存された静的コンテンツを返し、サイト編集画面を再検証する', async () => {
    const saved = { id: 1, siteId: 3, contentType: 'OPERATOR_INFO', body: '本文', createdAt: 'a', updatedAt: 'b' };
    saveStaticContent.mockResolvedValue(saved);

    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '本文')).resolves.toEqual({ content: saved });

    expect(saveStaticContent).toHaveBeenCalledWith(3, 'OPERATOR_INFO', '本文');
    expect(revalidatePath).toHaveBeenCalledWith('/sites/3/edit');
  });

  it('管理者でなければ保存しない', async () => {
    setup(USER_SESSION);
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '本文')).rejects.toThrow('NEXT_REDIRECT');
    expect(saveStaticContent).not.toHaveBeenCalled();
  });

  it('保存に失敗したら理由を error として返し、再検証しない', async () => {
    saveStaticContent.mockRejectedValueOnce(new Error('本文は必須です'));
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', '')).resolves.toEqual({
      error: 'APIエラー (400): 本文は必須です',
    });
    installFetch({
      'PUT /api/sites/:id/static-content/:type': () => {
        throw 'boom';
      },
    });
    await expect(saveStaticContentAction(3, 'OPERATOR_INFO', 'x')).resolves.toEqual({ error: 'boom' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
