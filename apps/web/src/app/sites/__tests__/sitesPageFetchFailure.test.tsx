/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・SiteListTable・SiteCreationPanel・next/link は
// 差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
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
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import SitesPage from "../page";

const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** gateway の向こう側(各サービス)の代役。拒否は 401(セッション切れ)か 503 の応答になる。 */
const api = { listSites: jest.fn(), listProjects: jest.fn(), listUsers: jest.fn(), listSshKeyPairs: jest.fn(), getMyProfile: jest.fn(), getSiteAdminPath: jest.fn() };
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

const SITE = {
  id: 1,
  name: "サイトA",
  siteKey: "a",
  cmsType: "WORDPRESS",
  baseUrl: "https://a.example.com",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  connectionCheckStatus: null,
  managedWordpress: false,
  sshConfigured: true,
};
const TABLE = "登録済みサイトはありません";
const CREATION = 'id="site-creation"';

describe("サイト一覧ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
    resetServerRuntime({ user: { role: "admin" } });
    installFetch({
      "GET /api/sites": respond(api.listSites),
      "GET /api/projects": respond(api.listProjects),
      "GET /api/users": respond(api.listUsers),
      "GET /api/ssh-key-pairs": respond(api.listSshKeyPairs),
      "GET /api/identity/me": respond(api.getMyProfile),
      "GET /api/system-settings/site-admin-path": respond(api.getSiteAdminPath),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
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
    expect(html).not.toContain(TABLE);
    expect(errorSpy).toHaveBeenCalled();
  });

  it("補助的な一覧(SSH鍵)の失敗でも通知し、サイト表は出す", async () => {
    api.listSshKeyPairs.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    expect(html).toContain(TABLE);
    expect(html).not.toContain(CREATION);
  });

  it("ユーザー一覧の取得失敗では、選択肢が空の登録フォームを描画しない", async () => {
    api.listUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain("ユーザー一覧を取得できませんでした");
    expect(html).not.toContain(CREATION);
  });

  it("サイト一覧の取得失敗では、既存サイトと照合できない登録フォームも描画しない", async () => {
    api.listSites.mockRejectedValue(DOWN);
    expect(renderToStaticMarkup(await SitesPage())).not.toContain(CREATION);
  });

  it("成功して0件のときは通知なしで表を描画する", async () => {
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain(TABLE);
    expect(html).toContain(CREATION);
  });

  it("システム設定の管理画面パスを表に渡す(issue #1529)", async () => {
    api.listSites.mockResolvedValue([SITE]);
    api.getSiteAdminPath.mockResolvedValue({ path: "secret-admin" });
    expect(renderToStaticMarkup(await SitesPage())).toContain('href="https://a.example.com/secret-admin"');
  });

  it("管理画面パスの取得に失敗しても wp-admin にフォールバックして表を描画する(issue #1529)", async () => {
    api.listSites.mockResolvedValue([SITE]);
    api.getSiteAdminPath.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await SitesPage());
    expect(html).toContain('href="https://a.example.com/wp-admin"');
    expect(html).not.toContain('role="alert"');
  });

  it("セッション切れは /login へ", async () => {
    api.listUsers.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(SitesPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
