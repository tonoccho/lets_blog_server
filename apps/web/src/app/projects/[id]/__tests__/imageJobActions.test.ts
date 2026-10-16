/**
 * @jest-environment node
 */

/**
 * issue #1408: アセット画像生成パネルを非同期経路(POST /api/ai/image/jobs)へ切り替えるためのServer Actionの検証。
 * `../actions` の実体を直接importし、`@/lib/apiClient`・`@/lib/session` も実物を通す。
 * 差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ(#1836)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { fetchImageJobResultAction, requestProjectImageJobAction } from '../actions';

const START = 'POST /api/ai/image/jobs';
const JOB = 'GET /api/generation-jobs/9';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [START]: () => jsonResponse({ id: 12, type: 'image_generation', status: 'running' }) });
});

describe('requestProjectImageJobAction(issue #1408)', () => {
  it('管理者を確認し、projectId を足して非同期の入口へ渡し、ジョブIDと状態を返す', async () => {
    await expect(requestProjectImageJobAction(7, { prompt: 'cat', batchSize: 2 })).resolves.toEqual({
      jobId: 12,
      status: 'running',
    });

    expect(bodies(backend, START)).toEqual([{ prompt: 'cat', batchSize: 2, projectId: 7 }]);
  });

  it('受理を拒否されたら、その理由を error として返す(Error 以外も文字列にする)', async () => {
    installBackend({ [START]: failWith(400, 'CHATGPT は10枚まで') });
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).resolves.toEqual({
      error: 'APIエラー (400): CHATGPT は10枚まで',
    });

    failFetchWith('plain');
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).resolves.toEqual({ error: 'plain' });
  });

  it('管理者でなければ API を呼ばない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(requestProjectImageJobAction(7, { prompt: 'cat' })).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});

describe('fetchImageJobResultAction(issue #1408)', () => {
  it('完了したジョブの結果から、そのジョブが生成した画像のIDだけを返す', async () => {
    installBackend({ [JOB]: () => jsonResponse({ id: 9, status: 'done', resultPayload: '{"imageIds":[4,5,6],"count":3}' }) });

    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ images: [{ id: 4 }, { id: 5 }, { id: 6 }] });
  });

  it('結果に画像が無ければ空配列を返す', async () => {
    installBackend({ [JOB]: () => jsonResponse({ id: 9, status: 'done', resultPayload: null }) });
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ images: [] });
  });

  it('完了していないジョブは、結果を出さずその旨を error として返す', async () => {
    installBackend({ [JOB]: () => jsonResponse({ id: 9, status: 'running', resultPayload: null }) });
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({
      error: 'このジョブはまだ完了していないか、失敗しています。',
    });
  });

  it('ジョブが取得できなければ(他人のジョブの404など)その理由を error として返す', async () => {
    installBackend({ [JOB]: failWith(404, 'not found') });
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ error: 'APIエラー (404): not found' });

    failFetchWith('plain');
    await expect(fetchImageJobResultAction(9)).resolves.toEqual({ error: 'plain' });
  });

  it('管理者でなければ API を呼ばない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(fetchImageJobResultAction(9)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
