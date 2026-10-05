/**
 * @jest-environment node
 */

/**
 * issue #1579: 設定画面の Threads 接続の Server Action(認可の開始 / テスト投稿 / 切断)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));
const redirect = jest.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT:${url}`);
});
jest.mock('next/navigation', () => ({ redirect: (url: string) => redirect(url) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startAuthorization = jest.fn();
const testPost = jest.fn();
const disconnect = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startProjectThreadsAuthorization: (...args: unknown[]) => startAuthorization(...args),
  testProjectThreadsPost: (...args: unknown[]) => testPost(...args),
  disconnectProjectThreads: (...args: unknown[]) => disconnect(...args),
}));

import {
  disconnectProjectThreadsAction,
  startProjectThreadsConnectionAction,
  testProjectThreadsPostAction,
} from '../snsThreadsActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('Threads 接続の Server Action(issue #1579)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = 'https://localhost';
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectThreadsConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      startAuthorization.mockResolvedValue({ authorizeUrl: 'https://threads.example/authorize?state=7.abc' });

      await expect(
        startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: ' app-id ', threadsAppSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://threads.example/authorize?state=7.abc');

      expect(requireAdminSession).toHaveBeenCalled();
      expect(startAuthorization).toHaveBeenCalledWith(7, {
        clientId: 'app-id',
        clientSecret: 'app-secret',
        redirectUri: 'https://localhost/connect/threads/callback',
      });
    });

    it('アプリIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: ' ', threadsAppSecret: 's' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('アプリID');
    });

    it('アプリシークレットが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: 'id' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('シークレット');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      startAuthorization.mockRejectedValueOnce(new Error('本番サイトが設定されていません'));
      expect(await startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: 'c', threadsAppSecret: 's' }))).toEqual({
        error: '本番サイトが設定されていません',
      });

      startAuthorization.mockRejectedValueOnce('boom');
      expect(await startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: 'c', threadsAppSecret: 's' }))).toEqual({
        error: 'boom',
      });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        startProjectThreadsConnectionAction(7, {}, form({ threadsAppId: 'c', threadsAppSecret: 's' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(startAuthorization).not.toHaveBeenCalled();
    });
  });

  describe('testProjectThreadsPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      testPost.mockResolvedValue({ success: true, error: null });

      expect(await testProjectThreadsPostAction(7)).toEqual({ success: true });
      expect(testPost).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      testPost.mockResolvedValue({ success: false, error: 'Threads の投稿に失敗しました(HTTP 403)' });

      expect(await testProjectThreadsPostAction(7)).toEqual({ error: 'Threads の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      testPost.mockResolvedValue({ success: false, error: null });

      expect((await testProjectThreadsPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      testPost.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await testProjectThreadsPostAction(7)).toEqual({ error: '接続失敗' });
      testPost.mockRejectedValueOnce('boom');
      expect(await testProjectThreadsPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(testProjectThreadsPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(testPost).not.toHaveBeenCalled();
    });
  });

  describe('disconnectProjectThreadsAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      disconnect.mockResolvedValue(undefined);

      expect(await disconnectProjectThreadsAction(7)).toEqual({ success: true });
      expect(disconnect).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      disconnect.mockRejectedValueOnce(new Error('本番サイトに届かない'));
      expect(await disconnectProjectThreadsAction(7)).toEqual({ error: '本番サイトに届かない' });
      disconnect.mockRejectedValueOnce('boom');
      expect(await disconnectProjectThreadsAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(disconnectProjectThreadsAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(disconnect).not.toHaveBeenCalled();
    });
  });
});
