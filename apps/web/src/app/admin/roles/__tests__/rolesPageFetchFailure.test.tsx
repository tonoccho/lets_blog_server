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

jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn().mockResolvedValue(undefined) }));
const api = { listRoles: jest.fn(), listUsers: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listRoles: (...a: unknown[]) => api.listRoles(...a),
  listUsers: (...a: unknown[]) => api.listUsers(...a),
}));
jest.mock("../RoleAssignmentPanel", () => ({ RoleAssignmentPanel: () => "ASSIGNMENT_PANEL" }));
import AdminRolesPage from "../page";

const role = { roleName: "admin", displayName: "管理者", description: "d", permissions: ["p1"] };

describe("ロール管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.listRoles.mockResolvedValue([role]);
    api.listUsers.mockResolvedValue([{ id: 1, email: "a@example.com", roleNames: ["admin"] }]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("ロール一覧の取得失敗では、通知とログを出し、空の割り当てパネルを描画しない", async () => {
    api.listRoles.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("ロール一覧を取得できませんでした");
    expect(html).not.toContain("ASSIGNMENT_PANEL");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("ユーザー一覧の取得失敗では、通知し、ロールカードは出すが割り当てパネルは出さない", async () => {
    api.listUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).toContain("ユーザー一覧を取得できませんでした");
    expect(html).toContain("管理者");
    expect(html).not.toContain("ASSIGNMENT_PANEL");
  });

  it("成功したときは通知なしでロールと割り当てパネルを出す", async () => {
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("管理者");
    expect(html).toContain("ASSIGNMENT_PANEL");
  });

  it("成功して0件でも通知しない", async () => {
    api.listRoles.mockResolvedValue([]);
    api.listUsers.mockResolvedValue([]);
    const html = renderToStaticMarkup(await AdminRolesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("ASSIGNMENT_PANEL");
  });

  it("説明の無いロールでも描画できる", async () => {
    api.listRoles.mockResolvedValue([{ ...role, description: null }]);
    expect(renderToStaticMarkup(await AdminRolesPage())).toContain("管理者");
  });

  it("セッション切れは /login へ", async () => {
    api.listRoles.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(AdminRolesPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
