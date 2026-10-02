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
  getViewerProfile: jest.fn().mockResolvedValue({ id: 99 }),
}));
const api = { listUsers: jest.fn(), listProjects: jest.fn(), listAllProjectUsers: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listUsers: (...a: unknown[]) => api.listUsers(...a),
  listProjects: (...a: unknown[]) => api.listProjects(...a),
  listAllProjectUsers: (...a: unknown[]) => api.listAllProjectUsers(...a),
}));
jest.mock("../UserForm", () => ({ UserForm: () => "USER_FORM" }));
jest.mock("../DeleteUserButton", () => ({ DeleteUserButton: () => "DELETE" }));
jest.mock("@/components/ViewerDateTime", () => ({ ViewerDateTime: () => "DATETIME" }));
import UsersPage from "../page";

describe("ユーザー管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
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
    expect(html).toContain("USER_FORM");
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
    expect(html).toContain("DELETE");
  });

  it("セッション切れは /login へ", async () => {
    api.listUsers.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(UsersPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
