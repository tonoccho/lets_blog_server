/**
 * @jest-environment node
 */
import { NextRequest } from "next/server";
import { GET } from "../callback/route";
import { completeProjectFacebookAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * Facebook の OAuth コールバック(X・Threads と同型)(issue #1580)。state の突き合わせ・クライアントの秘密は
 * バックエンド(project-service)がメモリに持つため、ここは state の形から対象プロジェクトを決め、
 * state と認可コードを渡すだけ。認可のあとは投稿先のページを選ぶので、設定画面へは
 * `facebookState=<state>` を付けて戻す(ページの一覧はその画面がバックエンドから取る。トークンはこの経路にも現れない)。
 */
jest.mock("@/lib/apiClient", () => ({
  completeProjectFacebookAuthorization: jest.fn(),
}));
jest.mock("@/lib/session", () => ({
  requireAdminSession: jest.fn(),
}));

const mockComplete = completeProjectFacebookAuthorization as jest.MockedFunction<typeof completeProjectFacebookAuthorization>;
const mockRequireAdminSession = requireAdminSession as jest.MockedFunction<typeof requireAdminSession>;

const PROJECT_ID = 42;
const STATE = `${PROJECT_ID}.abcDEF123_-`;

function callback(params: Record<string, string>): NextRequest {
  const url = new URL("https://localhost/connect/facebook/callback");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  return new NextRequest(url);
}

function redirectTarget(response: Response): URL {
  return new URL(response.headers.get("location") ?? "");
}

describe("GET /connect/facebook/callback", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    mockRequireAdminSession.mockResolvedValue({} as never);
    mockComplete.mockResolvedValue({ projectId: PROJECT_ID, pages: [{ id: "100", name: "ページA" }] });
  });

  it("stateと認可コードをバックエンドへ渡し、ページ選択のため facebookState を付けて設定画面へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: STATE }));

    expect(mockComplete).toHaveBeenCalledWith(PROJECT_ID, { state: STATE, code: "auth-code" });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("facebookState")).toBe(STATE);
    expect(target.searchParams.get("connected")).toBeNull();
  });

  it("コードが無ければ交換せず、invalid_stateで設定画面へ戻す", async () => {
    const response = await GET(callback({ state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.searchParams.get("error")).toBe("invalid_state");
    expect(target.searchParams.get("sns")).toBe("facebook");
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

  it("Facebook側がエラーを返したときは、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(callback({ error: "access_denied", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("access_denied");
    expect(target.searchParams.get("sns")).toBe("facebook");
  });

  it("接続に失敗したとき(ページが無い等)は、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("接続できる Facebook ページがありません"));

    const response = await GET(callback({ code: "auth-code", state: STATE }));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("接続できる Facebook ページがありません");
    expect(target.searchParams.get("facebookState")).toBeNull();
    expect(target.searchParams.get("sns")).toBe("facebook");
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
