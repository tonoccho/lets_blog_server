/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth・子コンポーネントは差し替えない。差し替えるのは
// Next.js の実行基盤(next/navigation の redirect、next-auth、next/headers など)と、外部との通信(fetch)だけ(#1832)。
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => { throw new Error("NEXT_NOT_FOUND"); } }));
import { renderToStaticMarkup } from "react-dom/server";
import {
  getServerSessionMock as getServerSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
  type FetchRoutes,
} from "@/lib/__tests__/testUtils/serverRuntime";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = () => textResponse("Service Unavailable", 503);

const EMPTY_LOG_PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 1 };
const LOGS = "GET /api/operation-logs/unified?type=AI_JOB&page=0&size=1";

/** ダッシュボードが呼ぶ gateway の API を、すべて「空・正常」で返す。`overrides` で差し替える。 */
function dashboardApi(overrides: FetchRoutes = {}) {
  return installFetch({
    "GET /api/sites": () => jsonResponse([]),
    "GET /api/posts": () => jsonResponse([]),
    [LOGS]: () => jsonResponse(EMPTY_LOG_PAGE),
    "GET /api/dashboard/service-status": () => jsonResponse([]),
    "GET /api/dashboard/service-status/detail": () => jsonResponse(null),
    "GET /api/dashboard/container-status": () => jsonResponse([]),
    "GET /api/identity/me": () => jsonResponse(null),
    ...overrides,
  });
}

import DashboardPage from "../page";

describe("ダッシュボードの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    resetServerRuntime({ user: { role: "admin" } });
    redirect.mockClear();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、失敗の通知を出し、失敗した件数カードを0件と表示しない", async () => {
    dashboardApi({ "GET /api/sites": DOWN });
    const html = renderToStaticMarkup(await DashboardPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("サイト一覧を取得できませんでした");
    expect(html).not.toContain("投稿一覧を取得できませんでした");
    expect(html).toContain("登録サイト数</div><div class=\"mt-1 text-3xl font-semibold\">-</div>");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して実際に0件のときは従来通り0を表示し、通知は出さない", async () => {
    dashboardApi();
    const html = renderToStaticMarkup(await DashboardPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("登録サイト数</div><div class=\"mt-1 text-3xl font-semibold\">0</div>");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("セッション切れの失敗は /login へリダイレクトする", async () => {
    // 401 は apiClient が SESSION_EXPIRED_MESSAGE の例外に変える。
    dashboardApi({ "GET /api/posts": () => textResponse(SESSION_EXPIRED, 401) });
    await expect(DashboardPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("AIジョブ数は、リンク先(type=AI_JOB)と同じ統合操作ログの件数(totalElements)を表示する(issue #1595)", async () => {
    const fetchMock = dashboardApi({
      [LOGS]: () => jsonResponse({ ...EMPTY_LOG_PAGE, totalElements: 7, totalPages: 1 }),
    });
    const html = renderToStaticMarkup(await DashboardPage());
    expect(fetchMock).toHaveBeenCalledWith(
      "https://localhost/api/operation-logs/unified?type=AI_JOB&page=0&size=1",
      expect.anything(),
    );
    expect(html).toContain("AIジョブ数</div><div class=\"mt-1 text-3xl font-semibold\">7</div>");
  });

  it("AIジョブ件数の取得に失敗したときは0件でなく「-」を表示し、通知を出す(issue #1595)", async () => {
    dashboardApi({ [LOGS]: DOWN });
    const html = renderToStaticMarkup(await DashboardPage());
    expect(html).toContain("AIジョブ数</div><div class=\"mt-1 text-3xl font-semibold\">-</div>");
    expect(html).toContain("AIジョブ件数を取得できませんでした");
  });
});
