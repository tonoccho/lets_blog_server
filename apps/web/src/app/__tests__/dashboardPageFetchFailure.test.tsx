/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
const getServerSession = jest.fn();
jest.mock("next-auth", () => ({ getServerSession: (...a: unknown[]) => getServerSession(...a) }));
jest.mock("@/lib/auth", () => ({ authOptions: {} }));
import { renderToStaticMarkup } from "react-dom/server";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

const api = {
  listSites: jest.fn(), listPosts: jest.fn(), listGenerationJobs: jest.fn(),
  getConnectedServiceStatuses: jest.fn(), getConnectedServiceStatusDetail: jest.fn(),
  getContainerStatuses: jest.fn(), getMyProfile: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  listSites: (...a: unknown[]) => api.listSites(...a),
  listPosts: (...a: unknown[]) => api.listPosts(...a),
  listGenerationJobs: (...a: unknown[]) => api.listGenerationJobs(...a),
  getConnectedServiceStatuses: (...a: unknown[]) => api.getConnectedServiceStatuses(...a),
  getConnectedServiceStatusDetail: (...a: unknown[]) => api.getConnectedServiceStatusDetail(...a),
  getContainerStatuses: (...a: unknown[]) => api.getContainerStatuses(...a),
  getMyProfile: (...a: unknown[]) => api.getMyProfile(...a),
}));
jest.mock("../ConnectedServiceStatusPanel", () => ({ ConnectedServiceStatusPanel: () => null }));
jest.mock("../ContainerStatusPanel", () => ({ ContainerStatusPanel: () => null }));
import DashboardPage from "../page";

describe("ダッシュボードの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.listSites.mockResolvedValue([]);
    api.listPosts.mockResolvedValue([]);
    api.listGenerationJobs.mockResolvedValue([]);
    api.getConnectedServiceStatuses.mockResolvedValue([]);
    api.getConnectedServiceStatusDetail.mockResolvedValue(null);
    api.getContainerStatuses.mockResolvedValue([]);
    api.getMyProfile.mockResolvedValue(null);
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、失敗の通知を出し、失敗した件数カードを0件と表示しない", async () => {
    api.listSites.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await DashboardPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("サイト一覧を取得できませんでした");
    expect(html).not.toContain("投稿一覧を取得できませんでした");
    expect(html).toContain("登録サイト数</div><div class=\"mt-1 text-3xl font-semibold\">-</div>");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して実際に0件のときは従来通り0を表示し、通知は出さない", async () => {
    const html = renderToStaticMarkup(await DashboardPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("登録サイト数</div><div class=\"mt-1 text-3xl font-semibold\">0</div>");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("セッション切れの失敗は /login へリダイレクトする", async () => {
    api.listPosts.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(DashboardPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
