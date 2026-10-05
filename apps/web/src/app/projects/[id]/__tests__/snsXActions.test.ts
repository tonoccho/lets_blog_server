/**
 * @jest-environment node
 */

/**
 * issue #1574: 設定画面の X 接続の Server Action(認可の開始 / テスト投稿)。
 * `../actions` と同様に実体を直接 import する。いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
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
jest.mock('@/lib/apiClient', () => ({
  startProjectXAuthorization: (...args: unknown[]) => startAuthorization(...args),
  testProjectXPost: (...args: unknown[]) => testPost(...args),
}));

import { startProjectXConnectionAction, testProjectXPostAction } from '../snsXActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('X 接続の Server Action(issue #1574)', () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = 'https://localhost';
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  describe('startProjectXConnectionAction', () => {
    it('クライアントの情報とアプリのコールバックURLを送り、返った認可URLへリダイレクトする', async () => {
      startAuthorization.mockResolvedValue({ authorizeUrl: 'https://x.example/authorize?state=7.abc' });

      await expect(
        startProjectXConnectionAction(7, {}, form({ clientId: ' cid ', clientSecret: ' csecret ' }))
      ).rejects.toThrow('NEXT_REDIRECT:https://x.example/authorize?state=7.abc');

      expect(requireAdminSession).toHaveBeenCalled();
      expect(startAuthorization).toHaveBeenCalledWith(7, {
        clientId: 'cid',
        clientSecret: 'csecret',
        redirectUri: 'https://localhost/connect/x/callback',
      });
    });

    it('クライアントIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectXConnectionAction(7, {}, form({ clientId: ' ', clientSecret: 's' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('クライアントID');
    });

    it('クライアントシークレットが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await startProjectXConnectionAction(7, {}, form({ clientId: 'cid' }));

      expect(startAuthorization).not.toHaveBeenCalled();
      expect(result.error).toContain('シークレット');
    });

    it('接続できない理由はエラーとして返し、リダイレクトしない(Errorでない例外も文字列化する)', async () => {
      startAuthorization.mockRejectedValueOnce(new Error('本番サイトが設定されていません'));
      expect(await startProjectXConnectionAction(7, {}, form({ clientId: 'c', clientSecret: 's' }))).toEqual({
        error: '本番サイトが設定されていません',
      });

      startAuthorization.mockRejectedValueOnce('boom');
      expect(await startProjectXConnectionAction(7, {}, form({ clientId: 'c', clientSecret: 's' }))).toEqual({
        error: 'boom',
      });
      expect(redirect).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(
        startProjectXConnectionAction(7, {}, form({ clientId: 'c', clientSecret: 's' }))
      ).rejects.toThrow('NEXT_REDIRECT');
      expect(startAuthorization).not.toHaveBeenCalled();
    });
  });

  describe('testProjectXPostAction', () => {
    it('テスト投稿が成功したら成功を返し、設定画面を再検証する', async () => {
      testPost.mockResolvedValue({ success: true, error: null });

      expect(await testProjectXPostAction(7)).toEqual({ success: true });
      expect(testPost).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('テスト投稿が失敗したら理由を返し、履歴を見せるため再検証する', async () => {
      testPost.mockResolvedValue({ success: false, error: 'X の投稿に失敗しました(HTTP 403)' });

      expect(await testProjectXPostAction(7)).toEqual({ error: 'X の投稿に失敗しました(HTTP 403)' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      testPost.mockResolvedValue({ success: false, error: null });

      const result = await testProjectXPostAction(7);
      expect(result.error).toContain('テスト投稿');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      testPost.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await testProjectXPostAction(7)).toEqual({ error: '接続失敗' });
      testPost.mockRejectedValueOnce('boom');
      expect(await testProjectXPostAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(testProjectXPostAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(testPost).not.toHaveBeenCalled();
    });
  });
});
