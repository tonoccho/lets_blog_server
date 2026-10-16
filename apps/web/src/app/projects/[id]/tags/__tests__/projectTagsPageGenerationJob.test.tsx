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
const TAG_DESIGN = "組み込みタグの見た目を";
const CUSTOM_TAGS = "このプロジェクト専用のカスタムタグです。";
const CSS_BUNDLE = "まとめて";

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

const customTagJob = (projectId: number | null = 7) => ({
  id: 42,
  type: "custom_tag_generation",
  status: "done",
  resultPayload: JSON.stringify({ tagName: "blue", description: null, projectId, htmlTemplate: "<b/>", cssContent: ".b{}" }),
});
const tagDesignJob = (projectId: number | null = 7) => ({
  id: 44,
  type: "tag_design_generation",
  status: "done",
  resultPayload: JSON.stringify({ projectId, tagType: "TOC", htmlTemplate: "", cssContent: ".a{}" }),
});

const GENERATED_TAG = 'data-testid="custom-tag-generation-result"';
const GENERATED_DESIGN = 'data-testid="tag-design-generated"';

/** issue #1409: 処理キューの「結果を見る」の遷移先。ジョブIDから生成結果を引いて、該当パネルへ渡す。 */
describe("プロジェクトのタグ画面: 生成ジョブの結果(issue #1409)", () => {
  beforeEach(install);

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで描画する。既定で開くのは先頭のタブ", async () => {
    const html = await render();
    expect(jobRequests).toEqual([]);
    expect(html).not.toContain(GENERATED_DESIGN);
    expect(html).toContain(TAG_DESIGN);
    expect(html).not.toContain(CUSTOM_TAGS);
  });

  it("?tab= で最初に開くタブを選べる", async () => {
    const custom = await render({ tab: "custom-tags" });
    expect(custom).toContain(CUSTOM_TAGS);
    expect(custom).not.toContain(TAG_DESIGN);
    const css = await render({ tab: "css-bundle" });
    expect(css).toContain(CSS_BUNDLE);
    expect(css).toContain("読み込み中...");
  });

  it("customTagJob のジョブの結果をカスタムタグ管理へ渡す", async () => {
    getJob = () => jsonResponse(customTagJob());
    const html = await render({ tab: "custom-tags", customTagJob: "42" });
    expect(jobRequests).toEqual(["42"]);
    expect(html).toContain(GENERATED_TAG);
    expect(html).toContain("[blue]");
  });

  it("tagDesignJob のジョブの結果をタグデザインへ渡す", async () => {
    getJob = () => jsonResponse(tagDesignJob());
    const html = await render({ tab: "tag-design", tagDesignJob: "44" });
    expect(jobRequests).toEqual(["44"]);
    expect(html).toContain(GENERATED_DESIGN);
    expect(html).toContain(".a{}");
  });

  it("別のプロジェクトのジョブの結果は、このプロジェクトの画面には出さない", async () => {
    getJob = () => jsonResponse(customTagJob(8));
    expect(await render({ tab: "custom-tags", customTagJob: "42" })).not.toContain(GENERATED_TAG);
    getJob = () => jsonResponse(tagDesignJob(8));
    expect(await render({ tagDesignJob: "44" })).not.toContain(GENERATED_DESIGN);
    // グローバル(projectId null)の結果もプロジェクトの画面には出さない
    getJob = () => jsonResponse(tagDesignJob(null));
    expect(await render({ tagDesignJob: "44" })).not.toContain(GENERATED_DESIGN);
  });

  it("ジョブを読めなければ(他人のジョブ・存在しない)結果なしで描画する", async () => {
    getJob = () => textResponse("not found", 404);
    const html = await render({ customTagJob: "42", tagDesignJob: "44" });
    expect(html).not.toContain(GENERATED_TAG);
    expect(html).not.toContain(GENERATED_DESIGN);
  });
});
