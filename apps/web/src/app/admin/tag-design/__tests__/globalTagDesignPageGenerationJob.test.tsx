/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・TagDesignSettingsPanel・Breadcrumb・next/linkは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

// ページが返すツリーは描画しない。パネル要素の props を読んで、ページが何を渡したかを確かめる。
import GlobalTagDesignPage from "../page";
import { TagDesignSettingsPanel } from "../../../projects/[id]/tag-design/TagDesignSettingsPanel";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

interface ElementLike {
  type: unknown;
  props: Record<string, unknown>;
}

function collect(node: unknown, predicate: (e: ElementLike) => boolean, out: ElementLike[] = []): ElementLike[] {
  if (node == null || typeof node !== "object") return out;
  if (Array.isArray(node)) {
    node.forEach((child) => collect(child, predicate, out));
    return out;
  }
  const element = node as ElementLike;
  if (predicate(element)) out.push(element);
  if (element.props && "children" in element.props) collect(element.props.children, predicate, out);
  return out;
}

const OVERVIEW = { presets: [{ id: "standard" }], settings: [{ tagType: "TOC" }] };

/** ページが TagDesignSettingsPanel に渡した props。 */
const render = async (searchParams?: Record<string, string>) => {
  const tree = await GlobalTagDesignPage({ searchParams: searchParams ? Promise.resolve(searchParams) : undefined });
  return collect(tree, (e) => e.type === TagDesignSettingsPanel)[0].props as {
    projectId: number | null;
    presets: unknown;
    settings: unknown;
    jobResult: { jobId: number; tagType: string } | null;
  };
};

const job = (projectId: number | null, overrides: Record<string, unknown> = {}) => ({
  id: 45,
  type: "tag_design_generation",
  status: "done",
  resultPayload: JSON.stringify({ projectId, tagType: "BLOGCARD", htmlTemplate: "", cssContent: ".a{}" }),
  ...overrides,
});

let fetchMock: jest.Mock;
const jobRequests = () => fetchMock.mock.calls.filter(([url]) => String(url).includes("/api/generation-jobs"));

function install(jobResponse: () => Response = () => jsonResponse(job(null))) {
  fetchMock = installFetch({
    "GET /api/tag-design-settings": () => jsonResponse(OVERVIEW),
    "GET /api/generation-jobs/:id": jobResponse,
  });
}

/** issue #1409: グローバルのタグデザイン生成の「結果を見る」の遷移先。 */
describe("グローバルタグデザイン画面: 生成ジョブの結果(issue #1409)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    install();
  });

  it("ジョブの指定が無ければジョブを取りに行かず、結果なしで、グローバル(projectId=null)の設定を渡す", async () => {
    const props = await render();

    expect(props.projectId).toBeNull();
    expect(props.presets).toEqual(OVERVIEW.presets);
    expect(props.settings).toEqual(OVERVIEW.settings);
    expect(props.jobResult).toBeNull();
    expect(jobRequests()).toHaveLength(0);
  });

  it("tagDesignJob のグローバルの結果をパネルへ渡す", async () => {
    const props = await render({ tagDesignJob: "45" });

    expect(props.jobResult).toMatchObject({ jobId: 45, tagType: "BLOGCARD" });
    expect(jobRequests().map(([url]) => String(url))).toEqual([expect.stringMatching(/\/api\/generation-jobs\/45$/)]);
  });

  it("プロジェクト個別の結果は、グローバルの画面には出さない", async () => {
    install(() => jsonResponse(job(7)));

    expect((await render({ tagDesignJob: "45" })).jobResult).toBeNull();
  });

  it("ジョブを読めなければ結果なしで描画する", async () => {
    install(() => textResponse("not found", 404));

    expect((await render({ tagDesignJob: "45" })).jobResult).toBeNull();
  });

  it("管理者でなければトップへ送り、設定を取得しない", async () => {
    resetServerRuntime(USER_SESSION);
    install();

    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
