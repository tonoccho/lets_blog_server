/**
 * @jest-environment node
 */

/**
 * issue #1580: 設定画面の Facebook ページ接続の Server Action(認可の開始 / 投稿先ページの選択 / テスト投稿 / 切断)。
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
  disconnectProjectFacebookAction,
  selectProjectFacebookPageAction,
  startProjectFacebookConnectionAction,
  testProjectFacebookPostAction,
} from '../snsFacebookActions';

const AUTHORIZE = 'POST /api/projects/7/sns/facebook/authorize';
const PAGE = 'POST /api/projects/7/sns/facebook/page';
const TEST = 'POST /api/projects/7/sns/facebook/test';
const DISCONNECT = 'DELETE /api/projects/7/sns/facebook';
const CREDENTIALS = { facebookAppId: 'c', facebookAppSecret: 's' };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  process.env.NEXTAUTH_URL = 'https://localhost';
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [AUTHORIZE]: () => jsonResponse({ authorizeUrl: 'https://facebook.example/dialog/oauth?state=7.abc' }),
    [PAGE]: () => jsonResponse({ projectId: 7, accountName: '公式ページ' }),
    [TEST]: () => jsonResponse({ success: true, error: null }),
    [DISCONNECT]: noContent,
  });
});

describe('Facebook ページ接続の Server Action(issue #1580)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;
  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectFacebookConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      await expect(
        startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: ' app-id ', facebookAppSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://facebook.example/dialog/oauth?state=7.abc');

      expect(bodies(backend, AUTHORIZE)).toEqual([
        { clientId: 'app-id', clientSecret: 'app-secret', redirectUri: 'https://localhost/connect/facebook/callback' },
      ]);
    });

    it('アプリIDが空(空白だけ)ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: ' ', facebookAppSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('アプリID');
    });

    it('アプリIDの入力項目そのものが無くてもAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectFacebookConnectionAction(7, {}, form({ facebookAppSecret: 's' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('アプリID');
    });

    it('アプリシークレットが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: 'id' }));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('シークレット');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      installBackend({ [AUTHORIZE]: failWith(400, '本番サイトが設定されていません') });
      expect(await startProjectFacebookConnectionAction(7, {}, form(CREDENTIALS))).toEqual({
        error: 'APIエラー (400): 本番サイトが設定されていません',
      });

      failFetchWith('boom');
      expect(await startProjectFacebookConnectionAction(7, {}, form(CREDENTIALS))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(startProjectFacebookConnectionAction(7, {}, form(CREDENTIALS))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('selectProjectFacebookPageAction', () => {
    it('選んだページをバックエンドへ送り、成功したら接続完了として設定画面へリダイレクトする', async () => {
      await expect(
        selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))
      ).rejects.toThrow('NEXT_REDIRECT:/projects/7/settings/sns?connected=facebook');

      expect(bodies(backend, PAGE)).toEqual([{ state: '7.abc', pageId: '100' }]);
    });

    it('ページが選ばれていなければAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectFacebookPageAction(7, '7.abc', {}, form({}));

      expect(requestCount(backend)).toBe(0);
      expect(result.error).toContain('ページ');
    });

    it('選択に失敗したら理由を返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      installBackend({ [PAGE]: failWith(502, '本番サイトへトークンを送れませんでした(接続失敗)') });
      expect(await selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))).toEqual({
        error: 'APIエラー (502): 本番サイトへトークンを送れませんでした(接続失敗)',
      });
      failFetchWith('boom');
      expect(await selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(
        selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('testProjectFacebookPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      expect(await testProjectFacebookPostAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: 'Facebook の投稿に失敗しました(HTTP 403)' }) });

      expect(await testProjectFacebookPostAction(7)).toEqual({ error: 'Facebook の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      installBackend({ [TEST]: () => jsonResponse({ success: false, error: null }) });

      expect((await testProjectFacebookPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [TEST]: failWith(502, '接続失敗') });
      expect(await testProjectFacebookPostAction(7)).toEqual({ error: 'APIエラー (502): 接続失敗' });
      failFetchWith('boom');
      expect(await testProjectFacebookPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(testProjectFacebookPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('disconnectProjectFacebookAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      expect(await disconnectProjectFacebookAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [DISCONNECT]: failWith(502, '本番サイトに届かない') });
      expect(await disconnectProjectFacebookAction(7)).toEqual({ error: 'APIエラー (502): 本番サイトに届かない' });
      failFetchWith('boom');
      expect(await disconnectProjectFacebookAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(disconnectProjectFacebookAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
