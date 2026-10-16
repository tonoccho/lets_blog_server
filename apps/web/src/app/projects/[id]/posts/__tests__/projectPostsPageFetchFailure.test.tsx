/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・Breadcrumb・ProjectSectionNav・PostsTable は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
import { jsonResponse, installFetch, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ProjectPostsPage from "../page";

const params = { params: Promise.resolve({ id: "7" }) };

const post = (id: number, siteId: number, siteName: string) => ({
  id,
  siteId,
  siteName,
  wpPostId: String(id),
  slug: `post-${id}`,
  status: "publish",
  lastPublishedAt: "2026-09-08T20:03:35Z",
  categories: [],
  publishScheduledAt: null,
});

let getProject: () => Response;
let listPosts: () => Response;
let profile: () => Response;

describe("プロジェクト投稿履歴ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    resetServerRuntime(ADMIN_SESSION);
    getProject = () => jsonResponse({ id: 7, name: "案件", localSite: { id: 1 }, testSite: null, productionSite: null });
    listPosts = () => jsonResponse([]);
    profile = () => jsonResponse({ id: 1, timezone: null });
    installFetch({
      "GET /api/projects/:id": () => getProject(),
      "GET /api/posts": () => listPosts(),
      "GET /api/identity/me": () => profile(),
    });
  });
  afterEach(() => errorSpy.mockRestore());

  it("投稿一覧の取得に失敗したとき、通知とログを出し「0件」と空の表を出さない", async () => {
    listPosts = () => textResponse("Service Unavailable", 503);
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).toContain('role="alert"');
    expect(html).toContain("投稿一覧を取得できませんでした");
    expect(html).not.toContain("全0件");
    expect(html).not.toContain("<table");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしで0件の表を出す", async () => {
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("全0件を表示");
    expect(html).toContain("<table");
    expect(html).toContain("案件 — 投稿履歴");
  });

  it("このプロジェクトのサイトの投稿だけを表に渡す(紐付く 3 環境のどれでも)", async () => {
    getProject = () =>
      jsonResponse({ id: 7, name: "案件", localSite: { id: 1 }, testSite: { id: 2 }, productionSite: { id: 3 } });
    listPosts = () => jsonResponse([post(1, 1, "ローカル"), post(2, 2, "テスト"), post(3, 3, "本番"), post(4, 9, "無関係")]);
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).toContain("全3件を表示");
    expect(html).toContain("ローカル");
    expect(html).toContain("テスト");
    expect(html).toContain("本番");
    expect(html).not.toContain("無関係");
  });

  it("サイトが 1 つも紐付かないプロジェクトでは、投稿は 1 件も出ない", async () => {
    getProject = () => jsonResponse({ id: 7, name: "案件", localSite: null, testSite: null, productionSite: null });
    listPosts = () => jsonResponse([post(1, 1, "どこかのサイト")]);
    const html = renderToStaticMarkup(await ProjectPostsPage(params));
    expect(html).toContain("全0件を表示");
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    getProject = () => textResponse("Service Unavailable", 503);
    await expect(ProjectPostsPage(params)).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("セッション切れは /login へ", async () => {
    listPosts = () => textResponse("Unauthorized", 401);
    await expect(ProjectPostsPage(params)).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(ProjectPostsPage(params)).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
