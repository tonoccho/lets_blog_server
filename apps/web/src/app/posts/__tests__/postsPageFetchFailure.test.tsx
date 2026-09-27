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

const api = { listPosts: jest.fn(), getMyProfile: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listPosts: (...a: unknown[]) => api.listPosts(...a),
  getMyProfile: (...a: unknown[]) => api.getMyProfile(...a),
}));
jest.mock("../PostsTable", () => ({ PostsTable: () => null }));
import PostsPage from "../page";

describe("投稿履歴ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "user" } });
    api.getMyProfile.mockResolvedValue(null);
  });
  afterEach(() => errorSpy.mockRestore());

  it("失敗したとき、通知を出し「投稿履歴はまだありません」「全0件」を出さない", async () => {
    api.listPosts.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await PostsPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("投稿履歴を取得できませんでした");
    expect(html).not.toContain("投稿履歴はまだありません");
    expect(html).not.toContain("全0件");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通りの空表示で通知なし", async () => {
    api.listPosts.mockResolvedValue([]);
    const html = renderToStaticMarkup(await PostsPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("投稿履歴はまだありません");
    expect(html).toContain("全0件");
  });

  it("セッション切れは /login へ", async () => {
    api.listPosts.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(PostsPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
