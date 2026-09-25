/**
 * @jest-environment node
 */
import { NextRequest } from 'next/server';
import { getToken } from 'next-auth/jwt';
import { proxy } from '../proxy';

jest.mock('next-auth/jwt', () => ({ getToken: jest.fn() }));
jest.mock('@/lib/apiBaseUrl', () => ({
  gatewayUrl: (path: string) => `http://gateway:8080${path}`,
}));

const mockGetToken = getToken as jest.MockedFunction<typeof getToken>;

function request(pathname: string): NextRequest {
  return new NextRequest(`https://localhost${pathname}`, { method: 'POST' });
}

describe('proxy', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    jest.clearAllMocks();
    // needsInitialSetup() が false を返すようにして、未認証時の遷移先を /login に固定する。
    global.fetch = jest.fn().mockResolvedValue({ ok: false } as Response);
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  describe('/client-errors (issue #791)', () => {
    it('未認証でも素通しする(認証判定はRoute Handler自身が行う)', async () => {
      mockGetToken.mockResolvedValue(null);

      const res = await proxy(request('/client-errors'));

      // リダイレクトせず後段へ渡す。fire-and-forgetのビーコンにリダイレクトを返しても
      // 送信側は見ないため意味が無く、needsInitialSetup()のgateway呼び出しも無駄になる。
      expect(res.headers.get('location')).toBeNull();
      expect(mockGetToken).not.toHaveBeenCalled();
      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('操作IDは素通し時も付与される', async () => {
      mockGetToken.mockResolvedValue(null);

      const res = await proxy(request('/client-errors'));

      expect(res.headers.get('location')).toBeNull();
      expect(res.status).toBe(200);
    });

    // 素通しは完全一致でなければならない。前方一致にすると client-errors で始まる
    // 別のルートを足した時点で認証ゲートが無言で外れる(ADR-0008)。
    it.each([
      ['/client-errors-foo'],
      ['/client-errorsXYZ'],
      ['/client-errors/nested'],
    ])('%s は素通しせず認証ゲートを通す', async (pathname) => {
      mockGetToken.mockResolvedValue(null);

      const res = await proxy(request(pathname));

      expect(res.headers.get('location')).toBe('https://localhost/login');
      expect(mockGetToken).toHaveBeenCalled();
    });
  });

  describe('既存の認証ゲート', () => {
    it('未認証の保護ルートは /login へリダイレクトする', async () => {
      mockGetToken.mockResolvedValue(null);

      const res = await proxy(request('/projects'));

      expect(res.headers.get('location')).toBe('https://localhost/login');
    });

    it('token.error(リフレッシュ失敗)も未認証と同じ扱いにする', async () => {
      mockGetToken.mockResolvedValue({ error: 'RefreshAccessTokenError' } as never);

      const res = await proxy(request('/projects'));

      expect(res.headers.get('location')).toBe('https://localhost/login');
    });

    it('admin限定プレフィックスに一般ユーザーが来たら / へ戻す', async () => {
      mockGetToken.mockResolvedValue({ role: 'user' } as never);

      const res = await proxy(request('/users'));

      expect(res.headers.get('location')).toBe('https://localhost/');
    });

    it('ログイン済みなら素通しする', async () => {
      mockGetToken.mockResolvedValue({ role: 'admin' } as never);

      const res = await proxy(request('/projects'));

      expect(res.headers.get('location')).toBeNull();
    });
  });

  describe('/users/{id}/edit の自己アクセス例外 (issue #1313)', () => {
    it('一般ユーザーが自分自身のIDの編集画面へ来たら素通しする', async () => {
      mockGetToken.mockResolvedValue({ role: 'user', accessToken: 'token-abc' } as never);
      global.fetch = jest.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ id: 42 }),
      } as Response);

      const res = await proxy(request('/users/42/edit'));

      expect(res.headers.get('location')).toBeNull();
      expect(global.fetch).toHaveBeenCalledWith(
        'http://gateway:8080/api/identity/me',
        expect.objectContaining({ headers: { Authorization: 'Bearer token-abc' } })
      );
    });

    it('一般ユーザーが他人のIDの編集画面へ来たら / へ戻す', async () => {
      mockGetToken.mockResolvedValue({ role: 'user', accessToken: 'token-abc' } as never);
      global.fetch = jest.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ id: 42 }),
      } as Response);

      const res = await proxy(request('/users/99/edit'));

      expect(res.headers.get('location')).toBe('https://localhost/');
    });

    it('自ユーザーID取得に失敗したら / へ戻す(フェイルクローズ)', async () => {
      mockGetToken.mockResolvedValue({ role: 'user', accessToken: 'token-abc' } as never);
      global.fetch = jest.fn().mockResolvedValue({ ok: false } as Response);

      const res = await proxy(request('/users/42/edit'));

      expect(res.headers.get('location')).toBe('https://localhost/');
    });

    it('自ユーザーID取得が例外を投げても / へ戻す(フェイルクローズ)', async () => {
      mockGetToken.mockResolvedValue({ role: 'user', accessToken: 'token-abc' } as never);
      global.fetch = jest.fn().mockRejectedValue(new Error('network error'));

      const res = await proxy(request('/users/42/edit'));

      expect(res.headers.get('location')).toBe('https://localhost/');
    });

    it('accessTokenを持たないトークンでは自己アクセス判定を行わず / へ戻す', async () => {
      mockGetToken.mockResolvedValue({ role: 'user' } as never);

      const res = await proxy(request('/users/42/edit'));

      expect(res.headers.get('location')).toBe('https://localhost/');
      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('/users(一覧)自体は自己アクセス例外の対象にならず、一般ユーザーは / へ戻す', async () => {
      mockGetToken.mockResolvedValue({ role: 'user', accessToken: 'token-abc' } as never);

      const res = await proxy(request('/users'));

      expect(res.headers.get('location')).toBe('https://localhost/');
      // /users/{id}/edit専用の判定なので、一覧ページでは自己ID確認のfetchを呼ばない
      expect(global.fetch).not.toHaveBeenCalled();
    });
  });
});
