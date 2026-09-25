/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import { completeProjectAdSenseOAuth } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * AdSense OAuth コールバックの `state` 照合(issue #939 / AT-13、受け入れ基準6の前半)。
 *
 * ## なぜ受け入れシナリオではなく単体テストなのか
 *
 * `state` の照合は Next.js の Route Handler にあり、**ログイン済みのブラウザセッションと
 * `/connect/adsense/start` が発行した HttpOnly cookie の両方**が揃って初めて到達する。
 * その cookie を得るには `/connect/adsense/start` を踏む必要があり、そこは
 * accounts.google.com へリダイレクトする — つまり実 Google の同意画面である。
 * 同意画面は #928 のスタブ化の対象外(置き換えたのはトークン交換とレポートAPIだけ)なので、
 * ブラウザ経路でこの分岐へ入る手段が無い。
 *
 * 受け入れシナリオ側(`e2e/features/analytics/credentials.feature`)は、
 * **不正な認可コードが拒否されること**と**未認証のコールバックが認可コードを処理しないこと**を
 * 見る。cookie と `state` の不一致だけをここが引き受ける
 * (docs/ACCEPTANCE_TESTING.md §4 の `@api` 例外と同じ考え方)。
 */
jest.mock("@/lib/apiClient", () => ({
  completeProjectAdSenseOAuth: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockComplete = completeProjectAdSenseOAuth as jest.MockedFunction<
  typeof completeProjectAdSenseOAuth
>;
const mockRequireAdminSession = requireAdminSession as jest.MockedFunction<
  typeof requireAdminSession
>;

const STATE_COOKIE = "adsense_oauth_state";
const PROJECT_ID = 42;
const VALID_STATE = `${PROJECT_ID}.11111111-2222-3333-4444-555555555555`;

function callback(params: Record<string, string>, cookieState?: string): NextRequest {
  const url = new URL("https://localhost/connect/adsense/callback");
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

describe("GET /connect/adsense/callback", () => {
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
      redirectUri: "https://localhost/connect/adsense/callback",
    });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/adsense`);
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
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/adsense`);
    expect(target.searchParams.get("error")).toBe("access_denied");
  });

  it("トークン交換が失敗したときは、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("APIエラー (502)"));

    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/adsense`);
    expect(target.searchParams.get("error")).toBe("APIエラー (502)");
  });

  it("state照合の前に管理者セッションを要求する", async () => {
    await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    expect(mockRequireAdminSession).toHaveBeenCalled();
  });
});
