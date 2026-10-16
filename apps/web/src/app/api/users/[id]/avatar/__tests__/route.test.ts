/**
 * @jest-environment node
 *
 * Route Handlerはjsdomではなくnode環境(fetch API相当のRequest/Response/NextResponseが
 * 揃う)で検証する。jsdomはこれらのグローバルを提供しない。
 */
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth は差し替えない。差し替えるのは Next.js の実行基盤
// (next-auth・next/headers・next/server の after)と、外部との通信(fetch)だけ(#1832)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

import {
  getServerSessionMock as mockGetSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
} from '@/lib/__tests__/testUtils/serverRuntime';

import { GET } from "../route";

/**
 * issue #1241: ブラウザの<img>にAuthorizationヘッダーを付けられないため、このRoute Handlerが
 * セッションCookie由来のアクセストークンでidentity-serviceのGET /api/users/{id}/avatarを
 * 中継する(apiClient.getAvatarBytes経由)。
 */
describe("GET /api/users/[id]/avatar", () => {
  let fetchMock: jest.Mock;
  const avatar = (handler: () => Response | Promise<Response>) =>
    (fetchMock = installFetch({ 'GET /api/users/1/avatar': handler }));

  beforeEach(() => {
    resetServerRuntime();
    avatar(() => textResponse('unused', 500));
  });

  function context(id: string) {
    return { params: Promise.resolve({ id }) };
  }

  it("不正なユーザーID(数値でない)は400を返す", async () => {
    const response = await GET(new Request("http://localhost/api/users/abc/avatar"), context("abc"));

    expect(response.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("取得に成功したら画像バイト列をContent-Type付きでそのまま返す", async () => {
    const bytes = new TextEncoder().encode("fake-jpeg-bytes").buffer;
    avatar(() => ({
      ...jsonResponse({}, 200, { "content-type": "image/jpeg" }),
      arrayBuffer: async () => bytes,
    } as unknown as Response));

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(fetchMock).toHaveBeenCalledWith("https://localhost/api/users/1/avatar", expect.anything());
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/jpeg");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    const responseBytes = new Uint8Array(await response.arrayBuffer());
    expect(Buffer.from(responseBytes).toString()).toBe("fake-jpeg-bytes");
  });

  it("上流が Content-Type を返さなければ image/jpeg として返す", async () => {
    avatar(() => ({
      ...jsonResponse({}),
      headers: { get: () => null },
      arrayBuffer: async () => new ArrayBuffer(2),
    } as unknown as Response));

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/jpeg");
  });

  it("apiClient側がAPIエラー(404)を投げたらそのステータスで返す", async () => {
    avatar(() => textResponse("id 1 のアバターは未設定です", 404));

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(response.status).toBe(404);
    const body = await response.json();
    expect(body.error).toContain("未設定");
  });

  it("Error以外の例外(ステータスを含まない)は500を返す", async () => {
    avatar(() => {
      throw "network down";
    });

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(response.status).toBe(500);
  });
});
