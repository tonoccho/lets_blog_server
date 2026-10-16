/**
 * @jest-environment node
 */

/**
 * issue #1506: ChatGPT(OpenAI) のプロジェクト単位APIキーを保存・削除するServer Actionの検証。
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
import { bodies, failFetchWith, failWith, installBackend, noContent, requestCount } from './projectCoreRuntime';
import { clearOpenAiApiKeyAction, setOpenAiApiKeyAction } from '../actions';

const KEY = '/api/projects/7/api-keys/openai-api-key';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ ['PUT ' + KEY]: noContent, ['DELETE ' + KEY]: noContent });
});

describe('ChatGPT(OpenAI) APIキーのServer Action(issue #1506)', () => {
  it('setOpenAiApiKeyAction はトリムしたキーを保存して空オブジェクトを返す', async () => {
    await expect(setOpenAiApiKeyAction(7, '  sk-abc  ')).resolves.toEqual({});

    expect(bodies(backend, 'PUT ' + KEY)).toEqual([{ apiKey: 'sk-abc' }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('setOpenAiApiKeyAction は空・空白のキーを保存せずエラーを返す', async () => {
    await expect(setOpenAiApiKeyAction(7, '')).resolves.toEqual({ error: 'APIキーを入力してください。' });
    await expect(setOpenAiApiKeyAction(7, '   ')).resolves.toEqual({ error: 'APIキーを入力してください。' });

    expect(requestCount(backend)).toBe(0);
  });

  it('setOpenAiApiKeyAction は失敗をerrorとして返す(Error以外も文字列にする)', async () => {
    installBackend({ ['PUT ' + KEY]: failWith(403, 'forbidden') });
    await expect(setOpenAiApiKeyAction(7, 'k')).resolves.toEqual({ error: 'APIエラー (403): forbidden' });

    failFetchWith('plain');
    await expect(setOpenAiApiKeyAction(7, 'k')).resolves.toEqual({ error: 'plain' });
  });

  it('clearOpenAiApiKeyAction は削除して空オブジェクトを返し、失敗はerrorとして返す', async () => {
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({});
    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');

    installBackend({ ['DELETE ' + KEY]: failWith(500, 'boom') });
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({ error: 'APIエラー (500): boom' });

    failFetchWith('plain');
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({ error: 'plain' });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(setOpenAiApiKeyAction(7, 'k')).rejects.toThrow('NEXT_REDIRECT:/');
    await expect(clearOpenAiApiKeyAction(7)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
