/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import { completeProjectLinkedInAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * LinkedIn の OAuth コールバック(X と同型。他の SNS の画面と区別するため、戻り先に sns=linkedin を付ける)(issue #1581)。state の突き合わせ・クライアントの秘密は
 * バックエンド(project-service)がメモリに持つため、ここは state の形から対象プロジェクトを決め、
 * state と認可コードを渡すだけ。トークンはこの経路にも現れない(戻りはアカウント名だけ)。
 */
jest.mock("@/lib/apiClient", () => ({
  completeProjectLinkedInAuthorization: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockComplete = completeProjectLinkedInAuthorization as jest.MockedFunction<typeof completeProjectLinkedInAuthorization>;
const mockRequireAdminSession = requireAdminSession as jest.MockedFunction<typeof requireAdminSession>;

const PROJECT_ID = 42;
const STATE = `${PROJECT_ID}.abcDEF123_-`;

function callback(params: Record<string, string>): NextRequest {
  const url = new URL("https://localhost/connect/linkedin/callback");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  return new NextRequest(url);
}

function redirectTarget(response: Response): URL {
  return new URL(response.headers.get("location") ?? "");
}

describe("GET /connect/linkedin/callback", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    mockRequireAdminSession.mockResolvedValue({} as never);
    mockComplete.mockResolvedValue({ projectId: PROJECT_ID, accountName: "Let's Blog E2E" });
  });

  it("stateと認可コードをバックエンドへ渡し、接続完了として設定画面へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: STATE }));

    expect(mockComplete).toHaveBeenCalledWith(PROJECT_ID, { state: STATE, code: "auth-code" });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("connected")).toBe("linkedin");
    expect(target.searchParams.get("connected")).not.toBe("1");
  });

  it("コードが無ければ交換せず、invalid_stateで設定画面へ戻す", async () => {
    const response = await GET(callback({ state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.searchParams.get("error")).toBe("invalid_state");
    expect(target.searchParams.get("sns")).toBe("linkedin");
  });

  it("stateが無ければ交換せず、プロジェクト一覧へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code" }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe("/projects");
    expect(target.searchParams.get("error")).toBe("invalid_state");
  });

  it("stateにプロジェクトIDが含まれていなければ、プロジェクト一覧へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: "not-a-number.nonce" }));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).pathname).toBe("/projects");
  });

  it("LinkedIn側がエラーを返したときは、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(callback({ error: "access_denied", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("access_denied");
    expect(target.searchParams.get("sns")).toBe("linkedin");
  });

  it("接続に失敗したときは、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("本番サイトへトークンを送れませんでした(接続失敗)"));

    const response = await GET(callback({ code: "auth-code", state: STATE }));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("本番サイトへトークンを送れませんでした(接続失敗)");
    expect(target.searchParams.get("connected")).toBeNull();
    expect(target.searchParams.get("sns")).toBe("linkedin");
  });

  it("Errorでない例外も文字列にして戻す", async () => {
    mockComplete.mockRejectedValue("boom");

    const response = await GET(callback({ code: "auth-code", state: STATE }));

    expect(redirectTarget(response).searchParams.get("error")).toBe("boom");
  });

  it("交換の前に管理者セッションを要求する", async () => {
    await GET(callback({ code: "auth-code", state: STATE }));

    expect(mockRequireAdminSession).toHaveBeenCalled();
  });
});
