/**
 * @jest-environment node
 */

// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
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
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** identity-service の代役(gateway の向こう側)。拒否は 401(セッション切れ)か 503 の応答になる。 */
const api = { listRoles: jest.fn(), listUsers: jest.fn() };
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
import AdminRolesPage from "../page";

const role = { roleName: "admin", displayName: "管理者", description: "d", permissions: ["p1"] };

describe("ロール管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.listRoles.mockResolvedValue([role]);
    api.listUsers.mockResolvedValue([{ id: 1, email: "a@example.com", roleNames: ["admin"] }]);
    installFetch({ "GET /api/roles": respond(api.listRoles), "GET /api/users": respond(api.listUsers) });
  });
  afterEach(() => errorSpy.mockRestore());

  it("ロール一覧の取得失敗では、通知とログを出し、空の割り当てパネルを描画しない", async () => {
    api.listRoles.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("ロール一覧を取得できませんでした");
    expect(html).not.toContain("ロールを追加");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("ユーザー一覧の取得失敗では、通知し、ロールカードは出すが割り当てパネルは出さない", async () => {
    api.listUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).toContain("ユーザー一覧を取得できませんでした");
    expect(html).toContain("管理者");
    expect(html).not.toContain("ロールを追加");
  });

  it("成功したときは通知なしでロールと割り当てパネルを出す", async () => {
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("管理者");
    expect(html).toContain("a@example.com");
    expect(html).toContain("ロールを追加");
  });

  it("成功して0件でも通知しない", async () => {
    api.listRoles.mockResolvedValue([]);
    api.listUsers.mockResolvedValue([]);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("ユーザーがいません。");
  });

  it("説明の無いロールでも描画できる", async () => {
    api.listRoles.mockResolvedValue([{ ...role, description: null }]);
    expect(renderToStaticMarkup(await AdminRolesPage())).toContain("管理者");
  });

  it("セッション切れは /login へ", async () => {
    api.listRoles.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(AdminRolesPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でなければトップへ送り、一覧を取得しない", async () => {
    resetServerRuntime(USER_SESSION);
    await expect(AdminRolesPage()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(api.listRoles).not.toHaveBeenCalled();
  });
});
