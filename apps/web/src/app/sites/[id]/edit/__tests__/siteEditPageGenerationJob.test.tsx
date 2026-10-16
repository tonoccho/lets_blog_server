/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/llmJobResults・各パネル・next/link は差し替えない。
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
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import Page from "../page";

/** gateway の向こう側(各サービス)の代役。 */
const api = {
  getSiteDetail: jest.fn(),
  listSshKeyPairs: jest.fn(),
  listStaticContent: jest.fn(),
  getSiteAdminPath: jest.fn(),
  getGenerationJob: jest.fn(),
};
const respond = (backend: jest.Mock) => async (_u: string, _i: RequestInit, params: string[]) => {
  try {
    return jsonResponse(await backend(...params.map(Number)));
  } catch (err) {
    return textResponse(err instanceof Error ? err.message : String(err), 404);
  }
};

const render = async (searchParams?: Record<string, string>) =>
  renderToStaticMarkup(
    await Page({
      params: Promise.resolve({ id: "5" }),
      searchParams: searchParams ? Promise.resolve(searchParams) : undefined,
    })
  );

// 生成結果が渡されたときだけ、StaticContentPanel は未保存の結果を示す。
const GENERATED = 'data-testid="static-content-generated"';

const staticJob = (siteId = 5) => ({
  id: 43,
  type: "static_content_generation",
  status: "done",
  resultPayload: JSON.stringify({ siteId, contentType: "OPERATOR_INFO", body: "本文" }),
});

/** issue #1409: 処理キューの「結果を見る」の遷移先。ジョブIDから生成結果を引いて、静的コンテンツのパネルへ渡す。 */
describe("サイト編集ページ: 静的コンテンツ生成ジョブの結果(issue #1409)", () => {
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
    resetServerRuntime({ user: { role: "admin" } });
    installFetch({
      "GET /api/sites/:id": respond(api.getSiteDetail),
      "GET /api/ssh-key-pairs": respond(api.listSshKeyPairs),
      "GET /api/sites/:id/static-content": respond(api.listStaticContent),
      "GET /api/system-settings/site-admin-path": respond(api.getSiteAdminPath),
      "GET /api/generation-jobs/:id": respond(api.getGenerationJob),
    });
    api.getSiteDetail.mockResolvedValue({
      id: 5, name: "S", siteKey: "s-key", cmsType: "WORDPRESS", baseUrl: "https://s.example.com",
      credentials: {}, configuredSecretFields: [], adminPath: null,
    });
    api.listSshKeyPairs.mockResolvedValue([]);
    api.listStaticContent.mockResolvedValue([]);
    api.getSiteAdminPath.mockResolvedValue({ path: "wp-admin" });
  });

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで描画する", async () => {
    expect(await render()).not.toContain(GENERATED);
    expect(api.getGenerationJob).not.toHaveBeenCalled();
  });

  it("staticContentJob のジョブの結果をパネルへ渡す", async () => {
    api.getGenerationJob.mockResolvedValue(staticJob());
    const html = await render({ staticContentJob: "43" });
    expect(api.getGenerationJob).toHaveBeenCalledWith(43);
    expect(html).toContain(GENERATED);
    expect(html).toContain('data-job-id="43"');
    expect(html).toContain("本文");
  });

  it("別のサイトのジョブの結果は、このサイトの画面には出さない", async () => {
    api.getGenerationJob.mockResolvedValue(staticJob(6));
    expect(await render({ staticContentJob: "43" })).not.toContain(GENERATED);
  });

  it("ジョブを読めなければ結果なしで描画する", async () => {
    api.getGenerationJob.mockRejectedValue(new Error("APIエラー (404)"));
    expect(await render({ staticContentJob: "43" })).not.toContain(GENERATED);
  });
});
