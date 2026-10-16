/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・@/lib/llmJobResults・Breadcrumb・Tabs・ProjectSectionNav・
// TagDesignSettingsPanel・ProjectCustomTagManager・CssBundleViewer は差し替えない。差し替えるのは Next.js の実行基盤・
// `next-auth/react`(ブラウザのセッション取得)と外部との通信(fetch)だけ(#1835)。
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
jest.mock("next-auth/react", () => ({
  useSession: () => ({ data: { user: { email: "a@example.com", role: "admin" }, expires: "2099-01-01" }, status: "authenticated" }),
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ProjectTagsPage from "../page";

// 各タブにだけ出る見出し。どのタブが開いているかを、描画された中身で確かめる。
const CUSTOM_TAGS = "このプロジェクト専用のカスタムタグです。";

type Respond = () => Response | Promise<Response>;
let getProject: Respond;
let getTagDesign: Respond;
let listCustomTags: Respond;
let getContentSettings: Respond;
let getJob: (id: string) => Response;
let jobRequests: string[];

const PRESET = { id: "default", label: "標準", backgroundColor: "#ffffff", textColor: "#111111", accentColor: "#2563eb" };
const SETTING = {
  tagType: "TOC",
  presetId: "default",
  backgroundColor: "#ffffff",
  textColor: "#111111",
  accentColor: "#2563eb",
  customCss: null,
  htmlTemplate: null,
};

function install() {
  resetServerRuntime(ADMIN_SESSION);
  jobRequests = [];
  getProject = () => jsonResponse({ id: 7, name: "案件", slug: "s" });
  getTagDesign = () => jsonResponse({ presets: [PRESET], settings: [SETTING] });
  listCustomTags = () => jsonResponse([]);
  getContentSettings = () => jsonResponse({ cssSelectorPrefix: "p" });
  getJob = () => textResponse("not found", 404);
  installFetch({
    "GET /api/projects/:id": () => getProject(),
    "GET /api/projects/:id/tag-design-settings": () => getTagDesign(),
    "GET /api/projects/:id/custom-tags": () => listCustomTags(),
    "GET /api/projects/:id/content-settings": () => getContentSettings(),
    "GET /api/generation-jobs/:id": (_u, _i, [id]) => {
      jobRequests.push(id);
      return getJob(id);
    },
  });
}

const render = async (searchParams?: Record<string, string>) =>
  renderToStaticMarkup(
    await ProjectTagsPage({
      params: Promise.resolve({ id: "7" }),
      searchParams: searchParams ? Promise.resolve(searchParams) : undefined,
    })
  );

const DOWN = () => textResponse("Service Unavailable", 503);

describe("プロジェクトタグページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    install();
  });
  afterEach(() => errorSpy.mockRestore());

  it("カスタムタグ一覧の取得失敗では、通知とログを出し「0件」のタグ管理を描画しない", async () => {
    listCustomTags = DOWN;
    const html = await render({ tab: "custom-tags" });
    expect(html).toContain('role="alert"');
    expect(html).toContain("カスタムタグ一覧を取得できませんでした");
    expect(html).toContain("取得に失敗したため、カスタムタグを表示できません。");
    expect(html).not.toContain(CUSTOM_TAGS);
    expect(errorSpy).toHaveBeenCalled();
  });

  it("CSSセレクタ接頭辞の取得失敗では、未設定に見えるタグ管理を描画しない", async () => {
    getContentSettings = DOWN;
    const html = await render({ tab: "custom-tags" });
    expect(html).toContain("CSSセレクタ接頭辞を取得できませんでした");
    expect(html).not.toContain(CUSTOM_TAGS);
  });

  it("成功して0件のときは従来通り通知なしでタグ管理を描画する", async () => {
    const html = await render({ tab: "custom-tags" });
    expect(html).not.toContain('role="alert"');
    expect(html).toContain(CUSTOM_TAGS);
    expect(html).toContain("登録済みカスタムタグはありません");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    getProject = DOWN;
    await expect(render()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("セッション切れは /login へ", async () => {
    listCustomTags = () => textResponse("Unauthorized", 401);
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
