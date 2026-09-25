/**
 * @jest-environment node
 *
 * Route Handlerはjsdomではなくnode環境(fetch API相当のRequest/Response/NextResponseが
 * 揃う)で検証する。jsdomはこれらのグローバルを提供しない。
 */
import { GET } from "../route";
import { getAvatarBytes } from "@/lib/apiClient";

jest.mock("@/lib/apiClient", () => ({
  getAvatarBytes: jest.fn(),
}));

/**
 * issue #1241: ブラウザの<img>にAuthorizationヘッダーを付けられないため、このRoute Handlerが
 * セッションCookie由来のアクセストークンでidentity-serviceのGET /api/users/{id}/avatarを
 * 中継する(apiClient.getAvatarBytes経由)。
 */
describe("GET /api/users/[id]/avatar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  function context(id: string) {
    return { params: Promise.resolve({ id }) };
  }

  it("不正なユーザーID(数値でない)は400を返す", async () => {
    const response = await GET(new Request("http://localhost/api/users/abc/avatar"), context("abc"));

    expect(response.status).toBe(400);
    expect(getAvatarBytes).not.toHaveBeenCalled();
  });

  it("取得に成功したら画像バイト列をContent-Type付きでそのまま返す", async () => {
    const bytes = new TextEncoder().encode("fake-jpeg-bytes").buffer;
    (getAvatarBytes as jest.Mock).mockResolvedValue({ body: bytes, contentType: "image/jpeg" });

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(getAvatarBytes).toHaveBeenCalledWith(1);
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/jpeg");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    const responseBytes = new Uint8Array(await response.arrayBuffer());
    expect(Buffer.from(responseBytes).toString()).toBe("fake-jpeg-bytes");
  });

  it("apiClient側がAPIエラー(404)を投げたらそのステータスで返す", async () => {
    (getAvatarBytes as jest.Mock).mockRejectedValue(new Error("APIエラー (404): id 1 のアバターは未設定です"));

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(response.status).toBe(404);
    const body = await response.json();
    expect(body.error).toContain("未設定");
  });

  it("Error以外の例外(ステータスを含まない)は500を返す", async () => {
    (getAvatarBytes as jest.Mock).mockRejectedValue("network down");

    const response = await GET(new Request("http://localhost/api/users/1/avatar"), context("1"));

    expect(response.status).toBe(500);
  });
});
