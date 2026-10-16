/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・CustomTagTemplateGallery・next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
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
  useRouter: () => ({ push: jest.fn(), refresh: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetchByPath, ADMIN_SESSION, queryOf } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import Page from "../page";

const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** project-service(テンプレート・プロジェクト)の代役(gateway の向こう側)。拒否は 401(セッション切れ)か 503 の応答になる。 */
const api = { getMyCustomTagTemplates: jest.fn(), listCustomTagTemplates: jest.fn(), listProjects: jest.fn() };
function respond(backend: jest.Mock) {
  return async (url: string) => {
    try {
      return jsonResponse(await backend(queryOf(url)));
    } catch (err) {
      if (err instanceof Error && err.message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
      return textResponse("Service Unavailable", 503);
    }
  };
}

const render = async (sp: Record<string, string> = {}) =>
  renderToStaticMarkup(await Page({ searchParams: Promise.resolve(sp) }));

describe("カスタムタグテンプレートの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    for (const backend of Object.values(api)) backend.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    installFetchByPath({
      "GET /api/custom-tag-templates": respond(api.listCustomTagTemplates),
      "GET /api/custom-tag-templates/my-templates": respond(api.getMyCustomTagTemplates),
      "GET /api/projects": respond(api.listProjects),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
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
    expect(html).not.toContain("テンプレートがありません");
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
    expect(html).toContain("テンプレートがありません");
  });

  it("成功して0件のときは通知なしでギャラリーを描画する", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("テンプレートがありません");
  });

  it("セッション切れは /login へ", async () => {
    api.listProjects.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("検索条件(プロジェクト・カテゴリー・検索語・未公開を含める)を一覧 API の検索文字列にして渡し、ギャラリーへも引き継ぐ", async () => {
    const html = await render({ projectId: "3", category: "装飾", search: "ボタン", showAll: "true" });
    expect(api.listCustomTagTemplates).toHaveBeenCalledWith({
      projectId: "3",
      category: "装飾",
      search: "ボタン",
      showAll: "true",
    });
    expect(html).toContain('value="ボタン"');
    expect(html).toContain("未公開を含める");
  });

  it("検索条件が無いときは検索文字列を付けずに一覧を取得する", async () => {
    await render();
    expect(api.listCustomTagTemplates).toHaveBeenCalledWith({});
  });

  it("「自分のテンプレート」は作成者の一覧に検索語とカテゴリーを重ねて表示する", async () => {
    api.getMyCustomTagTemplates.mockResolvedValue([
      { id: 1, templateName: "青いボタン", category: "装飾", description: null, isPublished: true },
      { id: 2, templateName: "赤いボタン", category: "その他", description: null, isPublished: false },
      { id: 3, templateName: "見出し", category: "装飾", description: null, isPublished: false },
    ]);
    const html = await render({ mine: "true", search: "ボタン", category: "装飾" });
    expect(html).toContain("青いボタン");
    expect(html).not.toContain("赤いボタン");
    expect(html).not.toContain("見出し");
    expect(api.listCustomTagTemplates).not.toHaveBeenCalled();
  });
});
