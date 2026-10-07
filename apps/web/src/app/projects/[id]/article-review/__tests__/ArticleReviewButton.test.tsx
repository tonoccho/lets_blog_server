import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArticleReviewButton } from "../ArticleReviewButton";

const startArticleReviewAction = jest.fn();
jest.mock("../actions", () => ({
  startArticleReviewAction: (...args: unknown[]) => startArticleReviewAction(...args),
}));

describe("ArticleReviewButton(issue #1345)", () => {
  beforeEach(() => jest.clearAllMocks());

  it("「レビュー」ボタンを表示し、押すとプロジェクトとPR番号でActionを呼ぶ", async () => {
    startArticleReviewAction.mockResolvedValue({ testPostUrl: "https://t.example/a/" });
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    expect(startArticleReviewAction).toHaveBeenCalledWith(7, 201);
  });

  it("処理中は進行中の表示になりボタンは押せず、APIは1回しか呼ばれない", async () => {
    let resolve: (v: { testPostUrl: string }) => void = () => {};
    startArticleReviewAction.mockReturnValue(new Promise((r) => (resolve = r)));
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));
    const busy = await screen.findByRole("button", { name: /テスト環境へ投稿しています/ });
    expect(busy).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("テスト環境へ投稿しています");
    await userEvent.click(busy);
    expect(startArticleReviewAction).toHaveBeenCalledTimes(1);

    resolve({ testPostUrl: "https://t.example/a/" });
    await waitFor(() => expect(screen.getByRole("button", { name: "レビュー" })).toBeEnabled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("成功したら投稿URLを新しいタブで開くリンクとして表示する", async () => {
    startArticleReviewAction.mockResolvedValue({ testPostUrl: "https://t.example/a/" });
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    const link = await screen.findByRole("link", { name: /https:\/\/t\.example\/a\// });
    expect(link).toHaveAttribute("href", "https://t.example/a/");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each([
    ["404", "APIエラー (404): 提出されていないPRです"],
    ["409", "APIエラー (409): テスト環境のサイトが紐づいていません"],
  ])("失敗(%s)したらサーバの理由を表示し、URLは表示しない", async (_status, message) => {
    startArticleReviewAction.mockResolvedValue({ error: message });
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("失敗の後に再度押して成功すると、エラー表示は消えてURLが出る", async () => {
    startArticleReviewAction
      .mockResolvedValueOnce({ error: "APIエラー (409): x" })
      .mockResolvedValueOnce({ testPostUrl: "https://t.example/b/" });
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    expect(await screen.findByRole("link")).toHaveAttribute("href", "https://t.example/b/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
