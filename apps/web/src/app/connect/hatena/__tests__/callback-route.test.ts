/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import { completeProjectHatenaAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * はてなブックマーク の OAuth 1.0a コールバック(X と同型。他の SNS の画面と区別するため、戻り先に sns=hatena を付ける)(issue #1582)。
 * state の突き合わせ・consumer secret・リクエストトークンの秘密はバックエンド(project-service)がメモリに持つため、
 * ここは state の形から対象プロジェクトを決め、state・oauth_token・oauth_verifier を渡すだけ。
 * トークンはこの経路にも現れない(戻りはアカウント名だけ)。
 */
jest.mock("@/lib/apiClient", () => ({
  completeProjectHatenaAuthorization: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockComplete = completeProjectHatenaAuthorization as jest.MockedFunction<typeof completeProjectHatenaAuthorization>;
const mockRequireAdminSession = requireAdminSession as jest.MockedFunction<typeof requireAdminSession>;

const PROJECT_ID = 42;
const STATE = `${PROJECT_ID}.abcDEF123_-`;

function callback(params: Record<string, string>): NextRequest {
  const url = new URL("https://localhost/connect/hatena/callback");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  return new NextRequest(url);
}

function redirectTarget(response: Response): URL {
  return new URL(response.headers.get("location") ?? "");
}

describe("GET /connect/hatena/callback", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    mockRequireAdminSession.mockResolvedValue({} as never);
    mockComplete.mockResolvedValue({ projectId: PROJECT_ID, accountName: "Let's Blog E2E" });
  });

  it("state・リクエストトークン・verifierをバックエンドへ渡し、接続完了として設定画面へ戻す", async () => {
    const response = await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier", state: STATE }));

    expect(mockComplete).toHaveBeenCalledWith(PROJECT_ID, {
      state: STATE,
      oauthToken: "request-token",
      oauthVerifier: "the-verifier",
    });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("connected")).toBe("hatena");
    expect(target.searchParams.get("connected")).not.toBe("1");
  });

  it("verifierが無ければ交換せず、invalid_stateで設定画面へ戻す", async () => {
    const response = await GET(callback({ oauth_token: "request-token", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.searchParams.get("error")).toBe("invalid_state");
    expect(target.searchParams.get("sns")).toBe("hatena");
  });

  it("stateが無ければ交換せず、プロジェクト一覧へ戻す", async () => {
    const response = await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier" }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe("/projects");
    expect(target.searchParams.get("error")).toBe("invalid_state");
  });

  it("stateにプロジェクトIDが含まれていなければ、プロジェクト一覧へ戻す", async () => {
    const response = await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier", state: "not-a-number.nonce" }));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).pathname).toBe("/projects");
  });

  it("リクエストトークンが無ければ交換せず、invalid_stateで設定画面へ戻す", async () => {
    const response = await GET(callback({ oauth_verifier: "the-verifier", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("invalid_state");
  });

  it("はてなブックマーク側が拒否(oauth_problem)したときも、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(callback({ oauth_problem: "user_refused", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.searchParams.get("error")).toBe("user_refused");
    expect(target.searchParams.get("sns")).toBe("hatena");
  });

  it("はてなブックマーク側がエラーを返したときは、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(callback({ error: "access_denied", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("access_denied");
    expect(target.searchParams.get("sns")).toBe("hatena");
  });

  it("接続に失敗したときは、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("本番サイトへトークンを送れませんでした(接続失敗)"));

    const response = await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier", state: STATE }));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("本番サイトへトークンを送れませんでした(接続失敗)");
    expect(target.searchParams.get("connected")).toBeNull();
    expect(target.searchParams.get("sns")).toBe("hatena");
  });

  it("Errorでない例外も文字列にして戻す", async () => {
    mockComplete.mockRejectedValue("boom");

    const response = await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier", state: STATE }));

    expect(redirectTarget(response).searchParams.get("error")).toBe("boom");
  });

  it("交換の前に管理者セッションを要求する", async () => {
    await GET(callback({ oauth_token: "request-token", oauth_verifier: "the-verifier", state: STATE }));

    expect(mockRequireAdminSession).toHaveBeenCalled();
  });
});
