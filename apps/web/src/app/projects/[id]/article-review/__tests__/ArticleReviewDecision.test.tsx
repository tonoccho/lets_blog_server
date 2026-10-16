import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArticleReviewDecision } from "../ArticleReviewDecision";

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
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
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installReviewBackend, type ReviewBackend } from "./articleReviewBackend";

const APPROVE = "POST /api/projects/7/article-review/pull-requests/201/approve";
const REJECT = "POST /api/projects/7/article-review/pull-requests/201/reject";
let backend: ReviewBackend;

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installReviewBackend();
});

function renderDecision(onSucceeded = jest.fn()) {
  render(<ArticleReviewDecision projectId={7} prNumber={201} onSucceeded={onSucceeded} />);
  return onSucceeded;
}

describe("ArticleReviewDecision: レビュー完了(issue #1346)", () => {

  it("「レビュー完了」を押しただけではAPIを呼ばず、確認(front matterのstatusに従う旨・マージとブランチ削除)を出す", async () => {
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));

    const confirm = screen.getByRole("group", { name: "レビュー完了の確認" });
    expect(confirm).toHaveTextContent("本番環境へ登録し、Pull Request をマージしてブランチを削除します");
    expect(confirm).toHaveTextContent("front matter");
    expect(confirm).toHaveTextContent("status");
    expect(backend.requests).toEqual([]);
  });

  it("確認で取りやめるとAPIは呼ばれず、確認は閉じて元の操作に戻る", async () => {
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "取りやめる" }));

    expect(backend.requests).toEqual([]);
    expect(screen.queryByRole("group", { name: "レビュー完了の確認" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "レビュー完了" })).toBeEnabled();
  });

  it("確認で実行を選ぶとプロジェクトとPR番号でActionを呼び、本番の投稿URLを新しいタブで開くリンクで出し、一覧を取り直す", async () => {
    const onSucceeded = renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    const link = await screen.findByRole("link", { name: /https:\/\/prod\.example\/a\// });
    expect(backend.requests).toEqual([APPROVE]);
    expect(link).toHaveAttribute("href", "https://prod.example/a/");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByRole("status")).toHaveTextContent("レビューを完了しました");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(onSucceeded).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
    // 完了後は取り消せないので操作は出さない
    expect(screen.queryByRole("button", { name: "レビュー完了" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "記事差し戻し" })).not.toBeInTheDocument();
  });

  it("ブランチの削除に失敗(branchDeleted=false)しても成功として扱い、削除失敗を補足する", async () => {
    backend.approve = () => jsonResponse({ productionPostUrl: "https://prod.example/a/", branchDeleted: false });
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("link", { name: /prod\.example/ })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("ブランチの削除に失敗");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("処理中は進行中の表示になりボタンは押せず、APIは1回しか呼ばれない", async () => {
    let resolve: (v: Response) => void = () => {};
    backend.approve = () => new Promise<Response>((r) => (resolve = r));
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("button", { name: /本番環境へ登録しています/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "記事差し戻し" })).toBeDisabled();
    expect(backend.requests).toEqual([APPROVE]);

    resolve(jsonResponse({ productionPostUrl: "https://prod.example/a/", branchDeleted: true }));
    await screen.findByRole("link");
  });

  it("失敗したらサーバの理由を表示し、本番の投稿URLも完了の表示も出さず、一覧は取り直さない。再度の操作もできる", async () => {
    backend.approve = () => textResponse("レビュー中ではありません", 409);
    const onSucceeded = renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (409): レビュー中ではありません");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(onSucceeded).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "レビュー完了" })).toBeEnabled();
  });

  it("失敗の後にやり直して成功すると、エラー表示は消える", async () => {
    backend.approve = () => textResponse("x", 409);
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));
    await screen.findByRole("alert");
    backend.approve = () => jsonResponse({ productionPostUrl: "https://prod.example/b/", branchDeleted: true });
    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("link")).toHaveAttribute("href", "https://prod.example/b/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("ArticleReviewDecision: 記事差し戻し(issue #1346)", () => {

  it("指摘事項を入力して「記事差し戻し」を押すと指摘事項つきでActionを呼び、差し戻したことを表示して一覧を取り直す", async () => {
    const onSucceeded = renderDecision();

    await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), "見出しの階層を直してください");
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    expect(await screen.findByRole("status")).toHaveTextContent("記事を差し戻しました");
    expect(backend.requests).toEqual([REJECT]);
    expect(backend.rejectBodies).toEqual([{ comment: "見出しの階層を直してください" }]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(onSucceeded).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "記事差し戻し" })).not.toBeInTheDocument();
  });

  it.each([[""], ["   "]])("指摘事項が空(%j)のまま押すと入力が必要な旨を出し、Actionは呼ばない", async (text) => {
    renderDecision();

    if (text) {
      await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), text);
    }
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    expect(screen.getByRole("alert")).toHaveTextContent("指摘事項を入力してください");
    expect(backend.requests).toEqual([]);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("入力が必要な旨の表示は、入力し直して成功すると消える", async () => {
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));
    await screen.findByRole("alert");
    await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), "直して");
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    await screen.findByRole("status");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("失敗したらサーバの理由を表示し、差し戻したことは表示せず、入力した指摘事項は残る", async () => {
    backend.reject = () => textResponse("レビュー中ではありません", 409);
    const onSucceeded = renderDecision();

    await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), "直して");
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (409): レビュー中ではありません");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "指摘事項" })).toHaveValue("直して");
    expect(onSucceeded).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("処理中は差し戻しもレビュー完了も押せず、Actionは1回しか呼ばれない", async () => {
    let resolve: (v: Response) => void = () => {};
    backend.reject = () => new Promise<Response>((r) => (resolve = r));
    renderDecision();

    await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), "直して");
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    await waitFor(() => expect(screen.getByRole("button", { name: /差し戻しています/ })).toBeDisabled());
    expect(screen.getByRole("button", { name: "レビュー完了" })).toBeDisabled();
    expect(backend.requests).toEqual([REJECT]);

    resolve(jsonResponse({}));
    await screen.findByRole("status");
  });
});

describe("ArticleReviewDecision: サーバの応答が想定の形でないとき", () => {
  it("レビュー完了の応答に本番の投稿URLが無ければ、その旨を出し、完了としては扱わない", async () => {
    backend.approve = () => jsonResponse({});
    const onSucceeded = renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("本番の投稿 URL が返りませんでした");
    expect(onSucceeded).not.toHaveBeenCalled();
  });

  it("レビュー完了の応答に branchDeleted が無ければ、ブランチは削除できたものとして扱う", async () => {
    backend.approve = () => jsonResponse({ productionPostUrl: "https://prod.example/a/" });
    renderDecision();

    await userEvent.click(screen.getByRole("button", { name: "レビュー完了" }));
    await userEvent.click(screen.getByRole("button", { name: "実行する" }));

    expect(await screen.findByRole("status")).not.toHaveTextContent("ブランチの削除に失敗");
  });

  it("差し戻しがサーバに拒否されたときは、成功としては扱わない", async () => {
    backend.reject = () => textResponse("", 409);
    const onSucceeded = renderDecision();

    await userEvent.type(screen.getByRole("textbox", { name: "指摘事項" }), "直して");
    await userEvent.click(screen.getByRole("button", { name: "記事差し戻し" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (409)");
    expect(onSucceeded).not.toHaveBeenCalled();
  });
});
