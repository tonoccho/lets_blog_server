/**
 * @jest-environment node
 */

/**
 * issue #1409: タグデザインのAI生成(プロジェクト個別・グローバル)を非同期ジョブとして要求する
 * Server Action。生成結果は保存せず、受理されたジョブ(ID・状態)だけを返す。
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { generateTagDesignAction } from '../actions';

let requests: { path: string; body: unknown }[];
let respond: () => Response;

beforeEach(() => {
  requests = [];
  resetServerRuntime(ADMIN_SESSION);
  respond = () => jsonResponse({ id: 23, type: 'tag_design_generation', status: 'running' });
  const handler = (url: string, init: RequestInit) => {
    requests.push({ path: new URL(url).pathname, body: JSON.parse(String(init.body)) });
    return respond();
  };
  installFetch({
    'POST /api/projects/:id/tag-design-settings/:type/generate/jobs': handler,
    'POST /api/tag-design-settings/:type/generate/jobs': handler,
  });
});

describe('generateTagDesignAction(issue #1409)', () => {
  it('プロジェクト個別: 管理者を確認し、非同期の入口へ渡して、ジョブIDと状態を返す', async () => {
    await expect(generateTagDesignAction(7, 'TOC', '淡いグレー')).resolves.toEqual({ jobId: 23, status: 'running' });

    expect(requests).toEqual([{ path: '/api/projects/7/tag-design-settings/TOC/generate/jobs', body: { prompt: '淡いグレー' } }]);
  });

  it('グローバル: projectId null のままグローバルの入口へ渡す', async () => {
    await generateTagDesignAction(null, 'BLOGCARD', 'p');

    expect(requests).toEqual([{ path: '/api/tag-design-settings/BLOGCARD/generate/jobs', body: { prompt: 'p' } }]);
  });

  it('管理者でなければバックエンドを呼ばない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(generateTagDesignAction(7, 'TOC', 'p')).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requests).toEqual([]);
  });

  it('受理を拒否されたら理由を error として返す(Error 以外も文字列にする)', async () => {
    respond = () => textResponse('権限がありません', 403);
    await expect(generateTagDesignAction(7, 'TOC', 'p')).resolves.toEqual({ error: 'APIエラー (403): 権限がありません' });
    global.fetch = jest.fn().mockRejectedValue('boom') as unknown as typeof fetch;
    await expect(generateTagDesignAction(7, 'TOC', 'p')).resolves.toEqual({ error: 'boom' });
  });
});
