/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・PostsTable・next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import PostsPage from "../page";

const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** project-service(投稿履歴)と identity-service の代役(gateway の向こう側)。拒否は 401(セッション切れ)か 503 の応答になる。 */
const listPosts = jest.fn();
const getMyProfile = jest.fn();
function respond(backend: jest.Mock) {
  return async () => {
    try {
      return jsonResponse(await backend());
    } catch (err) {
      if (err instanceof Error && err.message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
      return textResponse("Service Unavailable", 503);
    }
  };
}

describe("投稿履歴ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    listPosts.mockReset();
    getMyProfile.mockReset();
    resetServerRuntime({ user: { role: "user" } });
    installFetch({
      "GET /api/posts": respond(listPosts),
      "GET /api/identity/me": respond(getMyProfile),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getMyProfile.mockResolvedValue(null);
  });
  afterEach(() => errorSpy.mockRestore());

  it("失敗したとき、通知を出し「投稿履歴はまだありません」「全0件」を出さない", async () => {
    listPosts.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await PostsPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("投稿履歴を取得できませんでした");
    expect(html).not.toContain("投稿履歴はまだありません");
    expect(html).not.toContain("全0件");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通りの空表示で通知なし", async () => {
    listPosts.mockResolvedValue([]);
    const html = renderToStaticMarkup(await PostsPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("投稿履歴はまだありません");
    expect(html).toContain("全0件");
  });

  it("セッション切れは /login へ", async () => {
    listPosts.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(PostsPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
