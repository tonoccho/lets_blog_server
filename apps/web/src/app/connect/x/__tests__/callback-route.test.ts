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
 * X の OAuth コールバック(issue #1574)。state の突き合わせ・PKCE の検証子・クライアントの秘密は
 * バックエンド(project-service)がメモリに持つため、ここは state の形から対象プロジェクトを決め、
 * state と認可コードを渡すだけ。トークンはこの経路にも現れない(戻りはアカウント名だけ)。
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

const PROJECT_ID = 42;
const COMPLETE = `POST /api/projects/${PROJECT_ID}/sns/x/callback`;
const STATE = `${PROJECT_ID}.abcDEF123_-`;

function callback(params: Record<string, string>): NextRequest {
  const url = new URL("https://localhost/connect/x/callback");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  return new NextRequest(url);
}

function redirectTarget(response: Response): URL {
  return new URL(response.headers.get("location") ?? "");
}

describe("GET /connect/x/callback", () => {
  beforeEach(() => {
    mockComplete.mockReset();
    process.env.NEXTAUTH_URL = "https://localhost";
    resetServerRuntime(ADMIN_SESSION);
    mockComplete.mockResolvedValue({ projectId: PROJECT_ID, accountName: "lets_blog" });
    installFetch({ [COMPLETE]: completeHandler });
  });

  it("stateと認可コードをバックエンドへ渡し、接続完了として設定画面へ戻す", async () => {
    const response = await GET(callback({ code: "auth-code", state: STATE }));

    expect(mockComplete).toHaveBeenCalledWith({ state: STATE, code: "auth-code" });
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("connected")).toBe("1");
  });

  it("コードが無ければ交換せず、invalid_stateで設定画面へ戻す", async () => {
    const response = await GET(callback({ state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    expect(redirectTarget(response).searchParams.get("error")).toBe("invalid_state");
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

  it("X側がエラーを返したときは、その理由を添えて設定画面へ戻す", async () => {
    const response = await GET(callback({ error: "access_denied", state: STATE }));

    expect(mockComplete).not.toHaveBeenCalled();
    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("access_denied");
  });

  it("接続に失敗したときは、その理由を添えて設定画面へ戻す", async () => {
    mockComplete.mockRejectedValue(new Error("本番サイトへトークンを送れませんでした(接続失敗)"));

    const response = await GET(callback({ code: "auth-code", state: STATE }));

    const target = redirectTarget(response);
    expect(target.pathname).toBe(`/projects/${PROJECT_ID}/settings/sns`);
    expect(target.searchParams.get("error")).toBe("APIエラー (500): 本番サイトへトークンを送れませんでした(接続失敗)");
    expect(target.searchParams.get("connected")).toBeNull();
  });

  it("Errorでない例外も文字列にして戻す", async () => {
    // fetch 自体が Error でない値で失敗した場合(ネットワーク層の失敗)
    installFetch({
      [COMPLETE]: () => {
        throw "boom";
      },
    });

    const response = await GET(callback({ code: "auth-code", state: STATE }));

    expect(redirectTarget(response).searchParams.get("error")).toBe("boom");
  });

  it("管理者でなければ、認可コードを交換せずトップへ送る", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user", email: "u@example.com" } });

    await expect(GET(callback({ code: "auth-code", state: STATE }))).rejects.toThrow("NEXT_REDIRECT:/");

    expect(mockComplete).not.toHaveBeenCalled();
  });

  it("未ログインならログイン画面へ送り、認可コードを交換しない", async () => {
    resetServerRuntime(null);

    await expect(GET(callback({ code: "auth-code", state: STATE }))).rejects.toThrow("NEXT_REDIRECT:/login");

    expect(mockComplete).not.toHaveBeenCalled();
  });
});
