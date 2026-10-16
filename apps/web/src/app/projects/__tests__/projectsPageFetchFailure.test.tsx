/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・ProjectForm・ViewerDateTime・next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
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
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ProjectsPage from "../page";

/** project-service(プロジェクト一覧)と identity-service の代役(gateway の向こう側)。 */
let listProjects: () => Response;

describe("プロジェクト一覧ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    resetServerRuntime(ADMIN_SESSION);
    listProjects = () => jsonResponse([]);
    installFetch({
      "GET /api/projects": () => listProjects(),
      "GET /api/identity/me": () => jsonResponse({ id: 1, timezone: null }),
    });
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、通知とログを出し「0件」「登録済みプロジェクトはありません」を出さない", async () => {
    listProjects = () => textResponse("Service Unavailable", 503);
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("プロジェクト一覧を取得できませんでした");
    expect(html).not.toContain("全0件");
    expect(html).not.toContain("登録済みプロジェクトはありません");
    expect(html).toContain("プロジェクトを作成");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしで「登録済みプロジェクトはありません」を出す", async () => {
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("全0件を表示");
    expect(html).toContain("登録済みプロジェクトはありません");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("成功して1件以上のときは行を表示する", async () => {
    listProjects = () => jsonResponse([{ id: 1, name: "案件A", slug: "a", createdAt: "2026-01-01T00:00:00Z" }]);
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain("全1件を表示");
    expect(html).toContain("案件A");
  });

  it("セッション切れは /login へ", async () => {
    listProjects = () => textResponse("Unauthorized", 401);
    await expect(ProjectsPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でないユーザーは / へ戻される", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(ProjectsPage()).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
