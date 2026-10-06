/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: jest.fn(), notFound: () => notFound() }));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
import { renderToStaticMarkup } from "react-dom/server";

jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn().mockResolvedValue(undefined) }));
const api = {
  getProject: jest.fn(),
  getProjectContentSettings: jest.fn(),
  getTagDesignSettings: jest.fn(),
  listProjectCustomTags: jest.fn(),
  getGenerationJob: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getProject: (...a: unknown[]) => api.getProject(...a),
  getProjectContentSettings: (...a: unknown[]) => api.getProjectContentSettings(...a),
  getTagDesignSettings: (...a: unknown[]) => api.getTagDesignSettings(...a),
  listProjectCustomTags: (...a: unknown[]) => api.listProjectCustomTags(...a),
  getGenerationJob: (...a: unknown[]) => api.getGenerationJob(...a),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => "BREADCRUMB" }));
jest.mock("@/components/Tabs", () => ({
  Tabs: ({ tabs, defaultTabId }: { tabs: { id: string; content: unknown }[]; defaultTabId?: string }) => (
    <div>
      {`DEFAULT_TAB[${defaultTabId ?? ""}]`}
      {tabs.map((t) => <section key={t.id}>{t.content as never}</section>)}
    </div>
  ),
}));
jest.mock("../../ProjectSectionNav", () => ({ ProjectSectionNav: () => "NAV" }));
jest.mock("../../tag-design/TagDesignSettingsPanel", () => ({
  TagDesignSettingsPanel: ({ jobResult }: { jobResult?: { jobId: number; tagType: string } | null }) =>
    `TAG_DESIGN[${jobResult ? `${jobResult.jobId}:${jobResult.tagType}` : "none"}]`,
}));
jest.mock("../../custom-tags/ProjectCustomTagManager", () => ({
  ProjectCustomTagManager: ({ generatedResult }: { generatedResult?: { jobId: number; tagName: string } | null }) =>
    `TAG_MANAGER[${generatedResult ? `${generatedResult.jobId}:${generatedResult.tagName}` : "none"}]`,
}));
jest.mock("../../custom-tags/CssBundleViewer", () => ({ CssBundleViewer: () => "CSS_BUNDLE" }));
import ProjectTagsPage from "../page";

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
  resultPayload: JSON.stringify({
    tagName: "blue",
    description: null,
    projectId,
    htmlTemplate: "<b/>",
    cssContent: ".b{}",
  }),
});
const tagDesignJob = (projectId: number | null = 7) => ({
  id: 44,
  type: "tag_design_generation",
  status: "done",
  resultPayload: JSON.stringify({ projectId, tagType: "TOC", htmlTemplate: "", cssContent: ".a{}" }),
});

/** issue #1409: 処理キューの「結果を見る」の遷移先。ジョブIDから生成結果を引いて、該当パネルへ渡す。 */
describe("プロジェクトのタグ画面: 生成ジョブの結果(issue #1409)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.getProject.mockResolvedValue({ id: 7, name: "案件", slug: "s" });
    api.getTagDesignSettings.mockResolvedValue({ presets: [], settings: [] });
    api.listProjectCustomTags.mockResolvedValue([]);
    api.getProjectContentSettings.mockResolvedValue({ cssSelectorPrefix: "p" });
  });

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで描画する", async () => {
    const html = await render();
    expect(api.getGenerationJob).not.toHaveBeenCalled();
    expect(html).toContain("TAG_MANAGER[none]");
    expect(html).toContain("TAG_DESIGN[none]");
    expect(html).toContain("DEFAULT_TAB[]");
  });

  it("?tab= で最初に開くタブを選べる", async () => {
    expect(await render({ tab: "custom-tags" })).toContain("DEFAULT_TAB[custom-tags]");
  });

  it("customTagJob のジョブの結果をカスタムタグ管理へ渡す", async () => {
    api.getGenerationJob.mockResolvedValue(customTagJob());
    const html = await render({ tab: "custom-tags", customTagJob: "42" });
    expect(api.getGenerationJob).toHaveBeenCalledWith(42);
    expect(html).toContain("TAG_MANAGER[42:blue]");
    expect(html).toContain("TAG_DESIGN[none]");
  });

  it("tagDesignJob のジョブの結果をタグデザインへ渡す", async () => {
    api.getGenerationJob.mockResolvedValue(tagDesignJob());
    const html = await render({ tab: "tag-design", tagDesignJob: "44" });
    expect(api.getGenerationJob).toHaveBeenCalledWith(44);
    expect(html).toContain("TAG_DESIGN[44:TOC]");
    expect(html).toContain("TAG_MANAGER[none]");
  });

  it("別のプロジェクトのジョブの結果は、このプロジェクトの画面には出さない", async () => {
    api.getGenerationJob.mockResolvedValueOnce(customTagJob(8));
    expect(await render({ customTagJob: "42" })).toContain("TAG_MANAGER[none]");
    api.getGenerationJob.mockResolvedValueOnce(tagDesignJob(8));
    expect(await render({ tagDesignJob: "44" })).toContain("TAG_DESIGN[none]");
    // グローバル(projectId null)の結果もプロジェクトの画面には出さない
    api.getGenerationJob.mockResolvedValueOnce(tagDesignJob(null));
    expect(await render({ tagDesignJob: "44" })).toContain("TAG_DESIGN[none]");
  });

  it("ジョブを読めなければ(他人のジョブ・存在しない)結果なしで描画する", async () => {
    api.getGenerationJob.mockRejectedValue(new Error("APIエラー (404)"));
    const html = await render({ customTagJob: "42", tagDesignJob: "44" });
    expect(html).toContain("TAG_MANAGER[none]");
    expect(html).toContain("TAG_DESIGN[none]");
  });
});
