import { act, renderHook } from "@testing-library/react";
import {
  MAX_CONSECUTIVE_POLL_FAILURES,
  POLL_INTERVAL_MS,
  pollFailureMessage,
  useGenerationJobPolling,
} from "../useGenerationJobPolling";
import { fetchGenerationJobAction } from "../actions";
import type { GenerationJobDetail } from "@/lib/apiClient";

/** issue #1716: 取得の一過性の失敗でポーリングを止めず、連続して続いたときだけ止めて呼び出し側へ伝える。 */
jest.mock("../actions", () => ({ fetchGenerationJobAction: jest.fn() }));

const fetchJobMock = fetchGenerationJobAction as jest.MockedFunction<typeof fetchGenerationJobAction>;

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
  fetchJobMock.mockReset();
});
afterEach(() => {
  jest.useRealTimers();
});

describe("useGenerationJobPolling の取得失敗", () => {
  it("1回の失敗では止まらず、同じ間隔で続けて done なら onSettled を呼ぶ", async () => {
    fetchJobMock.mockRejectedValueOnce(new Error("503")).mockResolvedValue(job("done"));
    const onSettled = jest.fn();
    const onPollFailed = jest.fn();
    const { result } = renderHook(() => useGenerationJobPolling(onSettled, undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    expect(fetchJobMock).toHaveBeenCalledTimes(1);
    await tick();

    expect(fetchJobMock).toHaveBeenCalledTimes(2);
    expect(onSettled).toHaveBeenCalledTimes(1);
    expect(onPollFailed).not.toHaveBeenCalled();
    expect(result.current.isPolling).toBe(false);
  });

  it("連続して上限回数失敗したら止まり、onPollFailed に最後のエラーを渡す", async () => {
    fetchJobMock.mockRejectedValue(new Error("boom"));
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

    expect(fetchJobMock).toHaveBeenCalledTimes(MAX_CONSECUTIVE_POLL_FAILURES);
    expect(onPollFailed).toHaveBeenCalledTimes(1);
    expect((onPollFailed.mock.calls[0][0] as Error).message).toBe("boom");
    expect(result.current.isPolling).toBe(false);
    await tick();
    expect(fetchJobMock).toHaveBeenCalledTimes(MAX_CONSECUTIVE_POLL_FAILURES);
    expect(onSettled).not.toHaveBeenCalled();
  });

  it("成功が1回あれば連続失敗の数え直しになり、間に成功を挟めば止まらない", async () => {
    const n = MAX_CONSECUTIVE_POLL_FAILURES - 1;
    for (let i = 0; i < n; i++) fetchJobMock.mockRejectedValueOnce(new Error("x"));
    fetchJobMock.mockResolvedValueOnce(job("running"));
    for (let i = 0; i < n; i++) fetchJobMock.mockRejectedValueOnce(new Error("x"));
    fetchJobMock.mockResolvedValue(job("done"));
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
    fetchJobMock.mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() => useGenerationJobPolling(jest.fn()));

    await act(async () => {
      result.current.startPolling(1);
    });
    for (let i = 1; i < MAX_CONSECUTIVE_POLL_FAILURES; i++) await tick();

    expect(result.current.isPolling).toBe(false);
  });

  it("失敗の待機中にアンマウントされたら、その後は取得も通知もしない", async () => {
    fetchJobMock.mockRejectedValue(new Error("boom"));
    const onPollFailed = jest.fn();
    const { result, unmount } = renderHook(() => useGenerationJobPolling(jest.fn(), undefined, onPollFailed));

    await act(async () => {
      result.current.startPolling(1);
    });
    unmount();
    await tick();

    expect(fetchJobMock).toHaveBeenCalledTimes(1);
    expect(onPollFailed).not.toHaveBeenCalled();
  });

  it("取得の失敗が返る前にアンマウントされたら、通知しない", async () => {
    let rejectFetch: (e: Error) => void = () => {};
    fetchJobMock.mockReturnValue(new Promise((_, reject) => (rejectFetch = reject)));
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
