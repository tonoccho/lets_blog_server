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

const api = { listSites: jest.fn(), listProjects: jest.fn(), listUsers: jest.fn(), listSshKeyPairs: jest.fn(), getMyProfile: jest.fn(), getSiteAdminPath: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listSites: (...a: unknown[]) => api.listSites(...a),
  listProjects: (...a: unknown[]) => api.listProjects(...a),
  listUsers: (...a: unknown[]) => api.listUsers(...a),
  listSshKeyPairs: (...a: unknown[]) => api.listSshKeyPairs(...a),
  getMyProfile: (...a: unknown[]) => api.getMyProfile(...a),
  getSiteAdminPath: (...a: unknown[]) => api.getSiteAdminPath(...a),
}));
jest.mock("../SiteListTable", () => ({ SiteListTable: ({ adminPath }: { adminPath: string }) => `SITE_TABLE[${adminPath}]` }));
jest.mock("../SiteCreationPanel", () => ({ SiteCreationPanel: () => "SITE_CREATION" }));
import SitesPage from "../page";

describe("サイト一覧ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.listSites.mockResolvedValue([]);
    api.listProjects.mockResolvedValue([]);
    api.listUsers.mockResolvedValue([]);
    api.listSshKeyPairs.mockResolvedValue([]);
    api.getMyProfile.mockResolvedValue(null);
    api.getSiteAdminPath.mockResolvedValue({ path: "wp-admin" });
  });
  afterEach(() => errorSpy.mockRestore());

  it("サイト一覧の取得に失敗したとき、通知を出し「登録済みサイトはありません」を出す表を描画しない", async () => {
    api.listSites.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("サイト一覧を取得できませんでした");
    expect(html).not.toContain("SITE_TABLE");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("補助的な一覧(SSH鍵)の失敗でも通知し、サイト表は出す", async () => {
    api.listSshKeyPairs.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    expect(html).toContain("SITE_TABLE");
    expect(html).not.toContain("SITE_CREATION");
  });

  it("ユーザー一覧の取得失敗では、選択肢が空の登録フォームを描画しない", async () => {
    api.listUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain("ユーザー一覧を取得できませんでした");
    expect(html).not.toContain("SITE_CREATION");
  });

  it("サイト一覧の取得失敗では、既存サイトと照合できない登録フォームも描画しない", async () => {
    api.listSites.mockRejectedValue(DOWN);
    expect(renderToStaticMarkup(await SitesPage())).not.toContain("SITE_CREATION");
  });

  it("成功して0件のときは通知なしで表を描画する", async () => {
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("SITE_TABLE");
    expect(html).toContain("SITE_CREATION");
  });

  it("システム設定の管理画面パスを表に渡す(issue #1529)", async () => {
    api.getSiteAdminPath.mockResolvedValue({ path: "secret-admin" });
    expect(renderToStaticMarkup(await SitesPage())).toContain("SITE_TABLE[secret-admin]");
  });

  it("管理画面パスの取得に失敗しても wp-admin にフォールバックして表を描画する(issue #1529)", async () => {
    api.getSiteAdminPath.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain("SITE_TABLE[wp-admin]");
    expect(html).not.toContain('role="alert"');
  });

  it("セッション切れは /login へ", async () => {
    api.listUsers.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(SitesPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
