import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArticleReviewButton } from "../ArticleReviewButton";

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installReviewBackend, type ReviewBackend } from "./articleReviewBackend";

const REVIEW = "POST /api/projects/7/article-review/pull-requests/201/review";
let backend: ReviewBackend;

describe("ArticleReviewButton(issue #1345)", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    backend = installReviewBackend();
  });

  it("「レビュー」ボタンを表示し、押すとプロジェクトとPR番号でActionを呼ぶ", async () => {
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    await waitFor(() => expect(backend.requests).toEqual([REVIEW]));
    await screen.findByRole("link");
  });

  it("処理中は進行中の表示になりボタンは押せず、APIは1回しか呼ばれない", async () => {
    let resolve: (v: Response) => void = () => {};
    backend.start = () => new Promise<Response>((r) => (resolve = r));
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));
    const busy = await screen.findByRole("button", { name: /テスト環境へ投稿しています/ });
    expect(busy).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("テスト環境へ投稿しています");
    await userEvent.click(busy);
    expect(backend.requests).toEqual([REVIEW]);

    resolve(jsonResponse({ testPostUrl: "https://t.example/a/" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "レビュー" })).toBeEnabled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("成功したら投稿URLを新しいタブで開くリンクとして表示する", async () => {
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    const link = await screen.findByRole("link", { name: /https:\/\/t\.example\/a\// });
    expect(link).toHaveAttribute("href", "https://t.example/a/");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each([
    [404, "提出されていないPRです"],
    [409, "テスト環境のサイトが紐づいていません"],
  ])("失敗(%s)したらサーバの理由を表示し、URLは表示しない", async (status, reason) => {
    const message = `APIエラー (${status}): ${reason}`;
    backend.start = () => textResponse(reason, status);
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("失敗の後に再度押して成功すると、エラー表示は消えてURLが出る", async () => {
    backend.start = () => textResponse("x", 409);
    render(<ArticleReviewButton projectId={7} prNumber={201} />);

    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));
    await screen.findByRole("alert");
    backend.start = () => jsonResponse({ testPostUrl: "https://t.example/b/" });
    await userEvent.click(screen.getByRole("button", { name: "レビュー" }));

    expect(await screen.findByRole("link")).toHaveAttribute("href", "https://t.example/b/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
