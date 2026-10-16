/**
 * @jest-environment node
 */

/**
 * issue #1574: 設定画面の X 接続の Server Action(認可の開始 / テスト投稿)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
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
import {
  startProjectXConnectionAction,
  testProjectXPostAction,
} from '../snsXActions';

const AUTHORIZE = 'POST /api/projects/7/sns/x/authorize';
const TEST = 'POST /api/projects/7/sns/x/test';

const CREDENTIALS = { clientId: 'c', clientSecret: 's' };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  process.env.NEXTAUTH_URL = 'https://localhost';
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [AUTHORIZE]: () => jsonResponse({ authorizeUrl: 'https://x.example/authorize?state=7.abc' }),
    [TEST]: () => jsonResponse({ success: true, error: null }),
  });
});

describe('X 接続の Server Action(issue #1574)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;
  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectXConnectionAction', () => {
    it('クライアントの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      await expect(
        startProjectXConnectionAction(7, {}, form({ clientId: ' app-id ', clientSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://x.example/authorize?state=7.abc');

      expect(bodies(backend, AUTHORIZE)).toEqual([
        { clientId: 'app-id', clientSecret: 'app-secret', redirectUri: 'https://localhost/connect/x/callback' },
      ]);
    });

    it('クライアントIDが空(空白だけ)ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectXConnectionAction(7, {}, form({ clientId: ' ', clientSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('クライアントIDの入力項目そのものが無くてもAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectXConnectionAction(7, {}, form({ clientSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('クライアントID');
    });

    it('シークレットが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectXConnectionAction(7, {}, form({ clientId: 'id' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('シークレット');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      installBackend({ [AUTHORIZE]: failWith(400, '本番サイトが設定されていません') });
      expect(await startProjectXConnectionAction(7, {}, form(CREDENTIALS))).toEqual({
        error: 'APIエラー (400): 本番サイトが設定されていません',
      });

      failFetchWith('boom');
      expect(await startProjectXConnectionAction(7, {}, form(CREDENTIALS))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(startProjectXConnectionAction(7, {}, form(CREDENTIALS))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('testProjectXPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      expect(await testProjectXPostAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(bodies(backend, TEST)).toEqual([undefined]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: 'X の投稿に失敗しました(HTTP 403)' }) });

      expect(await testProjectXPostAction(7)).toEqual({ error: 'X の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: null }) });

      expect((await testProjectXPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [TEST]: failWith(502, '接続失敗') });
      expect(await testProjectXPostAction(7)).toEqual({ error: 'APIエラー (502): 接続失敗' });
      failFetchWith('boom');
      expect(await testProjectXPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(testProjectXPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
