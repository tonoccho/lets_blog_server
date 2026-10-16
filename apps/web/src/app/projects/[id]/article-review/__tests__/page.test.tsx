/**
 * @jest-environment node
 */
// 記事レビュー画面 page.tsx。自前の @/lib/session・@/lib/apiClient・Breadcrumb・ProjectSectionNav・一覧は差し替えない。
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
  useRouter: () => ({ refresh: jest.fn() }),
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ArticleReviewPage from "../page";

const project = (githubRepository: string | null) => ({ id: 7, name: "サンプル案件", githubRepository });

let getProject: () => Response;
let listPullRequests: () => Response;
let pullRequestListCalls: number;

const render_ = async () => renderToStaticMarkup(await ArticleReviewPage({ params: Promise.resolve({ id: "7" }) }));

describe("記事レビュー画面 page.tsx(issue #1340)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => undefined);
    resetServerRuntime(ADMIN_SESSION);
    pullRequestListCalls = 0;
    getProject = () => jsonResponse(project("acme/blog"));
    listPullRequests = () =>
      jsonResponse([
        { number: 201, title: "記事サンプル", headBranch: "article/x", createdAt: "2026-09-30T03:00:00Z", url: "https://github.com/acme/blog/pull/201", state: null },
      ]);
    installFetch({
      "GET /api/projects/:id": () => getProject(),
      "GET /api/projects/:id/article-review/pull-requests": () => {
        pullRequestListCalls += 1;
        return listPullRequests();
      },
      "GET /api/identity/me": () => jsonResponse({ id: 1, timezone: "Asia/Tokyo" }),
    });
  });
  afterEach(() => errorSpy.mockRestore());

  it("リポジトリ設定済みならPR一覧を、閲覧者のタイムゾーンの日時つきで表示する", async () => {
    const html = await render_();

    expect(pullRequestListCalls).toBe(1);
    expect(html).toContain("記事サンプル");
    expect(html).toContain("2026/9/30 12:00:00");
    expect(html).toContain("サンプル案件 — 記事レビュー");
    expect(html).not.toContain('role="alert"');
  });

  it("リポジトリ未設定なら未設定の旨と設定先リンクを出し、APIは呼ばず一覧も出さない", async () => {
    getProject = () => jsonResponse(project(null));
    const html = await render_();

    expect(html).toContain("GitHub リポジトリが未設定");
    expect(html).toMatch(/href="\/projects\/7"[^>]*>プロジェクト設定</);
    expect(pullRequestListCalls).toBe(0);
    expect(html).not.toContain("<table");
  });

  it("API失敗時は空一覧ではなく取得失敗のアラートを出す", async () => {
    listPullRequests = () => textResponse("Bad Gateway", 502);
    const html = await render_();

    expect(html).toContain('role="alert"');
    expect(html).toContain("Pull Request の取得に失敗しました");
    expect(html).not.toContain("レビュー待ちの Pull Request はありません");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    getProject = () => textResponse("not found", 404);
    await expect(render_()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(render_()).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
