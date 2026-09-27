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

const api = {
  getMyCustomTagTemplates: jest.fn(), listCustomTagTemplates: jest.fn(), listProjects: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getMyCustomTagTemplates: (...a: unknown[]) => api.getMyCustomTagTemplates(...a),
  listCustomTagTemplates: (...a: unknown[]) => api.listCustomTagTemplates(...a),
  listProjects: (...a: unknown[]) => api.listProjects(...a),
}));
jest.mock("../CustomTagTemplateGallery", () => ({ CustomTagTemplateGallery: () => "GALLERY" }));
import Page from "../page";

const render = async (sp: Record<string, string> = {}) =>
  renderToStaticMarkup(await Page({ searchParams: Promise.resolve(sp) }));

describe("カスタムタグテンプレートの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.listCustomTagTemplates.mockResolvedValue([]);
    api.getMyCustomTagTemplates.mockResolvedValue([]);
    api.listProjects.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("テンプレート一覧の取得失敗で通知を出し、「テンプレートがありません」を出すギャラリーを描画しない", async () => {
    api.listCustomTagTemplates.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain('role="alert"');
    expect(html).toContain("テンプレート一覧を取得できませんでした");
    expect(html).not.toContain("GALLERY");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("「自分のテンプレート」の取得失敗でも通知する", async () => {
    api.getMyCustomTagTemplates.mockRejectedValue(DOWN);
    const html = await render({ mine: "true" });
    expect(html).toContain("テンプレート一覧を取得できませんでした");
  });

  it("プロジェクト一覧の失敗は通知するがギャラリーは出す", async () => {
    api.listProjects.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("プロジェクト一覧を取得できませんでした");
    expect(html).toContain("GALLERY");
  });

  it("成功して0件のときは通知なしでギャラリーを描画する", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("GALLERY");
  });

  it("セッション切れは /login へ", async () => {
    api.listProjects.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
