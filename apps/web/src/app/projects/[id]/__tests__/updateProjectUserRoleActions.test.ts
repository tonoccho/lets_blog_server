/**
 * @jest-environment node
 */

/**
 * issue #1302: ロール変更のServer Actionは、失敗を例外ではなく { error } として返す(addProjectUserAction と同じ形)。
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
import { updateProjectUserRoleAction } from '../actions';

const ROLE = 'PUT /api/projects/7/users/42';

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe('ロール変更のServer Action(issue #1302)', () => {
  it('成功すると管理者セッションを要求してAPIを呼び、画面を再検証してerrorを含まない結果を返す', async () => {
    const backend = installBackend({ [ROLE]: noContent });

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(bodies(backend, ROLE)).toEqual([{ wpRole: 'editor' }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(result.error).toBeUndefined();
  });

  it('失敗(Error)すると例外を投げずerrorメッセージを返し、再検証しない', async () => {
    installBackend({ [ROLE]: failWith(500, 'テスト環境: 失敗しました') });

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(result).toEqual({ error: 'APIエラー (500): テスト環境: 失敗しました' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('Error以外で失敗しても文字列化したerrorを返す', async () => {
    failFetchWith('非Errorの失敗');

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(result).toEqual({ error: '非Errorの失敗' });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    const backend = installBackend({ [ROLE]: noContent });
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(updateProjectUserRoleAction(7, 42, 'editor')).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
