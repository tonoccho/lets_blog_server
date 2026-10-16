/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・ImageGalleryGrid・next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import ImageGalleryPage from "../page";

const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** gateway の向こう側(各サービス)の代役。拒否は 401(セッション切れ)・503 の応答になる。 */
const api = { listGeneratedImages: jest.fn(), listGeneratedImageFolders: jest.fn(), getMyProfile: jest.fn() };
function respond(backend: jest.Mock) {
  return async () => {
    try {
      return jsonResponse(await backend());
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      return message === SESSION_EXPIRED ? textResponse("Unauthorized", 401) : textResponse("Service Unavailable", 503);
    }
  };
}

/** 画像 1 枚ぶんの一覧の要素。実物のグリッドが描く。 */
const IMAGE = { id: 1, projectId: 1, prompt: "a cute cat", checkpoint: "m", createdAt: "2026-08-01T00:00:00Z", tags: [], provider: "COMFYUI", folderId: null };
// 実物のグリッドの目印: フォルダツリー(0件でも描画される。issue #1787)。
const GRID = "フォルダで絞り込み";

describe("画像ギャラリーの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
    resetServerRuntime({ user: { role: "user" } });
    installFetch({
      "GET /api/generated-images?limit=24&offset=0": respond(api.listGeneratedImages),
      "GET /api/generated-images/folders": respond(api.listGeneratedImageFolders),
      "GET /api/identity/me": respond(api.getMyProfile),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.getMyProfile.mockResolvedValue(null);
    api.listGeneratedImageFolders.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("失敗したとき、通知を出し「生成画像がありません」の操作案内を出さない", async () => {
    api.listGeneratedImages.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("生成画像を取得できませんでした");
    expect(html).not.toContain("生成画像がありません");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは案内を出しつつ、フォルダのグリッドも描画する(issue #1787)", async () => {
    api.listGeneratedImages.mockResolvedValue([]);
    api.listGeneratedImageFolders.mockResolvedValue([{ id: 7, name: "風景", parentId: null }]);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("生成画像がありません");
    expect(html).toContain(GRID);
    expect(html).toContain("風景");
  });

  it("初回は1ページぶん(limit=24, offset=0)だけを取得する(issue #1472)", async () => {
    api.listGeneratedImages.mockResolvedValue([IMAGE]);
    await ImageGalleryPage();
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining("/api/generated-images?limit=24&offset=0"), expect.anything());
  });

  it("成功して画像があるときはグリッドを描画する", async () => {
    api.listGeneratedImages.mockResolvedValue([IMAGE]);
    expect(renderToStaticMarkup(await ImageGalleryPage())).toContain(IMAGE.prompt);
  });

  it("セッション切れは /login へ", async () => {
    api.listGeneratedImages.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ImageGalleryPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
  it("フォルダ一覧も取得してグリッドへ渡す(issue #1493)", async () => {
    api.listGeneratedImages.mockResolvedValue([IMAGE]);
    api.listGeneratedImageFolders.mockResolvedValue([{ id: 7, name: "風景", parentId: null }]);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).toContain("風景");
  });

  it("フォルダ一覧の取得に失敗しても画像は表示し、フォルダの取得失敗を通知する(issue #1493)", async () => {
    api.listGeneratedImages.mockResolvedValue([IMAGE]);
    api.listGeneratedImageFolders.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).toContain(IMAGE.prompt);
    expect(html).toContain("フォルダを取得できませんでした");
    expect(html).not.toContain("生成画像を取得できませんでした");
  });

  it("画像の取得に失敗したときは、0件でもグリッドを描画しない(issue #1787)", async () => {
    api.listGeneratedImages.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).not.toContain(GRID);
  });
});
