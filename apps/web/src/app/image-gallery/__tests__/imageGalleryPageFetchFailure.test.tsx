/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
const getServerSession = jest.fn();
jest.mock("next-auth", () => ({ getServerSession: (...a: unknown[]) => getServerSession(...a) }));
jest.mock("@/lib/auth", () => ({ authOptions: {} }));
import { renderToStaticMarkup } from "react-dom/server";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

const api = { listGeneratedImages: jest.fn(), getMyProfile: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listGeneratedImages: (...a: unknown[]) => api.listGeneratedImages(...a),
  getMyProfile: (...a: unknown[]) => api.getMyProfile(...a),
}));
jest.mock("../ImageGalleryGrid", () => ({ ImageGalleryGrid: () => "GRID" }));
import ImageGalleryPage from "../page";

describe("画像ギャラリーの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "user" } });
    api.getMyProfile.mockResolvedValue(null);
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

  it("成功して0件のときは従来の空表示で通知なし", async () => {
    api.listGeneratedImages.mockResolvedValue([]);
    const html = renderToStaticMarkup(await ImageGalleryPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("生成画像がありません");
  });

  it("初回は1ページぶん(limit=24, offset=0)だけを取得する(issue #1472)", async () => {
    api.listGeneratedImages.mockResolvedValue([{ id: 1 }]);
    await ImageGalleryPage();
    expect(api.listGeneratedImages).toHaveBeenCalledWith(undefined, { limit: 24, offset: 0 });
  });

  it("成功して画像があるときはグリッドを描画する", async () => {
    api.listGeneratedImages.mockResolvedValue([{ id: 1 }]);
    expect(renderToStaticMarkup(await ImageGalleryPage())).toContain("GRID");
  });

  it("セッション切れは /login へ", async () => {
    api.listGeneratedImages.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ImageGalleryPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
