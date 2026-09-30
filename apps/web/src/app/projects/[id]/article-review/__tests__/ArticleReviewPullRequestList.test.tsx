import { render, screen, within } from "@testing-library/react";
import { ArticleReviewPullRequestList } from "../ArticleReviewPullRequestList";

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
    render(<ArticleReviewPullRequestList pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    const row = screen.getByRole("row", { name: /#201/ });
    expect(within(row).getByText("記事サンプル")).toBeInTheDocument();
    expect(within(row).getByText("article/e2e-sample")).toBeInTheDocument();
    // 2026-09-30T03:00:00Z は Asia/Tokyo で 12:00
    expect(within(row).getByText("2026/9/30 12:00:00")).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(3); // ヘッダ + 2 行
  });

  it("作成日時は渡されたタイムゾーンで表示される", () => {
    render(<ArticleReviewPullRequestList pullRequests={pullRequests.slice(0, 1)} timezone="America/New_York" />);

    expect(screen.getByText("2026/9/29 23:00:00")).toBeInTheDocument();
  });

  it("各行にGitHubのPRページへの外部リンクがある", () => {
    render(<ArticleReviewPullRequestList pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    const link = within(screen.getByRole("row", { name: /#205/ })).getByRole("link", { name: /GitHub/ });
    expect(link).toHaveAttribute("href", "https://github.com/acme/blog/pull/205");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("開いているPRが無いときは0件であることを表示し、失敗表示にはしない", () => {
    render(<ArticleReviewPullRequestList pullRequests={[]} timezone={null} />);

    expect(screen.getByText("レビュー待ちの Pull Request はありません")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
