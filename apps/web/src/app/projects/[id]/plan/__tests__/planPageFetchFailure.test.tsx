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
  getProject: jest.fn(), listArticlePlanSessions: jest.fn(), listArticlePlanIssues: jest.fn(),
  getArticlePlanSessionByIssue: jest.fn(), getArticlePlanIssueDescription: jest.fn(), getMyProfile: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  getProject: (...a: unknown[]) => api.getProject(...a),
  listArticlePlanSessions: (...a: unknown[]) => api.listArticlePlanSessions(...a),
  listArticlePlanIssues: (...a: unknown[]) => api.listArticlePlanIssues(...a),
  getArticlePlanSessionByIssue: (...a: unknown[]) => api.getArticlePlanSessionByIssue(...a),
  getArticlePlanIssueDescription: (...a: unknown[]) => api.getArticlePlanIssueDescription(...a),
  getMyProfile: (...a: unknown[]) => api.getMyProfile(...a),
}));
jest.mock("../../ProjectSectionNav", () => ({ ProjectSectionNav: () => null }));
jest.mock("../ArticlePlanWorkspace", () => ({ ArticlePlanWorkspace: () => "WORKSPACE" }));
jest.mock("../ArticlePlanIssueList", () => ({ ArticlePlanIssueList: () => "ISSUE_LIST" }));
import Page from "../page";

const render = async (issue?: string) =>
  renderToStaticMarkup(
    await Page({ params: Promise.resolve({ id: "7" }), searchParams: Promise.resolve({ issue }) }),
  );

describe("記事計画ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.getProject.mockResolvedValue({ id: 7, name: "P", githubRepository: "o/r" });
    api.listArticlePlanSessions.mockResolvedValue([]);
    api.listArticlePlanIssues.mockResolvedValue([]);
    api.getArticlePlanSessionByIssue.mockResolvedValue(null);
    api.getArticlePlanIssueDescription.mockResolvedValue(null);
    api.getMyProfile.mockResolvedValue(null);
  });
  afterEach(() => errorSpy.mockRestore());

  it("プロジェクト取得が404のときは notFound()", async () => {
    api.getProject.mockRejectedValue(new Error("APIエラー (404): Not Found"));
    await expect(render()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("プロジェクト取得が404以外で失敗したときは notFound() ではなく通知を出しログに残す", async () => {
    api.getProject.mockRejectedValue(DOWN);
    const html = await render();
    expect(notFound).not.toHaveBeenCalled();
    expect(html).toContain('role="alert"');
    expect(html).toContain("プロジェクト情報を取得できませんでした");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("セッションの一覧の取得失敗は通知し、ワークスペースは描画しない(空のセッション一覧に見せない)", async () => {
    api.listArticlePlanSessions.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("記事計画セッション一覧を取得できませんでした");
    expect(html).not.toContain("WORKSPACE");
  });

  it("Issue一覧の取得失敗は通知し、Issue一覧コンポーネントは描画しない", async () => {
    api.listArticlePlanIssues.mockRejectedValue(DOWN);
    const html = await render();
    expect(html).toContain("記事計画Issue一覧を取得できませんでした");
    expect(html).not.toContain("ISSUE_LIST");
  });

  it("Issue指定時の詳細/セッションの取得失敗も通知する", async () => {
    api.getArticlePlanSessionByIssue.mockRejectedValue(DOWN);
    api.getArticlePlanIssueDescription.mockRejectedValue(DOWN);
    const html = await render("3");
    expect(html).toContain("Issueのセッションを取得できませんでした");
    expect(html).toContain("Issueの構成案を取得できませんでした");
  });

  it("Issue指定時に一覧(all)の取得が失敗しても通知する", async () => {
    api.listArticlePlanIssues.mockImplementation((_id: number, state: string) =>
      state === "all" ? Promise.reject(DOWN) : Promise.resolve([]),
    );
    const html = await render("3");
    expect(html).toContain("記事計画Issue一覧(全件)を取得できませんでした");
  });

  it("すべて成功して0件のときは通知なしで描画する", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("WORKSPACE");
    expect(html).toContain("ISSUE_LIST");
  });

  it("GitHub未紐付けのプロジェクトでは Issue 取得を行わず通知も出さない", async () => {
    api.getProject.mockResolvedValue({ id: 7, name: "P", githubRepository: null });
    const html = await render();
    expect(api.listArticlePlanIssues).not.toHaveBeenCalled();
    expect(html).not.toContain('role="alert"');
  });

  it("セッション切れは /login へ", async () => {
    api.getProject.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
