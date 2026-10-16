/**
 * @jest-environment node
 */

/**
 * issue #1231: GA連携のServer Action(OAuthクライアント保存 / プロパティ選択 / 解除)の検証。
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
import { bodies, failFetchWith, failWith, form, installBackend, noContent, requestCount } from './projectCoreRuntime';
import {
  clearProjectGoogleAnalyticsCredentialsAction,
  selectProjectGoogleAnalyticsPropertyAction,
  setProjectGoogleAnalyticsClientAction,
} from '../actions';

const CLIENT = 'PUT /api/projects/7/api-keys/google-analytics/client';
const PROPERTY = 'PUT /api/projects/7/api-keys/google-analytics/property';
const CLEAR = 'DELETE /api/projects/7/api-keys/google-analytics';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [CLIENT]: noContent, [PROPERTY]: noContent, [CLEAR]: noContent });
});

describe('GA連携のServer Action(issue #1231)', () => {
  describe('setProjectGoogleAnalyticsClientAction', () => {
    it('クライアントIDとシークレットを保存し、設定画面を再検証する', async () => {
      const result = await setProjectGoogleAnalyticsClientAction(
        7, {}, form({ clientId: ' cid ', clientSecret: ' secret ' })
      );

      expect(bodies(backend, CLIENT)).toEqual([{ clientId: 'cid', clientSecret: 'secret' }]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
      expect(result).toEqual({ success: true });
    });

    it('シークレット欄が空ならシークレットを送らない(既存を維持)', async () => {
      await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid', clientSecret: '' }));

      // undefined は JSON にならず、キー自体が送られない
      expect(bodies(backend, CLIENT)).toEqual([{ clientId: 'cid' }]);
    });

    it('シークレット欄そのものが無くても(formDataに無くても)シークレットを送らない', async () => {
      await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid' }));

      expect(bodies(backend, CLIENT)).toEqual([{ clientId: 'cid' }]);
    });

    it('クライアントIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: ' ' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('クライアントIDの欄そのものが無くてもエラーを返す', async () => {
      const result = await setProjectGoogleAnalyticsClientAction(7, {}, form({}));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('APIが失敗したらエラーメッセージを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [CLIENT]: failWith(500, 'APIエラー') });
      expect(await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: 'APIエラー (500): APIエラー',
      });

      failFetchWith('文字列の失敗');
      expect(await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: '文字列の失敗',
      });
    });
  });

  describe('selectProjectGoogleAnalyticsPropertyAction', () => {
    it('選択したプロパティを保存し、ダッシュボードも再検証する', async () => {
      const result = await selectProjectGoogleAnalyticsPropertyAction(
        7, {}, form({ propertyId: '987654321' })
      );

      expect(bodies(backend, PROPERTY)).toEqual([{ propertyId: '987654321' }]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('プロパティが選ばれていなければAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('プロパティ');
    });

    it('プロパティの欄そのものが無くてもエラーを返す', async () => {
      const result = await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({}));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('プロパティ');
    });

    it('APIが失敗したらエラーメッセージを返す', async () => {
      installBackend({ [PROPERTY]: failWith(409, '連携していません') });
      expect(await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '1' }))).toEqual({
        error: 'APIエラー (409): 連携していません',
      });

      failFetchWith('失敗');
      expect(await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '1' }))).toEqual({
        error: '失敗',
      });
    });
  });

  it('clearProjectGoogleAnalyticsCredentialsActionは連携を解除して画面を再検証する', async () => {
    await clearProjectGoogleAnalyticsCredentialsAction(7);

    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(clearProjectGoogleAnalyticsCredentialsAction(7)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
