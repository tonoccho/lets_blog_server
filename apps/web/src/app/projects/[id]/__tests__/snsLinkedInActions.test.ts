/**
 * @jest-environment node
 */

/**
 * issue #1581: 設定画面の LinkedIn 接続の Server Action(認可の開始 / テスト投稿 / 切断)。
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
  startProjectLinkedInAuthorization: (...args: unknown[]) => startAuthorization(...args),
  testProjectLinkedInPost: (...args: unknown[]) => testPost(...args),
  disconnectProjectLinkedIn: (...args: unknown[]) => disconnect(...args),
}));

import {
  disconnectProjectLinkedInAction,
  startProjectLinkedInConnectionAction,
  testProjectLinkedInPostAction,
} from '../snsLinkedInActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('LinkedIn 接続の Server Action(issue #1581)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = 'https://localhost';
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectLinkedInConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      startAuthorization.mockResolvedValue({ authorizeUrl: 'https://linkedin.example/authorize?state=7.abc' });

      await expect(
        startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: ' app-id ', linkedinAppSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://linkedin.example/authorize?state=7.abc');

      expect(requireAdminSession).toHaveBeenCalled();
      expect(startAuthorization).toHaveBeenCalledWith(7, {
        clientId: 'app-id',
        clientSecret: 'app-secret',
        redirectUri: 'https://localhost/connect/linkedin/callback',
      });
    });

    it('Client IDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: ' ', linkedinAppSecret: 's' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('Client ID');
    });

    it('Client Secretが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: 'id' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('Client Secret');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      startAuthorization.mockRejectedValueOnce(new Error('本番サイトが設定されていません'));
      expect(await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: 'c', linkedinAppSecret: 's' }))).toEqual({
        error: '本番サイトが設定されていません',
      });

      startAuthorization.mockRejectedValueOnce('boom');
      expect(await startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: 'c', linkedinAppSecret: 's' }))).toEqual({
        error: 'boom',
      });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        startProjectLinkedInConnectionAction(7, {}, form({ linkedinAppId: 'c', linkedinAppSecret: 's' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(startAuthorization).not.toHaveBeenCalled();
    });
  });

  describe('testProjectLinkedInPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      testPost.mockResolvedValue({ success: true, error: null });

      expect(await testProjectLinkedInPostAction(7)).toEqual({ success: true });
      expect(testPost).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      testPost.mockResolvedValue({ success: false, error: 'LinkedIn の投稿に失敗しました(HTTP 403)' });

      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: 'LinkedIn の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      testPost.mockResolvedValue({ success: false, error: null });

      expect((await testProjectLinkedInPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      testPost.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: '接続失敗' });
      testPost.mockRejectedValueOnce('boom');
      expect(await testProjectLinkedInPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(testProjectLinkedInPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(testPost).not.toHaveBeenCalled();
    });
  });

  describe('disconnectProjectLinkedInAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      disconnect.mockResolvedValue(undefined);

      expect(await disconnectProjectLinkedInAction(7)).toEqual({ success: true });
      expect(disconnect).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      disconnect.mockRejectedValueOnce(new Error('本番サイトに届かない'));
      expect(await disconnectProjectLinkedInAction(7)).toEqual({ error: '本番サイトに届かない' });
      disconnect.mockRejectedValueOnce('boom');
      expect(await disconnectProjectLinkedInAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(disconnectProjectLinkedInAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(disconnect).not.toHaveBeenCalled();
    });
  });
});
