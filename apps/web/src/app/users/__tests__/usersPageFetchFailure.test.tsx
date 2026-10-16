/**
 * @jest-environment node
 */

// 自前の @/lib/session・@/lib/apiClient・UserForm・DeleteUserButton・ViewerDateTime と next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** identity-service と project-service の代役(gateway の向こう側)。拒否は 401(セッション切れ)か 503 の応答になる。 */
const api = { listUsers: jest.fn(), listProjects: jest.fn(), listAllProjectUsers: jest.fn() };
const getMyProfile = jest.fn();
function respond(backend: jest.Mock) {
  return async () => {
    try {
      return jsonResponse(await backend());
    } catch (err) {
      if (err instanceof Error && err.message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
      return textResponse("Service Unavailable", 503);
    }
  };
}
import UsersPage from "../page";

describe("ユーザー管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    getMyProfile.mockReset();
    getMyProfile.mockResolvedValue({ id: 99, timezone: null });
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "GET /api/users": respond(api.listUsers),
      "GET /api/projects": respond(api.listProjects),
      "GET /api/project-users": respond(api.listAllProjectUsers),
      "GET /api/identity/me": respond(getMyProfile),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.listUsers.mockResolvedValue([]);
    api.listProjects.mockResolvedValue([]);
    api.listAllProjectUsers.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("ユーザー一覧の取得失敗では、通知とログを出し「0件」「登録済みユーザーはありません」を出さない", async () => {
    api.listUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await UsersPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("ユーザー一覧を取得できませんでした");
    expect(html).not.toContain("全0件");
    expect(html).not.toContain("登録済みユーザーはありません");
    expect(html).toContain("ユーザーを追加");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("プロジェクト一覧の取得失敗でも通知する(参加プロジェクトの「-」を黙って出さない)", async () => {
    api.listProjects.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await UsersPage());
    expect(html).toContain("プロジェクト一覧を取得できませんでした");
  });

  it("プロジェクトメンバー一覧の取得失敗でも通知する", async () => {
    api.listAllProjectUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await UsersPage());
    expect(html).toContain("プロジェクトメンバー一覧を取得できませんでした");
  });

  it("成功して0件のときは従来通り通知なしで「登録済みユーザーはありません」を出す", async () => {
    const html = renderToStaticMarkup(await UsersPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("全0件を表示");
    expect(html).toContain("登録済みユーザーはありません");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("成功して1件以上のときは行と参加プロジェクトを表示する", async () => {
    api.listUsers.mockResolvedValue([{ id: 1, email: "a@example.com", role: "admin", createdAt: "x" }]);
    api.listProjects.mockResolvedValue([{ id: 5, name: "案件X" }]);
    api.listAllProjectUsers.mockResolvedValue([
      { projectId: 5, userId: 1 },
      { projectId: 404, userId: 1 },
    ]);
    const html = renderToStaticMarkup(await UsersPage());
    expect(html).toContain("a@example.com");
    expect(html).toContain("案件X");
    expect(html).toContain("削除");
    expect(html).toContain("/users/1/edit");
  });

  it("セッション切れは /login へ", async () => {
    api.listUsers.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(UsersPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
