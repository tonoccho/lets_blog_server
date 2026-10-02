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

const api = { getSiteDetail: jest.fn(), listSshKeyPairs: jest.fn(), listStaticContent: jest.fn(), getSiteAdminPath: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  getSiteDetail: (...a: unknown[]) => api.getSiteDetail(...a),
  listSshKeyPairs: (...a: unknown[]) => api.listSshKeyPairs(...a),
  listStaticContent: (...a: unknown[]) => api.listStaticContent(...a),
  getSiteAdminPath: (...a: unknown[]) => api.getSiteAdminPath(...a),
}));
jest.mock("../SiteEditForm", () => ({
  SiteEditForm: ({ defaultAdminPath }: { defaultAdminPath: string | null }) => `FORM[${defaultAdminPath}]`,
}));
jest.mock("../StaticContentPanel", () => ({ StaticContentPanel: () => "STATIC" }));
import Page from "../page";

const render = async () => renderToStaticMarkup(await Page({ params: Promise.resolve({ id: "5" }) }));

describe("サイト編集ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.getSiteDetail.mockResolvedValue({ id: 5, name: "S" });
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
    expect(notFound).not.toHaveBeenCalled();
    expect(html).toContain('role="alert"');
    expect(html).toContain("サイト情報を取得できませんでした");
    expect(html).not.toContain("FORM");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("静的コンテンツの取得失敗は通知し、空に見えるパネルは描画しない", async () => {
    api.listStaticContent.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("静的コンテンツ一覧を取得できませんでした");
    expect(html).not.toContain("STATIC");
    expect(html).toContain("FORM");
  });

  it("SSH鍵一覧の取得失敗は通知する", async () => {
    api.listSshKeyPairs.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    // 選択肢が空のフォームは、そのまま保存すると鍵の紐付けを外しかねないため描画しない。
    expect(html).not.toContain("FORM");
  });

  it("グローバル既定の管理画面パスをフォームへ渡す", async () => {
    api.getSiteAdminPath.mockResolvedValue({ path: "secret-admin" });
    expect(await render()).toContain("FORM[secret-admin]");
  });

  it("グローバル既定の取得失敗は通知するが、フォームは描画する(既定値なし)", async () => {
    api.getSiteAdminPath.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("管理画面パスの既定値を取得できませんでした");
    expect(html).toContain("FORM[null]");
  });

  it("すべて成功したときは通知なし", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("FORM");
    expect(html).toContain("STATIC");
  });

  it("セッション切れは /login へ", async () => {
    api.listSshKeyPairs.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("非 admin は / へ戻され、サイト情報を取得しない(issue #1532 AC5)", async () => {
    getServerSession.mockResolvedValue({ user: { role: "user" } });
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(api.getSiteDetail).not.toHaveBeenCalled();
  });
});
