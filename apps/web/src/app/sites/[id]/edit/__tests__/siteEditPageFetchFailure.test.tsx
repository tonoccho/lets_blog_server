/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・SiteEditForm・StaticContentPanel・next/link は
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
import Page from "../page";

const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** gateway の向こう側(各サービス)の代役。拒否は 404・401(セッション切れ)・503 の応答になる。 */
const api = { getSiteDetail: jest.fn(), listSshKeyPairs: jest.fn(), listStaticContent: jest.fn(), getSiteAdminPath: jest.fn() };
function respond(backend: jest.Mock) {
  return async () => {
    try {
      return jsonResponse(await backend());
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
      if (message.includes("404")) return textResponse("Not Found", 404);
      return textResponse("Service Unavailable", 503);
    }
  };
}

const SITE = {
  id: 5,
  name: "S",
  siteKey: "s-key",
  cmsType: "WORDPRESS",
  baseUrl: "https://s.example.com",
  credentials: {},
  configuredSecretFields: [],
  adminPath: null,
};
// 実物の部品の目印: 編集フォームは「サイトキー」欄(name="adminPath" の入力)、静的コンテンツは説明文。
const FORM = 'name="adminPath"';
const STATIC = "生成は処理キューで進みます";

const render = async () => renderToStaticMarkup(await Page({ params: Promise.resolve({ id: "5" }) }));

describe("サイト編集ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
    resetServerRuntime({ user: { role: "admin" } });
    installFetch({
      "GET /api/sites/:id": respond(api.getSiteDetail),
      "GET /api/ssh-key-pairs": respond(api.listSshKeyPairs),
      "GET /api/sites/:id/static-content": respond(api.listStaticContent),
      "GET /api/system-settings/site-admin-path": respond(api.getSiteAdminPath),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.getSiteDetail.mockResolvedValue(SITE);
    api.listSshKeyPairs.mockResolvedValue([]);
    api.listStaticContent.mockResolvedValue([]);
    api.getSiteAdminPath.mockResolvedValue({ path: "wp-admin" });
  });
  afterEach(() => errorSpy.mockRestore());

  it("サイト取得が404のときは notFound()", async () => {
    api.getSiteDetail.mockRejectedValue(new Error("APIエラー (404): Not Found"));
    await expect(render()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("サイト取得が404以外で失敗したときは notFound() ではなく通知を出しログに残す", async () => {
    api.getSiteDetail.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain('role="alert"');
    expect(html).toContain("サイト情報を取得できませんでした");
    expect(html).not.toContain(FORM);
    expect(errorSpy).toHaveBeenCalled();
  });

  it("静的コンテンツの取得失敗は通知し、空に見えるパネルは描画しない", async () => {
    api.listStaticContent.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("静的コンテンツ一覧を取得できませんでした");
    expect(html).not.toContain(STATIC);
    expect(html).toContain(FORM);
  });

  it("SSH鍵一覧の取得失敗は通知する", async () => {
    api.listSshKeyPairs.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    // 選択肢が空のフォームは、そのまま保存すると鍵の紐付けを外しかねないため描画しない。
    expect(html).not.toContain(FORM);
  });

  it("グローバル既定の管理画面パスをフォームへ渡す", async () => {
    api.getSiteAdminPath.mockResolvedValue({ path: "secret-admin" });
    expect(await render()).toContain('placeholder="既定: secret-admin"');
  });

  it("グローバル既定の取得失敗は通知するが、フォームは描画する(既定値なし)", async () => {
    api.getSiteAdminPath.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("管理画面パスの既定値を取得できませんでした");
    expect(html).toContain('placeholder="空欄ならシステム設定の既定値"');
  });

  it("すべて成功したときは通知なし", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain(FORM);
    expect(html).toContain(STATIC);
  });

  it("セッション切れは /login へ", async () => {
    api.listSshKeyPairs.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("非 admin は / へ戻され、サイト情報を取得しない(issue #1532 AC5)", async () => {
    resetServerRuntime({ user: { role: "user" } });
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(api.getSiteDetail).not.toHaveBeenCalled();
  });
});
