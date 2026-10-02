/**
 * @jest-environment node
 */

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
import { renderToStaticMarkup } from "react-dom/server";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue(null),
}));
const getProject = jest.fn();
const listPosts = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  getProject: (...a: unknown[]) => getProject(...a),
  listPosts: (...a: unknown[]) => listPosts(...a),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => "BREADCRUMB" }));
jest.mock("../../ProjectSectionNav", () => ({ ProjectSectionNav: () => "NAV" }));
jest.mock("../../../../posts/PostsTable", () => ({
  PostsTable: ({ posts }: { posts: unknown[] }) => `POSTS_TABLE(${posts.length})`,
}));
import ProjectPostsPage from "../page";

const params = { params: Promise.resolve({ id: "7" }) };

describe("プロジェクト投稿履歴ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getProject.mockResolvedValue({
      id: 7,
      name: "案件",
      localSite: { id: 1 },
      testSite: null,
      productionSite: null,
    });
    listPosts.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("投稿一覧の取得に失敗したとき、通知とログを出し「0件」と空の表を出さない", async () => {
    listPosts.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).toContain('role="alert"');
    expect(html).toContain("投稿一覧を取得できませんでした");
    expect(html).not.toContain("全0件");
    expect(html).not.toContain("POSTS_TABLE");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしで0件の表を出す", async () => {
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("全0件を表示");
    expect(html).toContain("POSTS_TABLE(0)");
  });

  it("このプロジェクトのサイトの投稿だけを表に渡す", async () => {
    listPosts.mockResolvedValue([{ siteId: 1 }, { siteId: 2 }]);
    expect(renderToStaticMarkup(await ProjectPostsPage(params))).toContain("POSTS_TABLE(1)");
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    getProject.mockRejectedValue(DOWN);
    await expect(ProjectPostsPage(params)).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("セッション切れは /login へ", async () => {
    listPosts.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ProjectPostsPage(params)).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
