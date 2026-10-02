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
import { renderToStaticMarkup } from "react-dom/server";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

const api = {
  getProject: jest.fn(),
  listSites: jest.fn(),
  listProjectUsers: jest.fn(),
  listUsers: jest.fn(),
  listCategoryComparison: jest.fn(),
  getProjectGithubTokenStatus: jest.fn(),
  getProjectBraveSearchApiKeyStatus: jest.fn(),
  getProjectImageSettings: jest.fn(),
  getSiteAdminPath: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getProject: (...a: unknown[]) => api.getProject(...a),
  listSites: (...a: unknown[]) => api.listSites(...a),
  listProjectUsers: (...a: unknown[]) => api.listProjectUsers(...a),
  listUsers: (...a: unknown[]) => api.listUsers(...a),
  listCategoryComparison: (...a: unknown[]) => api.listCategoryComparison(...a),
  getProjectGithubTokenStatus: (...a: unknown[]) => api.getProjectGithubTokenStatus(...a),
  getProjectBraveSearchApiKeyStatus: (...a: unknown[]) => api.getProjectBraveSearchApiKeyStatus(...a),
  getProjectImageSettings: (...a: unknown[]) => api.getProjectImageSettings(...a),
  getSiteAdminPath: (...a: unknown[]) => api.getSiteAdminPath(...a),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue(null),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => "BREADCRUMB" }));
jest.mock("@/components/Tabs", () => ({
  Tabs: ({ tabs }: { tabs: { id: string; content: unknown }[] }) => (
    <div>{tabs.map((t) => <section key={t.id}>{t.content as never}</section>)}</div>
  ),
}));
for (const name of [
  "ProjectSectionNav", "MasterEnvironmentSelector", "ProjectGithubRepositoryForm",
  "ProjectApiKeysForm", "EnvironmentSyncPanel", "BulkManagementPanel", "GarbageCollectionPanel",
  "ProjectAiModelsPanel", "ProjectAssetGenerationPanel", "ProjectImageGenerationPromptDefaultsForm",
  "ProjectImageGenerationSizeDefaultsForm", "ProjectArticleImageResizeDefaultForm",
  "ProjectImageContentFilterSettingsForm", "ProjectNameForm", "DeleteProjectButton", "ProjectUserManager",
  "AddProjectUserModal",
]) {
  jest.mock(`../${name}`, () => ({ [name]: () => null }));
}
jest.mock("../EnvironmentSlot", () => ({
  EnvironmentSlot: ({ environment, adminPath }: { environment: string; adminPath: string }) => `SLOT[${environment}:${adminPath}]`,
}));
import ProjectDetailPage from "../(detail)/page";

const params = { params: Promise.resolve({ id: "7" }) };
const project = { id: 7, name: "案件", slug: "s", localSite: null, testSite: null, productionSite: null };

describe("プロジェクト詳細ページの取得失敗表示(issue #1458: 共有ヘルパーへ移行)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    api.getProject.mockResolvedValue(project);
    api.listSites.mockResolvedValue([]);
    api.listProjectUsers.mockResolvedValue([]);
    api.listUsers.mockResolvedValue([]);
    api.listCategoryComparison.mockResolvedValue({ items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" });
    api.getProjectGithubTokenStatus.mockResolvedValue({ configured: false });
    api.getProjectBraveSearchApiKeyStatus.mockResolvedValue({ configured: false });
    api.getProjectImageSettings.mockResolvedValue({});
    api.getSiteAdminPath.mockResolvedValue({ path: "wp-admin" });
  });
  afterEach(() => errorSpy.mockRestore());

  it.each([
    ["listSites", "サイト一覧"],
    ["listUsers", "ユーザー一覧"],
    ["listCategoryComparison", "カテゴリ比較"],
    ["getProjectGithubTokenStatus", "GitHubトークン設定状況"],
    ["getProjectBraveSearchApiKeyStatus", "Brave APIキー設定状況"],
    ["getProjectImageSettings", "画像生成設定"],
  ] as const)("%s の取得失敗では「%s」の通知とログを出す", async (fn, label) => {
    api[fn].mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain('role="alert"');
    expect(html).toContain(`${label}を取得できませんでした`);
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining(`[projects/7] ${label}の取得に失敗しました`), DOWN);
  });

  it("システム設定の管理画面パスを3環境すべてのスロットに渡す(issue #1530)", async () => {
    api.getSiteAdminPath.mockResolvedValue({ path: "secret-admin" });
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain("SLOT[local:secret-admin]");
    expect(html).toContain("SLOT[test:secret-admin]");
    expect(html).toContain("SLOT[production:secret-admin]");
  });

  it("管理画面パスの取得に失敗しても wp-admin にフォールバックしてスロットを描画する(issue #1530)", async () => {
    api.getSiteAdminPath.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain("SLOT[test:wp-admin]");
    expect(html).not.toContain('role="alert"');
  });

  it("メンバー一覧の取得失敗は従来のメンバー専用の文言で示し、共通通知とは重複させない", async () => {
    api.listProjectUsers.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain("メンバー情報を取得できませんでした");
    expect(html).not.toContain('role="alert"');
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining("プロジェクトメンバーの取得に失敗しました"), DOWN);
  });

  it("すべて成功したときは通知を出さない", async () => {
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).not.toContain('role="alert"');
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("プロジェクトの取得に失敗したら notFound()(ログも残す)", async () => {
    api.getProject.mockRejectedValue(DOWN);
    await expect(ProjectDetailPage(params)).rejects.toThrow("NEXT_NOT_FOUND");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("セッション切れは /login へ", async () => {
    api.listSites.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ProjectDetailPage(params)).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
