/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient・@/lib/loadOrReport・各子コンポーネント・next/link は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installPlanBackend, PROJECT, type PlanBackend } from "./planBackend";
import Page from "../page";

const DOWN = () => textResponse("Service Unavailable", 503);
// 記事計画の各パーツは、描画された中身で見分ける。
const WORKSPACE = "壁打ち一覧";
const ISSUE_LIST = "登録済み記事(Issue)一覧";

let backend: PlanBackend;
const render = async (issue?: string) =>
  renderToStaticMarkup(await Page({ params: Promise.resolve({ id: "7" }), searchParams: Promise.resolve({ issue }) }));

/** `details.reason` つきの失敗(GitHub の設定の不備など)の応答。 */
const reasonFailure = (status: number, error: string, reason?: string) => () =>
  textResponse(JSON.stringify(reason ? { error, details: { reason } } : { error }), status);

describe("記事計画ページの取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    resetServerRuntime(ADMIN_SESSION);
    backend = installPlanBackend();
  });
  afterEach(() => errorSpy.mockRestore());

  it("プロジェクト取得が404のときは notFound()", async () => {
    backend.project = () => textResponse("Not Found", 404);
    await expect(render()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("プロジェクト取得が404以外で失敗したときは notFound() ではなく通知を出しログに残す", async () => {
    backend.project = DOWN;
    const html = await render();
    expect(html).toContain('role="alert"');
    expect(html).toContain("プロジェクト情報を取得できませんでした");
    expect(html).not.toContain(WORKSPACE);
    expect(errorSpy).toHaveBeenCalled();
  });

  it("セッションの一覧の取得失敗は通知し、ワークスペースは描画しない(空のセッション一覧に見せない)", async () => {
    backend.sessions = DOWN;
    const html = await render();
    expect(html).toContain("記事計画セッション一覧を取得できませんでした");
    expect(html).not.toContain(WORKSPACE);
  });

  it("Issue一覧の取得失敗は通知し、Issue一覧コンポーネントは描画しない", async () => {
    backend.issues = DOWN;
    const html = await render();
    expect(html).toContain("記事計画Issue一覧を取得できませんでした");
    expect(html).not.toContain(ISSUE_LIST);
  });

  describe("Issue一覧の取得失敗の原因(issue #1771)", () => {
    it("トークン未設定は原因の通知を出し、汎用文言は出さない", async () => {
      backend.issues = reasonFailure(409, "x", "GITHUB_TOKEN_NOT_CONFIGURED");
      const html = await render();
      expect(html).toContain('role="alert"');
      expect(html).toContain("記事計画Issue一覧を取得できませんでした。GitHub トークンが設定されていません。");
      expect(html).not.toContain("時間をおいて再読み込みしてください");
      expect(html).not.toContain(ISSUE_LIST);
      expect(errorSpy).toHaveBeenCalled();
    });

    it("認証失敗は原因の通知を出し、GitHubの本文は出さない", async () => {
      backend.issues = reasonFailure(502, "Bad credentials", "GITHUB_AUTH_FAILED");
      const html = await render();
      expect(html).toContain("記事計画Issue一覧を取得できませんでした。GitHub の認証に失敗しました。");
      expect(html).not.toContain("時間をおいて再読み込みしてください");
      expect(html).not.toContain("Bad credentials");
    });

    it("原因が無い失敗は汎用文言だけを出す", async () => {
      backend.issues = reasonFailure(502, "API rate limit exceeded");
      const html = await render();
      expect(html).toContain("記事計画Issue一覧を取得できませんでした。時間をおいて再読み込みしてください。");
      expect(html).not.toContain("GitHub トークンが設定されていません");
      expect(html).not.toContain("GitHub の認証に失敗しました");
      expect(html).not.toContain("API rate limit exceeded");
    });

    it("原因の通知と、ほかの項目の汎用通知を並べて出せる", async () => {
      backend.issues = reasonFailure(409, "x", "GITHUB_TOKEN_NOT_CONFIGURED");
      backend.sessions = DOWN;
      const html = await render();
      expect(html).toContain("GitHub トークンが設定されていません");
      expect(html).toContain("記事計画セッション一覧を取得できませんでした。時間をおいて再読み込みしてください。");
    });

    it("全件一覧(?issue=)の失敗は原因があっても汎用通知のまま", async () => {
      backend.issues = (state) =>
        state === "all" ? reasonFailure(409, "x", "GITHUB_TOKEN_NOT_CONFIGURED")() : jsonResponse([]);
      const html = await render("3");
      expect(html).toContain("記事計画Issue一覧(全件)を取得できませんでした。時間をおいて再読み込みしてください。");
      expect(html).not.toContain("GitHub トークンが設定されていません");
    });
  });

  it("Issue指定時の詳細/セッションの取得失敗も通知する", async () => {
    backend.sessionByIssue = DOWN;
    backend.issueDescription = DOWN;
    const html = await render("3");
    expect(html).toContain("Issueのセッションを取得できませんでした");
    expect(html).toContain("Issueの構成案を取得できませんでした");
  });

  it("Issue指定時に一覧(all)の取得が失敗しても通知する", async () => {
    backend.issues = (state) => (state === "all" ? DOWN() : jsonResponse([]));
    const html = await render("3");
    expect(html).toContain("記事計画Issue一覧(全件)を取得できませんでした");
  });

  it("すべて成功して0件のときは通知なしで描画する", async () => {
    const html = await render();
    expect(html).not.toContain('role="alert"');
    expect(html).toContain(WORKSPACE);
    expect(html).toContain(ISSUE_LIST);
  });

  it("GitHub未紐付けのプロジェクトでは Issue 取得を行わず、紐付けの案内を出し、通知は出さない", async () => {
    backend.project = () => jsonResponse({ ...PROJECT, githubRepository: null });
    const html = await render();
    expect(backend.requests.filter((r) => r.includes("issues"))).toEqual([]);
    expect(html).toContain("GitHub リポジトリが紐付けられていません");
    expect(html).not.toContain('role="alert"');
    expect(html).not.toContain(ISSUE_LIST);
  });

  it("セッション切れは /login へ", async () => {
    backend.project = () => textResponse("Unauthorized", 401);
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
