/**
 * @jest-environment node
 */

/**
 * issue #1052: JavaScript無効時に/loginのフォームから呼ばれるServer Actionの検証。
 *
 * `page.tsx`はNextAuthの実POSTハンドラ(apps/web/src/app/api/auth/[...nextauth]/route.ts)を
 * ブラウザに直接叩かせる代わりに、このServer Actionが自分自身のNext.jsサーバーへ
 *   1. GET /api/auth/csrf でCSRF Cookie+トークンを取得
 *   2. そのCookieを添えて POST /api/auth/signin/keycloak を実ハンドラへ直接送信
 * の順で内部fetchを行い、(2)の応答が持つPKCE用Cookie(pkce.code_verifier / state)を
 * ブラウザ向けの実際のレスポンスへ転記してから、Keycloakへ`redirect()`する
 * (CSRF Cookie自体はサーバー内往復にしか使わないため転記しない)。
 *
 * `next/navigation`のredirectはNEXT_REDIRECTを投げて制御を打ち切る仕組みのため、
 * `apps/web/src/app/sites/__tests__/actionsAuthorization.test.ts`と同じ方針で
 * 例外を投げるモックにする。
 */
jest.mock('server-only', () => ({}));

const mockRedirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => mockRedirect(p) }));

const mockCookieSet = jest.fn();
jest.mock('next/headers', () => ({
  cookies: async () => ({ set: mockCookieSet }),
}));

import { startNoJsLoginAction } from '../actions';

function csrfResponse(csrfToken: string, cookie: string): Response {
  return new Response(JSON.stringify({ csrfToken }), {
    headers: [
      ['content-type', 'application/json'],
      ['set-cookie', cookie],
    ],
  });
}

describe('JS無効時のログイン開始Server Action(issue #1052)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    jest.clearAllMocks();
  });

  it('CSRFトークンを取得し、そのCookieを添えてsignin POSTを行い、Keycloakへredirectする', async () => {
    const keycloakUrl = 'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth?client_id=letsblog-web';
    const signin = new Response(null, {
      status: 302,
      headers: [
        ['location', keycloakUrl],
        ['set-cookie', 'next-auth.pkce.code_verifier=abc; Path=/; HttpOnly; SameSite=Lax'],
        ['set-cookie', 'next-auth.state=xyz; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=900'],
        // signin応答にもcsrf-token Cookieが含まれうることを想定し、それが転記されないこと
        // (continueで読み飛ばされること)を検証する。
        ['set-cookie', 'next-auth.csrf-token=tok-456|hash2; Path=/; HttpOnly'],
      ],
    });
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce(csrfResponse('tok-123', 'next-auth.csrf-token=tok-123|hash; Path=/; HttpOnly'))
      .mockResolvedValueOnce(signin);
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(startNoJsLoginAction()).rejects.toThrow(`NEXT_REDIRECT:${keycloakUrl}`);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [csrfUrl] = fetchMock.mock.calls[0];
    expect(String(csrfUrl)).toContain('/api/auth/csrf');

    const [signinUrl, signinInit] = fetchMock.mock.calls[1] as [string, RequestInit & { headers: Record<string, string> }];
    expect(signinUrl).toContain('/api/auth/signin/keycloak');
    expect(signinInit.method).toBe('POST');
    expect(signinInit.headers.Cookie).toContain('next-auth.csrf-token=tok-123|hash');
    expect(new URLSearchParams(String(signinInit.body)).get('csrfToken')).toBe('tok-123');

    expect(mockCookieSet).toHaveBeenCalledWith(
      'next-auth.pkce.code_verifier',
      'abc',
      expect.objectContaining({ path: '/', httpOnly: true, sameSite: 'lax' })
    );
    expect(mockCookieSet).toHaveBeenCalledWith(
      'next-auth.state',
      'xyz',
      expect.objectContaining({ path: '/', httpOnly: true, secure: true, sameSite: 'lax', maxAge: 900 })
    );
    // CSRF Cookie自体はサーバー内往復にしか使わないため、ブラウザ向けには転記しない。
    expect(mockCookieSet).not.toHaveBeenCalledWith(
      'next-auth.csrf-token',
      expect.anything(),
      expect.anything()
    );
  });

  it('signin応答にLocationが無い場合は/loginへエラー付きでredirectする', async () => {
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce(csrfResponse('tok-123', 'next-auth.csrf-token=tok-123|hash; Path=/'))
      .mockResolvedValueOnce(new Response(null, { status: 400 }));
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(startNoJsLoginAction()).rejects.toThrow('NEXT_REDIRECT:/login?error=nojs');
    expect(mockCookieSet).not.toHaveBeenCalled();
  });

  it('CSRF応答にCookieが無い場合はCookie無しでsigninを送り、getSetCookie非対応の応答では何も転記しない', async () => {
    const keycloakUrl = 'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth?client_id=letsblog-web';
    // undici以外のfetch実装ではHeaders#getSetCookie()が無い場合を想定(getSetCookies()の
    // `?? []`フォールバック)。
    const signinWithoutGetSetCookie = {
      headers: { get: (name: string) => (name === 'location' ? keycloakUrl : null) },
    } as unknown as Response;
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce({ json: async () => ({ csrfToken: 'tok-789' }), headers: { getSetCookie: () => [] } })
      .mockResolvedValueOnce(signinWithoutGetSetCookie);
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(startNoJsLoginAction()).rejects.toThrow(`NEXT_REDIRECT:${keycloakUrl}`);

    const [, signinInit] = fetchMock.mock.calls[1] as [string, RequestInit & { headers: Record<string, string> }];
    expect(signinInit.headers.Cookie).toBeUndefined();
    expect(mockCookieSet).not.toHaveBeenCalled();
  });

  it('signin応答のCookie値が既にpercent-encode済みでも二重encodeせずに転記する(QA #1052で発見、callback-urlクッキー破損の回帰)', async () => {
    const keycloakUrl = 'https://localhost/auth/realms/letsblog/protocol/openid-connect/auth?client_id=letsblog-web';
    // NextAuthは__Secure-next-auth.callback-urlのようなCookieを、Set-Cookie上で
    // 既にpercent-encodeした値として送ってくる。cookieStore.set()自身がencodeURIComponentで
    // もう一段encodeするため、ここでdecodeしておかないと最終的に二重encodeされる。
    const signin = new Response(null, {
      status: 302,
      headers: [
        ['location', keycloakUrl],
        [
          'set-cookie',
          '__Secure-next-auth.callback-url=https%3A%2F%2Flocalhost; Path=/; HttpOnly; Secure; SameSite=Lax',
        ],
      ],
    });
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce(csrfResponse('tok-123', 'next-auth.csrf-token=tok-123|hash; Path=/'))
      .mockResolvedValueOnce(signin);
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(startNoJsLoginAction()).rejects.toThrow(`NEXT_REDIRECT:${keycloakUrl}`);

    // 転記後、cookies().set()が受け取る値は「decode済みの生の値」でなければならない。
    // cookies().set()自身がこの値をpercent-encodeしてSet-Cookieへ書き出すため、
    // ここでpercent-encode済みの文字列を渡すと二重encodeになる。
    expect(mockCookieSet).toHaveBeenCalledWith(
      '__Secure-next-auth.callback-url',
      'https://localhost',
      expect.objectContaining({ path: '/', httpOnly: true, secure: true, sameSite: 'lax' })
    );
  });
});
