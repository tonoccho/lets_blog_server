/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
jest.mock("next/navigation", () => ({
  redirect: jest.fn(),
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
const getServerSession = jest.fn();
jest.mock("next-auth", () => ({ getServerSession: (...a: unknown[]) => getServerSession(...a) }));
jest.mock("@/lib/auth", () => ({ authOptions: {} }));
import { renderToStaticMarkup } from "react-dom/server";

const api = {
  getSiteDetail: jest.fn(),
  listSshKeyPairs: jest.fn(),
  listStaticContent: jest.fn(),
  getSiteAdminPath: jest.fn(),
  getGenerationJob: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getSiteDetail: (...a: unknown[]) => api.getSiteDetail(...a),
  listSshKeyPairs: (...a: unknown[]) => api.listSshKeyPairs(...a),
  listStaticContent: (...a: unknown[]) => api.listStaticContent(...a),
  getSiteAdminPath: (...a: unknown[]) => api.getSiteAdminPath(...a),
  getGenerationJob: (...a: unknown[]) => api.getGenerationJob(...a),
}));
jest.mock("../SiteEditForm", () => ({ SiteEditForm: () => "FORM" }));
jest.mock("../LetsblogPluginPanel", () => ({ LetsblogPluginPanel: () => "PLUGIN" }));
jest.mock("../LetsblogSyncPanel", () => ({ LetsblogSyncPanel: () => "SYNC" }));
jest.mock("../StaticContentPanel", () => ({
  StaticContentPanel: ({ generatedResult }: { generatedResult?: { jobId: number; contentType: string } | null }) =>
    `STATIC[${generatedResult ? `${generatedResult.jobId}:${generatedResult.contentType}` : "none"}]`,
}));
import Page from "../page";

const render = async (searchParams?: Record<string, string>) =>
  renderToStaticMarkup(
    await Page({
      params: Promise.resolve({ id: "5" }),
      searchParams: searchParams ? Promise.resolve(searchParams) : undefined,
    })
  );

const staticJob = (siteId = 5) => ({
  id: 43,
  type: "static_content_generation",
  status: "done",
  resultPayload: JSON.stringify({ siteId, contentType: "OPERATOR_INFO", body: "本文" }),
});

/** issue #1409: 処理キューの「結果を見る」の遷移先。ジョブIDから生成結果を引いて、静的コンテンツのパネルへ渡す。 */
describe("サイト編集ページ: 静的コンテンツ生成ジョブの結果(issue #1409)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.getSiteDetail.mockResolvedValue({ id: 5, name: "S" });
    api.listSshKeyPairs.mockResolvedValue([]);
    api.listStaticContent.mockResolvedValue([]);
    api.getSiteAdminPath.mockResolvedValue({ path: "wp-admin" });
  });

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで描画する", async () => {
    expect(await render()).toContain("STATIC[none]");
    expect(api.getGenerationJob).not.toHaveBeenCalled();
  });

  it("staticContentJob のジョブの結果をパネルへ渡す", async () => {
    api.getGenerationJob.mockResolvedValue(staticJob());
    const html = await render({ staticContentJob: "43" });
    expect(api.getGenerationJob).toHaveBeenCalledWith(43);
    expect(html).toContain("STATIC[43:OPERATOR_INFO]");
  });

  it("別のサイトのジョブの結果は、このサイトの画面には出さない", async () => {
    api.getGenerationJob.mockResolvedValue(staticJob(6));
    expect(await render({ staticContentJob: "43" })).toContain("STATIC[none]");
  });

  it("ジョブを読めなければ結果なしで描画する", async () => {
    api.getGenerationJob.mockRejectedValue(new Error("APIエラー (404)"));
    expect(await render({ staticContentJob: "43" })).toContain("STATIC[none]");
  });
});
