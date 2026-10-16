/**
 * @jest-environment node
 */

/**
 * issue #1345: 記事レビュー画面の「レビュー」ボタンの Server Action。
 * admin 限定で、成功なら確認先 URL を、失敗ならサーバが返した理由を値として返す。
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installReviewBackend, type ReviewBackend } from "./articleReviewBackend";
import { approveArticleReviewAction, rejectArticleReviewAction, startArticleReviewAction } from "../actions";

let backend: ReviewBackend;
beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installReviewBackend();
});

const NOT_ADMIN = { user: { id: "u-1", role: "user" } };

describe("startArticleReviewAction(issue #1345)", () => {
  it("管理者を確認してからレビューAPIを呼び、テスト環境の投稿URLを返す", async () => {
    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ testPostUrl: "https://t.example/a/" });
    expect(backend.requests).toEqual(["POST /api/projects/7/article-review/pull-requests/201/review"]);
  });

  it("API失敗はサーバが返した理由を error として返し、URLは返さない", async () => {
    backend.start = () => textResponse("提出されていません", 404);
    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ error: "APIエラー (404): 提出されていません" });
  });

  it("Error 以外が投げられても文字列化して error に入れる", async () => {
    global.fetch = jest.fn().mockRejectedValue("boom") as unknown as typeof fetch;
    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ error: "boom" });
  });

  it("管理者でないときはレビューAPIへ到達しない", async () => {
    resetServerRuntime(NOT_ADMIN);
    await expect(startArticleReviewAction(7, 201)).rejects.toThrow("NEXT_REDIRECT:/");
    expect(backend.requests).toEqual([]);
  });
});

describe("approveArticleReviewAction(issue #1346)", () => {
  it("管理者を確認してからレビュー完了APIを呼び、本番の投稿URLとブランチ削除の成否を返す", async () => {
    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({
      productionPostUrl: "https://prod.example/a/",
      branchDeleted: true,
    });
    expect(backend.requests).toEqual(["POST /api/projects/7/article-review/pull-requests/201/approve"]);
  });

  it("ブランチ削除に失敗しても(branchDeleted=false)成功として返す", async () => {
    backend.approve = () => jsonResponse({ productionPostUrl: "https://prod.example/a/", branchDeleted: false });
    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({
      productionPostUrl: "https://prod.example/a/",
      branchDeleted: false,
    });
  });

  it("API失敗はサーバが返した理由を error として返し、URLは返さない", async () => {
    backend.approve = () => textResponse("レビュー中ではありません", 409);
    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({ error: "APIエラー (409): レビュー中ではありません" });
  });

  it("Error 以外が投げられても文字列化して error に入れる", async () => {
    global.fetch = jest.fn().mockRejectedValue("boom") as unknown as typeof fetch;
    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({ error: "boom" });
  });

  it("管理者でないときはAPIへ到達しない", async () => {
    resetServerRuntime(NOT_ADMIN);
    await expect(approveArticleReviewAction(7, 201)).rejects.toThrow("NEXT_REDIRECT:/");
    expect(backend.requests).toEqual([]);
  });
});

describe("rejectArticleReviewAction(issue #1346)", () => {
  it("管理者を確認してから差し戻しAPIを指摘事項つき(サーバの入力名は comment)で呼び、rejected を返す", async () => {
    await expect(rejectArticleReviewAction(7, 201, "見出しを直してください")).resolves.toEqual({ rejected: true });
    expect(backend.requests).toEqual(["POST /api/projects/7/article-review/pull-requests/201/reject"]);
    expect(backend.rejectBodies).toEqual([{ comment: "見出しを直してください" }]);
  });

  it.each([[""], ["   "], ["\n\t "]])("指摘事項が空白だけ(%j)ならAPIを呼ばず入力が必要な旨を error で返す", async (feedback) => {
    const result = await rejectArticleReviewAction(7, 201, feedback);
    expect(result).toEqual({ error: expect.stringContaining("指摘事項") });
    expect(backend.requests).toEqual([]);
  });

  it("API失敗はサーバが返した理由を error として返し、rejected は返さない", async () => {
    backend.reject = () => textResponse("レビュー中ではありません", 409);
    await expect(rejectArticleReviewAction(7, 201, "直して")).resolves.toEqual({ error: "APIエラー (409): レビュー中ではありません" });
  });

  it("Error 以外が投げられても文字列化して error に入れる", async () => {
    global.fetch = jest.fn().mockRejectedValue("boom") as unknown as typeof fetch;
    await expect(rejectArticleReviewAction(7, 201, "直して")).resolves.toEqual({ error: "boom" });
  });

  it("管理者でないときはAPIへ到達しない", async () => {
    resetServerRuntime(NOT_ADMIN);
    await expect(rejectArticleReviewAction(7, 201, "直して")).rejects.toThrow("NEXT_REDIRECT:/");
    expect(backend.requests).toEqual([]);
  });
});
