/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・各子コンポーネントは差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installDetailBackend, PROJECT, SITE } from "./projectDetailBackend";
import ProjectDetailPage from "../(detail)/page";

const params = { params: Promise.resolve({ id: "7" }) };
const DOWN = () => textResponse("Service Unavailable", 503);

describe("プロジェクト詳細ページの取得失敗表示(issue #1458: 共有ヘルパーへ移行)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    resetServerRuntime(ADMIN_SESSION);
    installDetailBackend();
  });
  afterEach(() => errorSpy.mockRestore());

  it.each([
    ["GET /api/sites", "サイト一覧"],
    ["GET /api/users", "ユーザー一覧"],
    ["GET /api/projects/:id/bulk-management/categories/comparison", "カテゴリ比較"],
    ["GET /api/projects/:id/api-keys/github-token", "GitHubトークン設定状況"],
    ["GET /api/projects/:id/api-keys/brave-search-api-key", "Brave APIキー設定状況"],
    ["GET /api/projects/:id/image-settings", "画像生成設定"],
  ])("%s の取得失敗では「%s」の通知とログを出す", async (route, label) => {
    installDetailBackend({ [route]: DOWN });
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain('role="alert"');
    expect(html).toContain(`${label}を取得できませんでした`);
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining(`[projects/7] ${label}の取得に失敗しました`), expect.anything());
  });

  it("システム設定の管理画面パスを、紐付いた環境のサイトの管理画面リンクに使う(issue #1530)", async () => {
    installDetailBackend(
      { "GET /api/system-settings/site-admin-path": () => jsonResponse({ path: "secret-admin" }) },
      { ...PROJECT, localSite: SITE, testSite: { ...SITE, id: 2 }, productionSite: { ...SITE, id: 3 } },
    );
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html.match(/href="https:\/\/s\.example\.com\/secret-admin"/g)).toHaveLength(3);
  });

  it("管理画面パスの取得に失敗しても wp-admin にフォールバックしてスロットを描画する(issue #1530)", async () => {
    installDetailBackend({ "GET /api/system-settings/site-admin-path": DOWN }, { ...PROJECT, testSite: SITE });
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain("https://s.example.com/wp-admin");
    expect(html).not.toContain('role="alert"');
  });

  it("メンバー一覧の取得失敗は従来のメンバー専用の文言で示し、共通通知とは重複させない", async () => {
    installDetailBackend({ "GET /api/projects/:id/users": DOWN });
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).toContain("メンバー情報を取得できませんでした");
    expect(html).not.toContain('role="alert"');
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining("プロジェクトメンバーの取得に失敗しました"), expect.anything());
  });

  it("すべて成功したときは通知を出さない", async () => {
    const html = renderToStaticMarkup(await ProjectDetailPage(params));
    expect(html).not.toContain('role="alert"');
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("プロジェクトの取得に失敗したら notFound()(ログも残す)", async () => {
    installDetailBackend({ "GET /api/projects/:id": DOWN });
    await expect(ProjectDetailPage(params)).rejects.toThrow("NEXT_NOT_FOUND");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("セッション切れは /login へ", async () => {
    installDetailBackend({ "GET /api/sites": () => textResponse("Unauthorized", 401) });
    await expect(ProjectDetailPage(params)).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
