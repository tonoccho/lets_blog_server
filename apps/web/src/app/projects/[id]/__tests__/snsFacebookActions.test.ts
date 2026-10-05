/**
 * @jest-environment node
 */

/**
 * issue #1580: 設定画面の Facebook ページ接続の Server Action(認可の開始 / 投稿先ページの選択 / テスト投稿 / 切断)。
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
const selectPage = jest.fn();
const testPost = jest.fn();
const disconnect = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startProjectFacebookAuthorization: (...args: unknown[]) => startAuthorization(...args),
  selectProjectFacebookPage: (...args: unknown[]) => selectPage(...args),
  testProjectFacebookPost: (...args: unknown[]) => testPost(...args),
  disconnectProjectFacebook: (...args: unknown[]) => disconnect(...args),
}));

import {
  disconnectProjectFacebookAction,
  selectProjectFacebookPageAction,
  startProjectFacebookConnectionAction,
  testProjectFacebookPostAction,
} from '../snsFacebookActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('Facebook ページ接続の Server Action(issue #1580)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = 'https://localhost';
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectFacebookConnectionAction', () => {
    it('アプリの情報とコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      startAuthorization.mockResolvedValue({ authorizeUrl: 'https://facebook.example/dialog/oauth?state=7.abc' });

      await expect(
        startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: ' app-id ', facebookAppSecret: ' app-secret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://facebook.example/dialog/oauth?state=7.abc');

      expect(requireAdminSession).toHaveBeenCalled();
      expect(startAuthorization).toHaveBeenCalledWith(7, {
        clientId: 'app-id',
        clientSecret: 'app-secret',
        redirectUri: 'https://localhost/connect/facebook/callback',
      });
    });

    it('アプリIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: ' ', facebookAppSecret: 's' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('アプリID');
    });

    it('アプリシークレットが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: 'id' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('シークレット');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      startAuthorization.mockRejectedValueOnce(new Error('本番サイトが設定されていません'));
      expect(await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: 'c', facebookAppSecret: 's' }))).toEqual({
        error: '本番サイトが設定されていません',
      });

      startAuthorization.mockRejectedValueOnce('boom');
      expect(await startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: 'c', facebookAppSecret: 's' }))).toEqual({
        error: 'boom',
      });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        startProjectFacebookConnectionAction(7, {}, form({ facebookAppId: 'c', facebookAppSecret: 's' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(startAuthorization).not.toHaveBeenCalled();
    });
  });

  describe('selectProjectFacebookPageAction', () => {
    it('選んだページをバックエンドへ送り、成功したら接続完了として設定画面へリダイレクトする', async () => {
      selectPage.mockResolvedValue({ projectId: 7, accountName: '公式ページ' });

      await expect(
        selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))
      ).rejects.toThrow('NEXT_REDIRECT:/projects/7/settings/sns?connected=facebook');

      expect(selectPage).toHaveBeenCalledWith(7, { state: '7.abc', pageId: '100' });
    });

    it('ページが選ばれていなければAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectFacebookPageAction(7, '7.abc', {}, form({}));

      expect(selectPage).not.toHaveBeenCalled();
      expect(result.error).toContain('ページ');
    });

    it('選択に失敗したら理由を返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      selectPage.mockRejectedValueOnce(new Error('本番サイトへトークンを送れませんでした(接続失敗)'));
      expect(await selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))).toEqual({
        error: '本番サイトへトークンを送れませんでした(接続失敗)',
      });
      selectPage.mockRejectedValueOnce('boom');
      expect(await selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))).toEqual({ error: 'boom' });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        selectProjectFacebookPageAction(7, '7.abc', {}, form({ facebookPageId: '100' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(selectPage).not.toHaveBeenCalled();
    });
  });

  describe('testProjectFacebookPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      testPost.mockResolvedValue({ success: true, error: null });

      expect(await testProjectFacebookPostAction(7)).toEqual({ success: true });
      expect(testPost).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      testPost.mockResolvedValue({ success: false, error: 'Facebook の投稿に失敗しました(HTTP 403)' });

      expect(await testProjectFacebookPostAction(7)).toEqual({ error: 'Facebook の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      testPost.mockResolvedValue({ success: false, error: null });

      expect((await testProjectFacebookPostAction(7)).error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      testPost.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await testProjectFacebookPostAction(7)).toEqual({ error: '接続失敗' });
      testPost.mockRejectedValueOnce('boom');
      expect(await testProjectFacebookPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(testProjectFacebookPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(testPost).not.toHaveBeenCalled();
    });
  });

  describe('disconnectProjectFacebookAction', () => {
    it('切断して設定画面を再検証し、成功を返す', async () => {
      disconnect.mockResolvedValue(undefined);

      expect(await disconnectProjectFacebookAction(7)).toEqual({ success: true });
      expect(disconnect).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('切断できなければ理由を返す(Errorでない例外も文字列化する)', async () => {
      disconnect.mockRejectedValueOnce(new Error('本番サイトに届かない'));
      expect(await disconnectProjectFacebookAction(7)).toEqual({ error: '本番サイトに届かない' });
      disconnect.mockRejectedValueOnce('boom');
      expect(await disconnectProjectFacebookAction(7)).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(disconnectProjectFacebookAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(disconnect).not.toHaveBeenCalled();
    });
  });
});
