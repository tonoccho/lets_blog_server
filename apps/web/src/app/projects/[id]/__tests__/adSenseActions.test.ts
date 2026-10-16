/**
 * @jest-environment node
 */

/**
 * issue #1232: AdSense連携のServer Action(設定保存 / パブリッシャーID選択)の検証。
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
import { selectProjectAdSenseAccountAction, setProjectAdSenseSettingsAction } from '../actions';

const SETTINGS = 'PUT /api/projects/7/api-keys/adsense';
const SECRET = 'PUT /api/projects/7/api-keys/adsense/client-secret';
const ACCOUNT = 'PUT /api/projects/7/api-keys/adsense/account';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [SETTINGS]: noContent,
    [SECRET]: noContent,
    [ACCOUNT]: noContent,
  });
});

describe('AdSense連携のServer Action(issue #1232)', () => {
  describe('setProjectAdSenseSettingsAction', () => {
    it('パブリッシャーIDが空でもクライアントIDがあれば保存できる', async () => {
      const result = await setProjectAdSenseSettingsAction(
        7, {}, form({ accountId: '  ', clientId: ' cid ', clientSecret: '' })
      );

      expect(bodies(backend, SETTINGS)).toEqual([{ accountId: '', clientId: 'cid' }]);
      expect(bodies(backend, SECRET)).toEqual([]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/adsense');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('パブリッシャーIDを入力したときはそのまま送り、シークレットも保存する', async () => {
      await setProjectAdSenseSettingsAction(
        7, {}, form({ accountId: ' pub-1 ', clientId: 'cid', clientSecret: ' secret ' })
      );

      expect(bodies(backend, SETTINGS)).toEqual([{ accountId: 'pub-1', clientId: 'cid' }]);
      expect(bodies(backend, SECRET)).toEqual([{ clientSecret: 'secret' }]);
    });

    it('フォームにaccountIdが無くても保存できる', async () => {
      const result = await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }));

      expect(bodies(backend, SETTINGS)).toEqual([{ accountId: '', clientId: 'cid' }]);
      expect(result).toEqual({ success: true });
    });

    it('クライアントIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await setProjectAdSenseSettingsAction(7, {}, form({ accountId: 'pub-1', clientId: ' ' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('クライアントIDの欄そのものが無くてもAPIを呼ばずエラーを返す', async () => {
      const result = await setProjectAdSenseSettingsAction(7, {}, form({ accountId: 'pub-1' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('APIが失敗したらエラーメッセージを返す', async () => {
      installBackend({ [SETTINGS]: failWith(500, 'APIエラー') });
      expect(await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: 'APIエラー (500): APIエラー',
      });
    });

    it('シークレットの保存が失敗したらエラーを返し、再検証しない', async () => {
      installBackend({ [SETTINGS]: noContent, [SECRET]: failWith(500, 'secret NG') });
      expect(
        await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid', clientSecret: 's' }))
      ).toEqual({ error: 'APIエラー (500): secret NG' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('通信そのものがErrorでない値で失敗しても文字列化する', async () => {
      failFetchWith('失敗');
      expect(await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: '失敗',
      });
    });
  });

  describe('selectProjectAdSenseAccountAction', () => {
    it('選んだパブリッシャーIDを保存し、設定画面とダッシュボードを再検証する', async () => {
      const result = await selectProjectAdSenseAccountAction(
        7, {}, form({ selectedAccountId: ' pub-2222222222222222 ' })
      );

      expect(bodies(backend, ACCOUNT)).toEqual([{ accountId: 'pub-2222222222222222' }]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/adsense');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('未選択ならAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectAdSenseAccountAction(7, {}, form({}));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('選択');
    });

    it('APIが失敗したらエラーメッセージを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [ACCOUNT]: failWith(500, 'APIエラー') });
      expect(await selectProjectAdSenseAccountAction(7, {}, form({ selectedAccountId: 'pub-1' }))).toEqual({
        error: 'APIエラー (500): APIエラー',
      });

      failFetchWith('失敗');
      expect(await selectProjectAdSenseAccountAction(7, {}, form({ selectedAccountId: 'pub-1' }))).toEqual({
        error: '失敗',
      });
    });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(selectProjectAdSenseAccountAction(7, {}, form({ selectedAccountId: 'p' }))).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
