/**
 * @jest-environment node
 */

/**
 * issue #1242: メンバー個別の「ユーザー情報を同期」を扱うServer Actionの検証。
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
import { failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { syncProjectUserAction } from '../actions';

const SYNC = 'POST /api/projects/7/users/42/sync';

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe('メンバーのユーザー情報同期のServer Action(issue #1242)', () => {
  it('管理者セッションを要求し、成功時は各サイトの結果をそのまま返す', async () => {
    const results = [
      { siteId: 10, siteKey: 'local-key', siteName: 'ローカル', success: true, errorMessage: null },
    ];
    installBackend({ [SYNC]: () => jsonResponse(results) });

    const result = await syncProjectUserAction(7, 42);

    expect(result).toEqual({ results });
  });

  it('失敗(Error)するとerrorメッセージを返す', async () => {
    installBackend({ [SYNC]: failWith(500, '同期に失敗しました') });

    const result = await syncProjectUserAction(7, 42);

    expect(result).toEqual({ error: 'APIエラー (500): 同期に失敗しました' });
  });

  it('Error以外で失敗しても文字列化したerrorを返す', async () => {
    failFetchWith('同期に失敗しました(非Error)');

    const result = await syncProjectUserAction(7, 42);

    expect(result).toEqual({ error: '同期に失敗しました(非Error)' });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    const backend = installBackend({ [SYNC]: () => jsonResponse([]) });
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(syncProjectUserAction(7, 42)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
