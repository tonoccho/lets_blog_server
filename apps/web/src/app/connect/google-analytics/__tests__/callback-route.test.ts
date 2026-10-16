/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import {
  installFetch,
  jsonResponse,
  resetServerRuntime,
  type FetchHandler,
  textResponse,
} from "@/lib/__tests__/testUtils/serverRuntime";

/**
 * Google Analytics OAuth コールバックの `state` 照合(issue #1231)。
 *
 * AdSense(`connect/adsense/__tests__/callback-route.test.ts`)と同じ理由で、cookie と `state` の不一致は
 * 受け入れシナリオからはブラウザ経路で到達できない(同意画面は accounts.google.com でスタブ化の
 * 対象外)ため、Route Handler の単体テストが担当する。受け入れシナリオ側
 * (`e2e/features/analytics/credentials.feature`)は不正な認可コードの拒否と、
 * 未認証コールバックが認可コードを処理しないことを見る(docs/ACCEPTANCE_TESTING.md §4 の `@api` 例外)。
 */
// 自前の @/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤
// (next/headers・next/server の after・next-auth・next/navigation の redirect)と、外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

/** project-service の代役(gateway の向こう側)。呼び出しは HTTP の本文として届く。 */
const mockComplete = jest.fn();
const ADMIN_SESSION = { user: { id: "admin-1", role: "admin", email: "admin@example.com" } };
const completeHandler: FetchHandler = async (_url, init) => {
  try {
    return jsonResponse((await mockComplete(JSON.parse(String(init.body)))) ?? null);
  } catch (err) {
    return textResponse(err instanceof Error ? err.message : String(err), 500);
  }
};

const STATE_COOKIE = "google_analytics_oauth_state";
const PROJECT_ID = 42;
const COMPLETE = `POST /api/projects/${PROJECT_ID}/api-keys/google-analytics/oauth-callback`;
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
    mockComplete.mockReset();
    process.env.NEXTAUTH_URL = "https://localhost";
    resetServerRuntime(ADMIN_SESSION);
    mockComplete.mockResolvedValue(undefined);
    installFetch({ [COMPLETE]: completeHandler });
  });

  it("stateがcookieと一致すれば認可コードを交換し、連携完了として設定画面へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    expect(mockComplete).toHaveBeenCalledWith({
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
    installFetch({ [COMPLETE]: () => textResponse("Bad Gateway", 502) });

    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/google-analytics`);
    expect(target.searchParams.get("error")).toBe("APIエラー (502): Bad Gateway");
  });

  it("Errorでない例外も文字列にして戻す", async () => {
    // fetch 自体が Error でない値で失敗した場合(ネットワーク層の失敗)
    installFetch({
      [COMPLETE]: () => {
        throw "boom";
      },
    });

    const response = await GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE));

    expect(redirectTarget(response).searchParams.get("error")).toBe("boom");
  });

  it("管理者でなければ、認可コードを交換せずトップへ送る", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user", email: "u@example.com" } });

    await expect(GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE))).rejects.toThrow("NEXT_REDIRECT:/");

    expect(mockComplete).not.toHaveBeenCalled();
  });

  it("未ログインならログイン画面へ送り、認可コードを交換しない", async () => {
    resetServerRuntime(null);

    await expect(GET(callback({ code: "auth-code", state: VALID_STATE }, VALID_STATE))).rejects.toThrow("NEXT_REDIRECT:/login");

    expect(mockComplete).not.toHaveBeenCalled();
  });
});
