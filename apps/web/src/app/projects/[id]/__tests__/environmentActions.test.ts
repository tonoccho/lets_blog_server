/**
 * @jest-environment node
 */

/**
 * issue #1500: 環境の紐付け・切離しアクションは、詳細ページとダッシュボードを再検証する。
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
import { bodies, failFetchWith, failWith, form, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { bindEnvironmentAction, unbindEnvironmentAction } from '../actions';

const BIND = 'POST /api/projects/7/environments';
const UNBIND = 'DELETE /api/projects/7/environments/test';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [BIND]: () => jsonResponse({}), [UNBIND]: () => jsonResponse({}) });
});

describe('環境の紐付け・切離しアクション(issue #1500: ダッシュボードも再検証する)', () => {
  it('紐付けに成功すると詳細ページとダッシュボードを再検証する', async () => {
    const result = await bindEnvironmentAction(7, 'production', {}, form({ siteId: '3' }));

    expect(result).toEqual({ success: true });
    expect(bodies(backend, BIND)).toEqual([{ environment: 'production', siteId: 3 }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });

  it('サイト未選択ならエラーを返し、再検証しない', async () => {
    const result = await bindEnvironmentAction(7, 'test', {}, form({ siteId: '' }));

    expect(result.error).toBe('サイトを選択してください。');
    expect(requestCount(backend)).toBe(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('紐付けがErrorで失敗したらそのメッセージを返し、再検証しない', async () => {
    installBackend({ [BIND]: failWith(409, 'conflict') });

    expect(await bindEnvironmentAction(7, 'test', {}, form({ siteId: '3' }))).toEqual({
      error: 'APIエラー (409): conflict',
    });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('紐付けがError以外で失敗しても文字列化して返す', async () => {
    failFetchWith('boom');

    expect(await bindEnvironmentAction(7, 'test', {}, form({ siteId: '3' }))).toEqual({ error: 'boom' });
  });

  it('切離しに成功すると詳細ページとダッシュボードを再検証する', async () => {
    await unbindEnvironmentAction(7, 'test');

    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(bindEnvironmentAction(7, 'test', {}, form({ siteId: '3' }))).rejects.toThrow('NEXT_REDIRECT:/');
    await expect(unbindEnvironmentAction(7, 'test')).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
