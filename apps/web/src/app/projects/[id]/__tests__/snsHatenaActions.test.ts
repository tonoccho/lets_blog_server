/**
 * @jest-environment node
 */

/**
 * issue #1582: 設定画面の はてなブックマーク 接続の Server Action(認可の開始 / テスト投稿 / 切断)。
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
  disconnectProjectHatenaAction,
  startProjectHatenaConnectionAction,
  testProjectHatenaPostAction,
} from '../snsHatenaActions';

const AUTHORIZE = 'POST /api/projects/7/sns/hatena/authorize';
const TEST = 'POST /api/projects/7/sns/hatena/test';
const DISCONNECT = 'DELETE /api/projects/7/sns/hatena';
const CREDENTIALS = { hatenaConsumerKey: 'c', hatenaConsumerSecret: 's' };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  process.env.NEXTAUTH_URL = 'https://localhost';
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [AUTHORIZE]: () => jsonResponse({ authorizeUrl: 'https://hatena.example/authorize?state=7.abc' }),
    [TEST]: () => jsonResponse({ success: true, error: null }),
    [DISCONNECT]: noContent,
  });
});

describe('はてなブックマーク 接続の Server Action(issue #1582)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;
  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectHatenaConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      await expect(
        startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: ' app-id ', hatenaConsumerSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://hatena.example/authorize?state=7.abc');

      expect(bodies(backend, AUTHORIZE)).toEqual([
        { clientId: 'app-id', clientSecret: 'app-secret', redirectUri: 'https://localhost/connect/hatena/callback' },
      ]);
    });

    it('Consumer Keyが空(空白だけ)ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: ' ', hatenaConsumerSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Consumer Key');
    });

    it('Consumer Keyの入力項目そのものが無くてもAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Consumer Key');
    });

    it('Consumer Secretが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: 'id' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('Consumer Secret');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      installBackend({ [AUTHORIZE]: failWith(400, '本番サイトが設定されていません') });
      expect(await startProjectHatenaConnectionAction(7, {}, form(CREDENTIALS))).toEqual({
        error: 'APIエラー (400): 本番サイトが設定されていません',
      });

      failFetchWith('boom');
      expect(await startProjectHatenaConnectionAction(7, {}, form(CREDENTIALS))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(startProjectHatenaConnectionAction(7, {}, form(CREDENTIALS))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('testProjectHatenaPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      expect(await testProjectHatenaPostAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(bodies(backend, TEST)).toEqual([undefined]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: 'はてなブックマーク の投稿に失敗しました(HTTP 403)' }) });

      expect(await testProjectHatenaPostAction(7)).toEqual({ error: 'はてなブックマーク の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: null }) });

      expect((await testProjectHatenaPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [TEST]: failWith(502, '接続失敗') });
      expect(await testProjectHatenaPostAction(7)).toEqual({ error: 'APIエラー (502): 接続失敗' });
      failFetchWith('boom');
      expect(await testProjectHatenaPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(testProjectHatenaPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('disconnectProjectHatenaAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      expect(await disconnectProjectHatenaAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [DISCONNECT]: failWith(502, '本番サイトに届かない') });
      expect(await disconnectProjectHatenaAction(7)).toEqual({ error: 'APIエラー (502): 本番サイトに届かない' });
      failFetchWith('boom');
      expect(await disconnectProjectHatenaAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(disconnectProjectHatenaAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
