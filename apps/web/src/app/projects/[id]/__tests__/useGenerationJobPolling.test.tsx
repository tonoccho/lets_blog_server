import { act, renderHook } from "@testing-library/react";
import {
  MAX_CONSECUTIVE_POLL_FAILURES,
  POLL_INTERVAL_MS,
  pollFailureMessage,
  useGenerationJobPolling,
} from "../useGenerationJobPolling";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import type { GenerationJobDetail } from "@/lib/apiClient";

/**
 * issue #1716: 取得の一過性の失敗でポーリングを止めず、連続して続いたときだけ止めて呼び出し側へ伝える。
 * `../actions`(`fetchGenerationJobAction`)・`@/lib/apiClient`・`@/lib/session` は差し替えず、gateway の
 * `GET /api/generation-jobs/1` の応答を `fetch` の層で順番に返す(#1836)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

type Outcome = "503" | "done" | "running";
/** 呼ばれるたびに 1 つずつ使う。尽きたら最後の 1 つを使い続ける。 */
let script: Outcome[] = [];
let fetchCalls = 0;
/** 設定すると、応答を返す代わりにこの Promise を返す(返る前にアンマウントする検証用)。 */
let gate: Promise<Response> | null = null;

function respond(): Response | Promise<Response> {
  fetchCalls += 1;
  if (gate) return gate;
  const outcome = script.length > 1 ? (script.shift() as Outcome) : script[0];
  return outcome === "503" ? textResponse("Service Unavailable", 503) : jsonResponse(job(outcome));
}

function job(status: string): GenerationJobDetail {
  return {
    id: 1,
    type: "t",
    status,
    requestPayload: null,
    resultPayload: null,
    createdAt: "",
    updatedAt: "",
  } as GenerationJobDetail;
}

async function tick() {
  await act(async () => {
    await jest.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
  });
}

beforeEach(() => {
  jest.useFakeTimers();
  resetServerRuntime(ADMIN_SESSION);
  script = [];
  fetchCalls = 0;
  gate = null;
  installFetchByPath({ "GET /api/generation-jobs/:id": respond });
});
afterEach(() => {
  jest.useRealTimers();
});

describe("useGenerationJobPolling の取得失敗", () => {
  it("1回の失敗では止まらず、同じ間隔で続けて done なら onSettled を呼ぶ", async () => {
    script = ["503", "done"];
    const onSettled = jest.fn();
    const onPollFailed = jest.fn();
    const { result } = renderHook(() => useGenerationJobPolling(onSettled, undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    expect(fetchCalls).toBe(1);
    await tick();

    expect(fetchCalls).toBe(2);
    expect(onSettled).toHaveBeenCalledTimes(1);
    expect(onPollFailed).not.toHaveBeenCalled();
    expect(result.current.isPolling).toBe(false);
  });

  it("連続して上限回数失敗したら止まり、onPollFailed に最後のエラーを渡す", async () => {
    script = ["503"];
    const onSettled = jest.fn();
    const onPollFailed = jest.fn();
    const { result } = renderHook(() => useGenerationJobPolling(onSettled, undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    for (let i = 1; i < MAX_CONSECUTIVE_POLL_FAILURES; i++) {
      expect(onPollFailed).not.toHaveBeenCalled();
      await tick();
    }

    expect(fetchCalls).toBe(MAX_CONSECUTIVE_POLL_FAILURES);
    expect(onPollFailed).toHaveBeenCalledTimes(1);
    expect((onPollFailed.mock.calls[0][0] as Error).message).toBe("APIエラー (503): Service Unavailable");
    expect(result.current.isPolling).toBe(false);
    await tick();
    expect(fetchCalls).toBe(MAX_CONSECUTIVE_POLL_FAILURES);
    expect(onSettled).not.toHaveBeenCalled();
  });

  it("成功が1回あれば連続失敗の数え直しになり、間に成功を挟めば止まらない", async () => {
    const n = MAX_CONSECUTIVE_POLL_FAILURES - 1;
    script = [...Array(n).fill("503"), "running", ...Array(n).fill("503"), "done"];
    const onSettled = jest.fn();
    const onProgress = jest.fn();
    const onPollFailed = jest.fn();
    const { result } = renderHook(() => useGenerationJobPolling(onSettled, onProgress, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    for (let i = 0; i < 2 * n + 1; i++) await tick();

    expect(onProgress).toHaveBeenCalledTimes(1);
    expect(onPollFailed).not.toHaveBeenCalled();
    expect(onSettled).toHaveBeenCalledTimes(1);
  });

  it("onPollFailed が無くても連続失敗で止まる", async () => {
    script = ["503"];
    const { result } = renderHook(() => useGenerationJobPolling(jest.fn()));

    await act(async () => {
      result.current.startPolling(1);
    });
    for (let i = 1; i < MAX_CONSECUTIVE_POLL_FAILURES; i++) await tick();

    expect(result.current.isPolling).toBe(false);
  });

  it("失敗の待機中にアンマウントされたら、その後は取得も通知もしない", async () => {
    script = ["503"];
    const onPollFailed = jest.fn();
    const { result, unmount } = renderHook(() => useGenerationJobPolling(jest.fn(), undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    unmount();
    await tick();

    expect(fetchCalls).toBe(1);
    expect(onPollFailed).not.toHaveBeenCalled();
  });

  it("取得の失敗が返る前にアンマウントされたら、通知しない", async () => {
    let rejectFetch: (e: Error) => void = () => {};
    gate = new Promise<Response>((_, reject) => (rejectFetch = reject));
    const onPollFailed = jest.fn();
    const { result, unmount } = renderHook(() => useGenerationJobPolling(jest.fn(), undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    unmount();
    await act(async () => {
      rejectFetch(new Error("late"));
    });

    expect(onPollFailed).not.toHaveBeenCalled();
  });
});

describe("useGenerationJobPolling の取得成功", () => {
  it("取得の成功が返る前にアンマウントされたら、完了も進捗も通知しない", async () => {
    let resolveFetch: (r: Response) => void = () => {};
    gate = new Promise<Response>((resolve) => (resolveFetch = resolve));
    const onSettled = jest.fn();
    const onProgress = jest.fn();
    const { result, unmount } = renderHook(() => useGenerationJobPolling(onSettled, onProgress));

    await act(async () => {
      result.current.startPolling(1);
    });
    unmount();
    await act(async () => {
      resolveFetch(jsonResponse(job("done")));
    });
    await tick();

    expect(onSettled).not.toHaveBeenCalled();
    expect(onProgress).not.toHaveBeenCalled();
    expect(fetchCalls).toBe(1);
  });
});

describe("pollFailureMessage", () => {
  it("エラーの理由を含め、進捗を確認できなかったことを示す", () => {
    expect(pollFailureMessage(new Error("503 Service Unavailable"))).toBe(
      "進捗を確認できませんでした(503 Service Unavailable)。サーバ側の処理は続いている可能性があります。画面を再読み込みして状態を確認してください。"
    );
  });

  it("Error 以外が投げられても文字列化して示す", () => {
    expect(pollFailureMessage("plain")).toContain("(plain)");
  });
});
