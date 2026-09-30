import { render, screen } from "@testing-library/react";

const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({ notFound: () => notFound() }));

const getProject = jest.fn();
const listArticleReviewPullRequests = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  getProject: (...args: unknown[]) => getProject(...args),
  listArticleReviewPullRequests: (...args: unknown[]) => listArticleReviewPullRequests(...args),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue("Asia/Tokyo"),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => <nav /> }));
jest.mock("../../ProjectSectionNav", () => ({ ProjectSectionNav: () => null }));

import ArticleReviewPage from "../page";

const project = (githubRepository: string | null) => ({
  id: 7,
  name: "サンプル案件",
  githubRepository,
});

const render_ = async () => render(await ArticleReviewPage({ params: Promise.resolve({ id: "7" }) }));

describe("記事レビュー画面 page.tsx(issue #1340)", () => {
  beforeEach(() => {
    notFound.mockClear();
    getProject.mockReset();
    listArticleReviewPullRequests.mockReset();
    jest.spyOn(console, "error").mockImplementation(() => undefined);
  });

  it("リポジトリ設定済みならPR一覧を表示する", async () => {
    getProject.mockResolvedValue(project("acme/blog"));
    listArticleReviewPullRequests.mockResolvedValue([
      { number: 201, title: "記事サンプル", headBranch: "article/x", createdAt: "2026-09-30T03:00:00Z", url: "https://github.com/acme/blog/pull/201" },
    ]);
    await render_();

    expect(listArticleReviewPullRequests).toHaveBeenCalledWith(7);
    expect(screen.getByText("記事サンプル")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("リポジトリ未設定なら未設定の旨と設定先リンクを出し、APIは呼ばず一覧も出さない", async () => {
    getProject.mockResolvedValue(project(null));
    await render_();

    expect(screen.getByText(/GitHub リポジトリが未設定/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /プロジェクト設定/ })).toHaveAttribute("href", "/projects/7");
    expect(listArticleReviewPullRequests).not.toHaveBeenCalled();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("API失敗時は空一覧ではなく取得失敗のアラートを出す", async () => {
    getProject.mockResolvedValue(project("acme/blog"));
    listArticleReviewPullRequests.mockRejectedValue(new Error("502"));
    await render_();

    expect(screen.getByRole("alert")).toHaveTextContent("Pull Request の取得に失敗しました");
    expect(screen.queryByText("レビュー待ちの Pull Request はありません")).not.toBeInTheDocument();
  });

  it("プロジェクトが取得できなければ notFound()", async () => {
    getProject.mockRejectedValue(new Error("404"));
    await expect(render_()).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFound).toHaveBeenCalledTimes(1);
  });
});
