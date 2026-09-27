/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import { completeProjectGoogleAnalyticsOAuth } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * Google Analytics OAuth コールバックの `state` 照合(issue #1231)。
 *
 * AdSense(`connect/adsense/__tests__/callback-route.test.ts`)と同じ理由で、cookie と `state` の不一致は
 * 受け入れシナリオからはブラウザ経路で到達できない(同意画面は accounts.google.com でスタブ化の
 * 対象外)ため、Route Handler の単体テストが担当する。受け入れシナリオ側
 * (`e2e/features/analytics/credentials.feature`)は不正な認可コードの拒否と、
 * 未認証コールバックが認可コードを処理しないことを見る(docs/ACCEPTANCE_TESTING.md §4 の `@api` 例外)。
 */
jest.mock("@/lib/apiClient", () => ({
  completeProjectGoogleAnalyticsOAuth: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockComplete = completeProjectGoogleAnalyticsOAuth as jest.MockedFunction<
  typeof completeProjectGoogleAnalyticsOAuth
>;
const mockRequireAdminSession = requireAdminSession as jest.MockedFunction<
  typeof requireAdminSession
>;

const STATE_COOKIE = "google_analytics_oauth_state";
const PROJECT_ID = 42;
const VALID_STATE = `${PROJECT_ID}.11111111-2222-3333-4444-555555555555`;

function callback(params: Record<string, string>, cookieState?: string): NextRequest {
  const url = new URL("https://localhost/connect/google-analytics/callback");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  const headers: Record<string, string> = {};
  if (cookieState !== undefined) {
    headers.cookie = `${STATE_COOKIE}=${cookieState}`;
  }
  return new NextRequest(url, { headers });
}

/** リダイレクト先のクエリ。どの理由で戻されたかを見る。 */
function redirectTarget(response: Response): URL {
  return new URL(response.headers.get("location") ?? "");
}

describe("GET /connect/google-analytics/callback", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    mockRequireAdminSession.mockResolvedValue({} as never);
    mockComplete.mockResolvedValue(undefined);
  });

  it("stateがcookieと一致すれば認可コードを交換し、連携完了として設定画面へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    expect(mockComplete).toHaveBeenCalledWith(PROJECT_ID, {
      code: "auth-code",
      redirectUri: "https://localhost/connect/google-analytics/callback",
    });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/google-analytics`);
    expect(target.searchParams.get("connected")).toBe("1");
  });

  it("stateがcookieと一致しなければ認可コードを交換しない", async () => {
    const response = await GET(
      callback({ code: "auth-code", state: VALID_STATE }, `${PROJECT_ID}.forged-nonce`)
    );

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).searchParams.get("error")).toBe("invalid_state");
  });

  it("cookieが無ければ認可コードを交換しない", async () => {
    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).searchParams.get("error")).toBe("invalid_state");
  });

  it("stateが無ければ認可コードを交換しない", async () => {
    const response = await GET(callback({ code: "auth-code" }, VALID_STATE));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).searchParams.get("error")).toBe("invalid_state");
  });

  it("認可コードが無ければ交換しない", async () => {
    const response = await GET(callback({ state: VALID_STATE }, VALID_STATE));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).searchParams.get("error")).toBe("invalid_state");
  });

  it("stateにプロジェクトIDが含まれていなければ、プロジェクト一覧へ戻す", async () => {
    const state = "not-a-number.nonce";

    const response = await GET(callback({ code: "auth-code", state }, state));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe("/projects");
    expect(target.searchParams.get("error")).toBe("invalid_state");
  });

  it("Google側がエラーを返したときは、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(
      callback({ error: "access_denied", state: VALID_STATE }, VALID_STATE)
    );

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/google-analytics`);
    expect(target.searchParams.get("error")).toBe("access_denied");
  });

  it("トークン交換が失敗したときは、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("APIエラー (502)"));

    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/google-analytics`);
    expect(target.searchParams.get("error")).toBe("APIエラー (502)");
  });

  it("state照合の前に管理者セッションを要求する", async () => {
    await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    expect(mockRequireAdminSession).toHaveBeenCalled();
  });
});
