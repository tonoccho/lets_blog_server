/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
import { renderToStaticMarkup } from "react-dom/server";

jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn().mockResolvedValue(undefined) }));
const api = { getTagDesignSettings: jest.fn(), getGenerationJob: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  getTagDesignSettings: (...a: unknown[]) => api.getTagDesignSettings(...a),
  getGenerationJob: (...a: unknown[]) => api.getGenerationJob(...a),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => "BREADCRUMB" }));
jest.mock("../../../projects/[id]/tag-design/TagDesignSettingsPanel", () => ({
  TagDesignSettingsPanel: ({ projectId, jobResult }: { projectId: number | null; jobResult?: { jobId: number; tagType: string } | null }) =>
    `PANEL[${projectId}|${jobResult ? `${jobResult.jobId}:${jobResult.tagType}` : "none"}]`,
}));
import GlobalTagDesignPage from "../page";

const render = async (searchParams?: Record<string, string>) =>
  renderToStaticMarkup(await GlobalTagDesignPage({ searchParams: searchParams ? Promise.resolve(searchParams) : undefined }));

const job = (projectId: number | null) => ({
  id: 45,
  type: "tag_design_generation",
  status: "done",
  resultPayload: JSON.stringify({ projectId, tagType: "BLOGCARD", htmlTemplate: "", cssContent: ".a{}" }),
});

/** issue #1409: グローバルのタグデザイン生成の「結果を見る」の遷移先。 */
describe("グローバルタグデザイン画面: 生成ジョブの結果(issue #1409)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.getTagDesignSettings.mockResolvedValue({ presets: [], settings: [] });
  });

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで描画する", async () => {
    expect(await render()).toContain("PANEL[null|none]");
    expect(api.getGenerationJob).not.toHaveBeenCalled();
  });

  it("tagDesignJob のグローバルの結果をパネルへ渡す", async () => {
    api.getGenerationJob.mockResolvedValue(job(null));
    expect(await render({ tagDesignJob: "45" })).toContain("PANEL[null|45:BLOGCARD]");
    expect(api.getGenerationJob).toHaveBeenCalledWith(45);
  });

  it("プロジェクト個別の結果は、グローバルの画面には出さない", async () => {
    api.getGenerationJob.mockResolvedValue(job(7));
    expect(await render({ tagDesignJob: "45" })).toContain("PANEL[null|none]");
  });

  it("ジョブを読めなければ結果なしで描画する", async () => {
    api.getGenerationJob.mockRejectedValue(new Error("APIエラー (404)"));
    expect(await render({ tagDesignJob: "45" })).toContain("PANEL[null|none]");
  });
});
