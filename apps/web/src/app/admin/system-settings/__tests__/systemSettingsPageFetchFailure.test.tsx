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

const api = { listAppSettings: jest.fn() };
jest.mock("@/lib/apiClient", () => ({ listAppSettings: (...a: unknown[]) => api.listAppSettings(...a) }));
jest.mock("../AppSettingsPanel", () => ({ AppSettingsPanel: () => "PANEL" }));
import Page from "../page";

describe("システム設定の取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
  });
  afterEach(() => errorSpy.mockRestore());

  it("失敗したとき通知を出し、空の設定を保存させかねない編集パネルを描画しない", async () => {
    api.listAppSettings.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await Page());
    expect(html).toContain('role="alert"');
    expect(html).toContain("システム設定を取得できませんでした");
    expect(html).not.toContain("PANEL");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功したときは通知なしでパネルを描画する", async () => {
    api.listAppSettings.mockResolvedValue([]);
    const html = renderToStaticMarkup(await Page());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("PANEL");
  });

  it("セッション切れは /login へ", async () => {
    api.listAppSettings.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(Page()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
