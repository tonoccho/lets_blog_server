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
const api = {
  getProject: jest.fn(),
  getProjectContentSettings: jest.fn(),
  getTagDesignSettings: jest.fn(),
  listProjectCustomTags: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getProject: (...a: unknown[]) => api.getProject(...a),
  getProjectContentSettings: (...a: unknown[]) => api.getProjectContentSettings(...a),
  getTagDesignSettings: (...a: unknown[]) => api.getTagDesignSettings(...a),
  listProjectCustomTags: (...a: unknown[]) => api.listProjectCustomTags(...a),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => "BREADCRUMB" }));
jest.mock("@/components/Tabs", () => ({
  Tabs: ({ tabs }: { tabs: { id: string; content: unknown }[] }) => (
    <div>{tabs.map((t) => <section key={t.id}>{t.content as never}</section>)}</div>
  ),
}));
jest.mock("../../ProjectSectionNav", () => ({ ProjectSectionNav: () => "NAV" }));
jest.mock("../../tag-design/TagDesignSettingsPanel", () => ({ TagDesignSettingsPanel: () => "TAG_DESIGN" }));
jest.mock("../../custom-tags/ProjectCustomTagManager", () => ({
  ProjectCustomTagManager: ({ tags }: { tags: unknown[] }) => `TAG_MANAGER(${tags.length})`,
}));
jest.mock("../../custom-tags/CssBundleViewer", () => ({ CssBundleViewer: () => "CSS_BUNDLE" }));
import ProjectTagsPage from "../page";

const params = { params: Promise.resolve({ id: "7" }) };

describe("プロジェクトタグページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.getProject.mockResolvedValue({ id: 7, name: "案件", slug: "s" });
    api.getTagDesignSettings.mockResolvedValue({ presets: [], settings: {} });
    api.listProjectCustomTags.mockResolvedValue([]);
    api.getProjectContentSettings.mockResolvedValue({ cssSelectorPrefix: "p" });
  });
  afterEach(() => errorSpy.mockRestore());

  it("カスタムタグ一覧の取得失敗では、通知とログを出し「0件」のタグ管理を描画しない", async () => {
    api.listProjectCustomTags.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectTagsPage(params));
    expect(html).toContain('role="alert"');
    expect(html).toContain("カスタムタグ一覧を取得できませんでした");
    expect(html).not.toContain("TAG_MANAGER");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("CSSセレクタ接頭辞の取得失敗では、未設定に見えるタグ管理を描画しない", async () => {
    api.getProjectContentSettings.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectTagsPage(params));
    expect(html).toContain("CSSセレクタ接頭辞を取得できませんでした");
    expect(html).not.toContain("TAG_MANAGER");
  });

  it("成功して0件のときは従来通り通知なしでタグ管理を描画する", async () => {
    const html = renderToStaticMarkup(await ProjectTagsPage(params));
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("TAG_MANAGER(0)");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    api.getProject.mockRejectedValue(DOWN);
    await expect(ProjectTagsPage(params)).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("セッション切れは /login へ", async () => {
    api.listProjectCustomTags.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ProjectTagsPage(params)).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
