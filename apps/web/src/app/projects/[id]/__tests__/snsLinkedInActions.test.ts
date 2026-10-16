/**
 * @jest-environment node
 */

/**
 * issue #1581: 設定画面の LinkedIn 接続の Server Action(認可の開始 / テスト投稿 / 切断)。
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
import { bodies, failFetchWith, failWith, form, installBackend, jsonResponse, noContent, requestCount } from './projectCoreRuntime';
import {
  disconnectProjectLinkedInAction,
  startProjectLinkedInConnectionAction,
  testProjectLinkedInPostAction,
} from '../snsLinkedInActions';

const AUTHORIZE = 'POST /api/projects/7/sns/linkedin/authorize';
const TEST = 'POST /api/projects/7/sns/linkedin/test';
const DISCONNECT = 'DELETE /api/projects/7/sns/linkedin';
const CREDENTIALS = { linkedinAppId: 'c', linkedinAppSecret: 's' };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  process.env.NEXTAUTH_URL = 'https://localhost';
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [AUTHORIZE]: () => jsonResponse({ authorizeUrl: 'https://linkedin.example/authorize?state=7.abc' }),
    [TEST]: () => jsonResponse({ success: true, error: null }),
    [DISCONNECT]: noContent,
  });
});

describe('LinkedIn 接続の Server Action(issue #1581)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;
  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectLinkedInConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      await expect(
        startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: ' app-id ', linkedinAppSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://linkedin.example/authorize?state=7.abc');

      expect(bodies(backend, AUTHORIZE)).toEqual([
        { clientId: 'app-id', clientSecret: 'app-secret', redirectUri: 'https://localhost/connect/linkedin/callback' },
      ]);
    });

    it('Client IDが空(空白だけ)ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: ' ', linkedinAppSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Client ID');
    });

    it('Client IDの入力項目そのものが無くてもAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Client ID');
    });

    it('Client Secretが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: 'id' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Client Secret');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      installBackend({ [AUTHORIZE]: failWith(400, '本番サイトが設定されていません') });
      expect(await startProjectLinkedInConnectionAction(7, {}, form(CREDENTIALS))).toEqual({
        error: 'APIエラー (400): 本番サイトが設定されていません',
      });

      failFetchWith('boom');
      expect(await startProjectLinkedInConnectionAction(7, {}, form(CREDENTIALS))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(startProjectLinkedInConnectionAction(7, {}, form(CREDENTIALS))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('testProjectLinkedInPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      expect(await testProjectLinkedInPostAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(bodies(backend, TEST)).toEqual([undefined]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: 'LinkedIn の投稿に失敗しました(HTTP 403)' }) });

      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: 'LinkedIn の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: null }) });

      expect((await testProjectLinkedInPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [TEST]: failWith(502, '接続失敗') });
      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: 'APIエラー (502): 接続失敗' });
      failFetchWith('boom');
      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(testProjectLinkedInPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('disconnectProjectLinkedInAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      expect(await disconnectProjectLinkedInAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [DISCONNECT]: failWith(502, '本番サイトに届かない') });
      expect(await disconnectProjectLinkedInAction(7)).toEqual({ error: 'APIエラー (502): 本番サイトに届かない' });
      failFetchWith('boom');
      expect(await disconnectProjectLinkedInAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(disconnectProjectLinkedInAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
