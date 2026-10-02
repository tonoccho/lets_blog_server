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
}));
const listSshKeyPairs = jest.fn();
jest.mock("@/lib/apiClient", () => ({ listSshKeyPairs: (...a: unknown[]) => listSshKeyPairs(...a) }));
jest.mock("../SshKeyPairsPanel", () => ({
  SshKeyPairsPanel: ({ keyPairs }: { keyPairs: unknown[] }) => `SSH_PANEL(${keyPairs.length})`,
}));
import AdminSshKeysPage from "../page";

describe("SSH鍵管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    listSshKeyPairs.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、通知とログを出し、0件の鍵一覧を描画しない", async () => {
    listSshKeyPairs.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminSshKeysPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    expect(html).not.toContain("SSH_PANEL");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしでパネルを描画する", async () => {
    const html = renderToStaticMarkup(await AdminSshKeysPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("SSH_PANEL(0)");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("セッション切れは /login へ", async () => {
    listSshKeyPairs.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(AdminSshKeysPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
