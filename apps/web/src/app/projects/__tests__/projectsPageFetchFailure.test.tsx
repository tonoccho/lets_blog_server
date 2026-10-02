/**
 * @jest-environment node
 */

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
jest.mock("next/link", () => ({ __esModule: true, default: ({ children }: { children: unknown }) => children }));
import { renderToStaticMarkup } from "react-dom/server";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue(null),
}));
const listProjects = jest.fn();
jest.mock("@/lib/apiClient", () => ({ listProjects: (...a: unknown[]) => listProjects(...a) }));
jest.mock("../ProjectForm", () => ({ ProjectForm: () => "PROJECT_FORM" }));
jest.mock("@/components/ViewerDateTime", () => ({ ViewerDateTime: () => "DATETIME" }));
import ProjectsPage from "../page";

describe("プロジェクト一覧ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    listProjects.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、通知とログを出し「0件」「登録済みプロジェクトはありません」を出さない", async () => {
    listProjects.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("プロジェクト一覧を取得できませんでした");
    expect(html).not.toContain("全0件");
    expect(html).not.toContain("登録済みプロジェクトはありません");
    expect(html).toContain("PROJECT_FORM");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしで「登録済みプロジェクトはありません」を出す", async () => {
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("全0件を表示");
    expect(html).toContain("登録済みプロジェクトはありません");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("成功して1件以上のときは行を表示する", async () => {
    listProjects.mockResolvedValue([{ id: 1, name: "案件A", slug: "a", createdAt: "2026-01-01T00:00:00Z" }]);
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain("全1件を表示");
    expect(html).toContain("案件A");
  });

  it("セッション切れは /login へ", async () => {
    listProjects.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(ProjectsPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
