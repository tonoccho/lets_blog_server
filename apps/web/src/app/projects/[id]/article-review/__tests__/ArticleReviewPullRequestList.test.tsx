import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArticleReviewPullRequestList } from "../ArticleReviewPullRequestList";

const approveArticleReviewAction = jest.fn();
jest.mock("../actions", () => ({
  startArticleReviewAction: jest.fn(),
  approveArticleReviewAction: (...args: unknown[]) => approveArticleReviewAction(...args),
  rejectArticleReviewAction: jest.fn(),
}));
jest.mock("next/navigation", () => ({ useRouter: () => ({ refresh: jest.fn() }) }));

const pullRequests = [
  {
    number: 201,
    title: "記事サンプル",
    headBranch: "article/e2e-sample",
    createdAt: "2026-09-30T03:00:00Z",
    url: "https://github.com/acme/blog/pull/201",
  },
  {
    number: 205,
    title: "別の記事",
    headBranch: "article/other",
    createdAt: "2026-09-29T15:30:00Z",
    url: "https://github.com/acme/blog/pull/205",
  },
];

describe("ArticleReviewPullRequestList(issue #1340)", () => {
  it("各PRを番号・タイトル・ブランチ名・作成日時付きの行で表示する", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    const row = screen.getByRole("row", { name: /#201/ });
    expect(within(row).getByText("記事サンプル")).toBeInTheDocument();
    expect(within(row).getByText("article/e2e-sample")).toBeInTheDocument();
    // 2026-09-30T03:00:00Z は Asia/Tokyo で 12:00
    expect(within(row).getByText("2026/9/30 12:00:00")).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(3); // ヘッダ + 2 行
  });

  it("作成日時は渡されたタイムゾーンで表示される", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests.slice(0, 1)} timezone="America/New_York" />);

    expect(screen.getByText("2026/9/29 23:00:00")).toBeInTheDocument();
  });

  it("各行にGitHubのPRページへの外部リンクがある", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    const link = within(screen.getByRole("row", { name: /#205/ })).getByRole("link", { name: /GitHub/ });
    expect(link).toHaveAttribute("href", "https://github.com/acme/blog/pull/205");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("開いているPRが無いときは0件であることを表示し、失敗表示にはしない", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={[]} timezone={null} />);

    expect(screen.getByText("レビュー待ちの Pull Request はありません")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("各行に「レビュー」ボタンがある(issue #1345)", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    expect(screen.getAllByRole("button", { name: "レビュー" })).toHaveLength(2);
    expect(within(screen.getByRole("row", { name: /#205/ })).getByRole("button", { name: "レビュー" })).toBeInTheDocument();
  });

  it("各行に「レビュー完了」「記事差し戻し」の操作と指摘事項の入力欄がある(issue #1346)", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    expect(screen.getAllByRole("button", { name: "レビュー完了" })).toHaveLength(2);
    const row = screen.getByRole("row", { name: /#205/ });
    expect(within(row).getByRole("button", { name: "記事差し戻し" })).toBeInTheDocument();
    expect(within(row).getByRole("textbox", { name: "指摘事項" })).toBeInTheDocument();
  });

  it("レビュー完了後に一覧からPRが消えても(取り直し)、その行は成功の結果つきで残る(issue #1346)", async () => {
    approveArticleReviewAction.mockResolvedValue({ productionPostUrl: "https://prod.example/a/", branchDeleted: true });
    const { rerender } = render(
      <ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />
    );

    const row = screen.getByRole("row", { name: /#201/ });
    await userEvent.click(within(row).getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(within(row).getByRole("button", { name: "実行する" }));
    await within(row).findByRole("link", { name: /prod\.example/ });

    // router.refresh() 後のサーバ描画: マージされたPR #201 は開いているPRの一覧から消えている
    rerender(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests.slice(1)} timezone="Asia/Tokyo" />);

    const kept = screen.getByRole("row", { name: /#201/ });
    expect(within(kept).getByRole("link", { name: /prod\.example/ })).toHaveAttribute("href", "https://prod.example/a/");
    expect(screen.getByRole("row", { name: /#205/ })).toBeInTheDocument();
    expect(screen.queryByText("レビュー待ちの Pull Request はありません")).not.toBeInTheDocument();
  });

  it("操作していないPRは、取り直しで一覧から消えたらそのまま消える(issue #1346)", () => {
    const { rerender } = render(
      <ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />
    );

    rerender(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests.slice(1)} timezone="Asia/Tokyo" />);

    expect(screen.queryByRole("row", { name: /#201/ })).not.toBeInTheDocument();
  });
});
