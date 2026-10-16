/**
 * @jest-environment node
 */

/**
 * issue #1578: 設定画面の PV 達成ルールの Server Action(追加 / 削除 / 再送)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。入力の検証はバックエンドへ送る前に行う。
 * `@/lib/apiClient`・`@/lib/session` は実物を通し、差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ(#1838)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION, USER_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, form, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { addProjectPvRuleAction, deleteProjectPvRuleAction, resendProjectPvRulesAction } from '../pvRuleActions';

const ADD = 'POST /api/projects/7/sns/pv/rules';
const DELETE = 'DELETE /api/projects/7/sns/pv/rules/r3';
const RESEND = 'POST /api/projects/7/sns/pv/resend';

const sent = { addable: true, reason: null, rules: [], send: { state: 'SENT', error: null, at: null } };
const failed = { addable: true, reason: null, rules: [], send: { state: 'FAILED', error: '本番サイトに届きません', at: null } };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [ADD]: () => jsonResponse(sent),
    [DELETE]: () => jsonResponse(sent),
    [RESEND]: () => jsonResponse(sent),
  });
});

describe('PV 達成ルールの Server Action(issue #1578)', () => {
  describe('addProjectPvRuleAction', () => {
    it('期間と閾値を送ってルールを追加し、設定画面を再検証する', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'total', threshold: ' 5000 ' }));

      expect(result).toEqual({ success: true });
      expect(bodies(backend, ADD)).toEqual([{ period: 'total', threshold: 5000 }]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('本番サイトへの送信に失敗しても保存はされているので、成功として返し再検証する(失敗は一覧側に出る)', async () => {
      installBackend({ [ADD]: () => jsonResponse(failed) });

      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({ success: true });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('期間が daily でも total でもなければAPIを呼ばずエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'weekly', threshold: '100' }));

      expect(result.error).toContain('期間');
      expect(requestCount(backend)).toBe(0);
    });

    it('期間が未指定でもAPIを呼ばずエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ threshold: '100' }));

      expect(result.error).toContain('期間');
      expect(requestCount(backend)).toBe(0);
    });

    it.each(['', 'abc', '0', '-5', '1.5'])('閾値が「%s」ならAPIを呼ばずエラーを返す', async (threshold) => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold }));

      expect(result.error).toContain('閾値');
      expect(requestCount(backend)).toBe(0);
    });

    it('閾値が未指定でもエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'daily' }));

      expect(result.error).toContain('閾値');
    });

    it('GA が未連携などAPIの拒否はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [ADD]: failWith(409, 'GA が連携されていません') });
      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({
        error: 'APIエラー (409): GA が連携されていません',
      });

      failFetchWith('boom');
      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('deleteProjectPvRuleAction', () => {
    it('ルールを削除し、設定画面を再検証する', async () => {
      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('ルールIDはURLに使える形へエンコードして送る', async () => {
      const fetchMock = installBackend({ 'DELETE /api/projects/7/sns/pv/rules/a%2Fb': () => jsonResponse(sent) });

      expect(await deleteProjectPvRuleAction(7, 'a/b')).toEqual({ success: true });
      expect(requestCount(fetchMock)).toBe(1);
    });

    it('APIの失敗はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [DELETE]: failWith(404, '見つかりません') });
      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ error: 'APIエラー (404): 見つかりません' });
      failFetchWith('boom');
      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(deleteProjectPvRuleAction(7, 'r3')).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('resendProjectPvRulesAction', () => {
    it('再送が届いたら成功を返し、設定画面を再検証する', async () => {
      expect(await resendProjectPvRulesAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('再送しても届かなければ失敗の理由を返し、状態を見せるため再検証する', async () => {
      installBackend({ [RESEND]: () => jsonResponse(failed) });

      expect(await resendProjectPvRulesAction(7)).toEqual({ error: '本番サイトに届きません' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      installBackend({ [RESEND]: () => jsonResponse({ ...failed, send: { state: 'FAILED', error: null, at: null } }) });

      expect((await resendProjectPvRulesAction(7)).error).toContain('再送');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [RESEND]: failWith(502, '接続失敗') });
      expect(await resendProjectPvRulesAction(7)).toEqual({ error: 'APIエラー (502): 接続失敗' });
      failFetchWith('boom');
      expect(await resendProjectPvRulesAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(resendProjectPvRulesAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
