/**
 * @jest-environment node
 */
import { NextRequest, NextResponse } from "next/server";
import { GET } from "../start/route";
import { getProjectGoogleAnalyticsStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * Google Analytics 連携の起点(issue #1231)。Google の同意画面へのリダイレクトURLが
 * `analytics.readonly` だけを要求し(受け入れ基準1・要件8)、`state` を HttpOnly cookie にも
 * 保存することを確かめる。同意画面は受け入れテストでスタブ化できないため単体テストが担当する。
 */
jest.mock("@/lib/apiClient", () => ({
  getProjectGoogleAnalyticsStatus: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockStatus = getProjectGoogleAnalyticsStatus as jest.MockedFunction<
  typeof getProjectGoogleAnalyticsStatus
>;

function start(query: string): NextRequest {
  return new NextRequest(`https://localhost/connect/google-analytics/start${query}`);
}

describe("GET /connect/google-analytics/start", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    (requireAdminSession as jest.Mock).mockResolvedValue({});
    mockStatus.mockResolvedValue({
      configured: false,
      propertyId: null,
      clientId: "gaclient.apps.googleusercontent.com",
      hasClientSecret: true,
      connected: false,
    });
  });

  it("スコープはanalytics.readonlyのみで、offline/consentを指定してGoogleへリダイレクトする", async () => {
    const response = await GET(start("?projectId=42"));

    const location = new URL(response.headers.get("location") ?? "");
    expect(location.origin + location.pathname).toBe("https://accounts.google.com/o/oauth2/v2/auth");
    expect(location.searchParams.get("scope")).toBe("https://www.googleapis.com/auth/analytics.readonly");
    expect(location.searchParams.get("access_type")).toBe("offline");
    expect(location.searchParams.get("prompt")).toBe("consent");
    expect(location.searchParams.get("response_type")).toBe("code");
    expect(location.searchParams.get("client_id")).toBe("gaclient.apps.googleusercontent.com");
    expect(location.searchParams.get("redirect_uri")).toBe(
      "https://localhost/connect/google-analytics/callback"
    );
  });

  it("stateは{projectId}.{nonce}で、同じ値をHttpOnly cookieに保存する", async () => {
    const response = await GET(start("?projectId=42"));

    const state = new URL(response.headers.get("location") ?? "").searchParams.get("state") ?? "";
    expect(state).toMatch(/^42\.[0-9a-f-]{36}$/);
    const cookie = (response as NextResponse).cookies.get("google_analytics_oauth_state");
    expect(cookie?.value).toBe(state);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.path).toBe("/connect/google-analytics");
  });

  it("projectIdが無い/数値でなければ400", async () => {
    expect((await GET(start(""))).status).toBe(400);
    expect((await GET(start("?projectId=abc"))).status).toBe(400);
    expect(mockStatus).not.toHaveBeenCalled();
  });

  it("OAuthクライアントIDが未保存なら500でGoogleへ飛ばさない", async () => {
    mockStatus.mockResolvedValue({
      configured: false,
      propertyId: null,
      clientId: null,
      hasClientSecret: true,
      connected: false,
    });
    expect((await GET(start("?projectId=42"))).status).toBe(500);
  });

  it("OAuthクライアントシークレットが未保存なら500でGoogleへ飛ばさない", async () => {
    mockStatus.mockResolvedValue({
      configured: false,
      propertyId: null,
      clientId: "cid",
      hasClientSecret: false,
      connected: false,
    });
    expect((await GET(start("?projectId=42"))).status).toBe(500);
  });

  it("管理者セッションを要求する", async () => {
    await GET(start("?projectId=42"));
    expect(requireAdminSession).toHaveBeenCalled();
  });
});
