/**
 * @jest-environment node
 */

/**
 * issue #1582: 設定画面の はてなブックマーク 接続の Server Action(認可の開始 / テスト投稿 / 切断)。
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
  startProjectHatenaAuthorization: (...args: unknown[]) => startAuthorization(...args),
  testProjectHatenaPost: (...args: unknown[]) => testPost(...args),
  disconnectProjectHatena: (...args: unknown[]) => disconnect(...args),
}));

import {
  disconnectProjectHatenaAction,
  startProjectHatenaConnectionAction,
  testProjectHatenaPostAction,
} from '../snsHatenaActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('はてなブックマーク 接続の Server Action(issue #1582)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = 'https://localhost';
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectHatenaConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      startAuthorization.mockResolvedValue({ authorizeUrl: 'https://hatena.example/authorize?state=7.abc' });

      await expect(
        startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: ' app-id ', hatenaConsumerSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://hatena.example/authorize?state=7.abc');

      expect(requireAdminSession).toHaveBeenCalled();
      expect(startAuthorization).toHaveBeenCalledWith(7, {
        clientId: 'app-id',
        clientSecret: 'app-secret',
        redirectUri: 'https://localhost/connect/hatena/callback',
      });
    });

    it('Consumer Keyが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: ' ', hatenaConsumerSecret: 's' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('Consumer Key');
    });

    it('Consumer Secretが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: 'id' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('Consumer Secret');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      startAuthorization.mockRejectedValueOnce(new Error('本番サイトが設定されていません'));
      expect(await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: 'c', hatenaConsumerSecret: 's' }))).toEqual({
        error: '本番サイトが設定されていません',
      });

      startAuthorization.mockRejectedValueOnce('boom');
      expect(await startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: 'c', hatenaConsumerSecret: 's' }))).toEqual({
        error: 'boom',
      });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        startProjectHatenaConnectionAction(7, {}, form({ hatenaConsumerKey: 'c', hatenaConsumerSecret: 's' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(startAuthorization).not.toHaveBeenCalled();
    });
  });

  describe('testProjectHatenaPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      testPost.mockResolvedValue({ success: true, error: null });

      expect(await testProjectHatenaPostAction(7)).toEqual({ success: true });
      expect(testPost).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      testPost.mockResolvedValue({ success: false, error: 'はてなブックマーク の投稿に失敗しました(HTTP 403)' });

      expect(await testProjectHatenaPostAction(7)).toEqual({ error: 'はてなブックマーク の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      testPost.mockResolvedValue({ success: false, error: null });

      expect((await testProjectHatenaPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      testPost.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await testProjectHatenaPostAction(7)).toEqual({ error: '接続失敗' });
      testPost.mockRejectedValueOnce('boom');
      expect(await testProjectHatenaPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(testProjectHatenaPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(testPost).not.toHaveBeenCalled();
    });
  });

  describe('disconnectProjectHatenaAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      disconnect.mockResolvedValue(undefined);

      expect(await disconnectProjectHatenaAction(7)).toEqual({ success: true });
      expect(disconnect).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      disconnect.mockRejectedValueOnce(new Error('本番サイトに届かない'));
      expect(await disconnectProjectHatenaAction(7)).toEqual({ error: '本番サイトに届かない' });
      disconnect.mockRejectedValueOnce('boom');
      expect(await disconnectProjectHatenaAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(disconnectProjectHatenaAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(disconnect).not.toHaveBeenCalled();
    });
  });
});
