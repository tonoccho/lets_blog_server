import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArticleReviewPullRequestList } from "../ArticleReviewPullRequestList";

/**
 * 自前の Server Action・各行の部品・ViewerDateTime は差し替えない。差し替えるのは
 * Next.js の実行基盤(`next/navigation` の `useRouter`)と外部との通信(fetch)だけ(#1835)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const refresh = jest.fn();
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  useRouter: () => ({ refresh }),
}));
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installReviewBackend } from "./articleReviewBackend";

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  installReviewBackend();
});

const pullRequests = [
  {
    number: 201,
    title: "記事サンプル",
    headBranch: "article/e2e-sample",
    createdAt: "2026-09-30T03:00:00Z",
    url: "https://github.com/acme/blog/pull/201",
    state: "IN_REVIEW" as const,
  },
  {
    number: 205,
    title: "別の記事",
    headBranch: "article/other",
    createdAt: "2026-09-29T15:30:00Z",
    url: "https://github.com/acme/blog/pull/205",
    state: null,
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

  it.each([
    [null, "未提出"],
    ["SUBMITTED", "提出済み"],
    ["IN_REVIEW", "レビュー中"],
    ["CHANGES_REQUESTED", "差し戻し"],
    ["PUBLISHED", "公開済み"],
  ] as const)("状態 %s の行は「状態」列に「%s」と表示される(issue #1677)", (state, label) => {
    render(
      <ArticleReviewPullRequestList
        projectId={7}
        pullRequests={[{ ...pullRequests[0], state }]}
        timezone="Asia/Tokyo"
      />
    );

    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    const index = headers.indexOf("状態");
    expect(index).toBeGreaterThanOrEqual(0);
    const cell = within(screen.getByRole("row", { name: /#201/ })).getAllByRole("cell")[index];
    expect(cell).toHaveTextContent(label);
  });

  it("行ごとにその行の状態が表示される(issue #1677)", () => {
    render(<ArticleReviewPullRequestList projectId={7} pullRequests={pullRequests} timezone="Asia/Tokyo" />);

    const index = screen.getAllByRole("columnheader").map((h) => h.textContent).indexOf("状態");
    expect(within(screen.getByRole("row", { name: /#201/ })).getAllByRole("cell")[index]).toHaveTextContent("レビュー中");
    expect(within(screen.getByRole("row", { name: /#205/ })).getAllByRole("cell")[index]).toHaveTextContent("未提出");
  });
});
