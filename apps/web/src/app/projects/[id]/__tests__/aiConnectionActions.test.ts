/**
 * @jest-environment node
 */

/**
 * issue #1504: Ollama / ComfyUI の接続情報(#1499)と接続先の上書き(#1503)を扱うServer Actionの検証。
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
import {
  fetchAiConnectionsAction,
  fetchProjectConnectionsAction,
  updateProjectConnectionAction,
  pullOllamaModelAction,
} from '../actions';

const CONNECTIONS = 'PUT /api/projects/7/ai-models/connections';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe('接続情報のServer Action(issue #1504)', () => {
  it('fetchAiConnectionsActionは管理者セッションを要求し、一覧をそのまま返す(失敗は握り潰さず投げる)', async () => {
    installBackend({ 'GET /api/projects/7/ai-connections': () => jsonResponse([{ provider: 'OLLAMA' }]) });
    await expect(fetchAiConnectionsAction(7)).resolves.toEqual([{ provider: 'OLLAMA' }]);

    installBackend({ 'GET /api/projects/7/ai-connections': failWith(500, 'boom') });
    await expect(fetchAiConnectionsAction(7)).rejects.toThrow('APIエラー (500): boom');
  });

  it('fetchProjectConnectionsActionは管理者セッションを要求し、接続先をそのまま返す(失敗は投げる)', async () => {
    const data = { ollama: { overrideBaseUrl: null, baseUrl: 'http://o', source: 'DATABASE' } };
    installBackend({ 'GET /api/projects/7/ai-models/connections': () => jsonResponse(data) });
    await expect(fetchProjectConnectionsAction(7)).resolves.toEqual(data);

    installBackend({ 'GET /api/projects/7/ai-models/connections': failWith(500, 'boom') });
    await expect(fetchProjectConnectionsAction(7)).rejects.toThrow('APIエラー (500): boom');
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    backend = installBackend();
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(fetchAiConnectionsAction(7)).rejects.toThrow('NEXT_REDIRECT:/');
    await expect(fetchProjectConnectionsAction(7)).rejects.toThrow('NEXT_REDIRECT:/');
    await expect(updateProjectConnectionAction(7, 'OLLAMA', 'x')).rejects.toThrow('NEXT_REDIRECT:/');
    await expect(pullOllamaModelAction(7, 'x')).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });

  it('updateProjectConnectionActionはOLLAMAの値をollamaBaseUrlだけで送り、応答を返す', async () => {
    const data = { ollama: { overrideBaseUrl: 'http://o', baseUrl: 'http://o', source: 'PROJECT' } };
    backend = installBackend({ [CONNECTIONS]: () => jsonResponse(data) });

    const result = await updateProjectConnectionAction(7, 'OLLAMA', 'http://o');

    expect(bodies(backend, CONNECTIONS)).toEqual([{ ollamaBaseUrl: 'http://o' }]);
    expect(result).toEqual({ data });
  });

  it('updateProjectConnectionActionはCOMFYUIの空文字をcomfyuiBaseUrl=""(上書き解除)で送る', async () => {
    backend = installBackend({ [CONNECTIONS]: () => jsonResponse({}) });

    await updateProjectConnectionAction(7, 'COMFYUI', '');

    expect(bodies(backend, CONNECTIONS)).toEqual([{ comfyuiBaseUrl: '' }]);
  });

  it('updateProjectConnectionActionは失敗をerrorとして返す(Error以外の例外も文字列にする)', async () => {
    installBackend({ [CONNECTIONS]: failWith(400, 'bad') });
    await expect(updateProjectConnectionAction(7, 'OLLAMA', 'x')).resolves.toEqual({ error: 'APIエラー (400): bad' });

    failFetchWith('plain');
    await expect(updateProjectConnectionAction(7, 'OLLAMA', 'x')).resolves.toEqual({ error: 'plain' });
  });

  it('pullOllamaModelActionは管理者セッションを要求し、ジョブIDと実行中かどうかを返す(issue #1675)', async () => {
    backend = installBackend({
      'POST /api/projects/7/ai-models/ollama/pull': () => jsonResponse({ jobId: 31, alreadyRunning: true }),
    });

    const result = await pullOllamaModelAction(7, 'llama3');

    expect(bodies(backend, 'POST /api/projects/7/ai-models/ollama/pull')).toEqual([{ model: 'llama3' }]);
    expect(result).toEqual({ jobId: 31, alreadyRunning: true });
  });

  it('pullOllamaModelActionは失敗をerrorとして返す(Error以外の例外も文字列にする)', async () => {
    installBackend({ 'POST /api/projects/7/ai-models/ollama/pull': failWith(403, 'forbidden') });
    await expect(pullOllamaModelAction(7, 'x')).resolves.toEqual({ error: 'APIエラー (403): forbidden' });

    failFetchWith('plain');
    await expect(pullOllamaModelAction(7, 'x')).resolves.toEqual({ error: 'plain' });
  });
});
