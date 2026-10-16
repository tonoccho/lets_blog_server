import { act, render, screen } from "@testing-library/react";
import { ContainerStatusPanel } from "../ContainerStatusPanel";
import type { ContainerStatus } from "@/lib/apiClient";

/**
 * issue #1362(親issue #1261 分割A): `ContainerStatusPanel`は`ConnectedServiceStatusPanel`と
 * 同じSSE+ポーリングフォールバック構成を持ち、最終更新時刻を
 * `lastUpdatedAt.toLocaleTimeString("ja-JP")`(TZ引数無し)で整形していた。TZ引数を省くと
 * 実行環境依存の既定TZ(コンテナ/ブラウザ)にフォールバックし、SSRとブラウザで表示が
 * ずれ得る。個人設定TZ(`personalTimeZone`)を受け取り`timeZone`へ渡す。
 *
 * `lastUpdatedAt`は初期値`null`でマウント後の更新でしか入らないため、SSR/クライアントの
 * 初期描画は常に一致しており、この項目にはgateが要らない
 * (Readiness Report参照、issue #1362)。
 */

/** jsdomは既定でEventSourceを持たない(#1362実装調査で確認済み)。 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static readonly CLOSED = 2;
  readyState = 0;
  onerror: ((event: Event) => void) | null = null;
  private listeners: Record<string, Array<(event: MessageEvent) => void>> = {};

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, callback: (event: MessageEvent) => void) {
    (this.listeners[type] ||= []).push(callback);
  }

  dispatch(type: string, data?: unknown) {
    for (const callback of this.listeners[type] ?? []) {
      callback({ data: JSON.stringify(data) } as MessageEvent);
    }
  }

  close() {
    // no-op
  }
}

function containerStatus(overrides: Partial<ContainerStatus> = {}): ContainerStatus {
  return {
    id: "lbs-web",
    name: "lbs-web",
    status: "NORMAL",
    state: "running",
    detail: "",
    ...overrides,
  };
}

describe("ContainerStatusPanel", () => {
  beforeEach(() => {
    FakeEventSource.instances.length = 0;
    (global as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
  });

  afterEach(() => {
    delete (global as unknown as { EventSource?: unknown }).EventSource;
    jest.restoreAllMocks();
  });

  it("コンテナ一覧を表示する", () => {
    render(<ContainerStatusPanel initialStatuses={[containerStatus()]} personalTimeZone={null} />);

    expect(screen.getByText("lbs-web")).toBeInTheDocument();
  });

  it("state が standby のコンテナは「正常」ではなく「待機中」と表示する(issue #1584)", () => {
    render(
      <ContainerStatusPanel
        initialStatuses={[
          containerStatus({ id: "a", name: "comfyui", state: "running" }),
          containerStatus({ id: "b", name: "comfyui-cpu", state: "standby", detail: "Exited (0) 1 minute ago" }),
        ]}
        personalTimeZone={null}
      />
    );

    expect(screen.getByText("待機中")).toBeInTheDocument();
    expect(screen.getAllByText("正常")).toHaveLength(1);
    expect(screen.getByText("comfyui-cpu").closest("li")).toHaveAttribute("title", "Exited (0) 1 minute ago");
  });

  it("エラーのコンテナは state に関わらず「エラー」と表示する(issue #1584)", () => {
    render(
      <ContainerStatusPanel
        initialStatuses={[containerStatus({ status: "ERROR", state: "exited" })]}
        personalTimeZone={null}
      />
    );

    expect(screen.getByText("エラー")).toBeInTheDocument();
    expect(screen.queryByText("待機中")).not.toBeInTheDocument();
  });

  // --- SSE と、SSE が使えないときのポーリング(fetch)。#1832 ---------------------------------
  describe("更新の取得(SSE とポーリング)", () => {
    const POLL_MS = 30000;
    const NOW = new Date("2026-09-08T20:03:35Z");
    type FetchMock = jest.Mock<Promise<unknown>, [string, RequestInit?]>;

    function useFixedClock() {
      // Date だけを固定する。setInterval / Promise は実物のまま使いたいので、他は偽装しない。
      jest.useFakeTimers({
        now: NOW,
        doNotFake: [
          "hrtime", "nextTick", "performance", "queueMicrotask", "requestAnimationFrame",
          "cancelAnimationFrame", "requestIdleCallback", "cancelIdleCallback", "setImmediate",
          "clearImmediate", "setTimeout", "clearTimeout",
        ],
      });
    }

    function installFetch(impl: () => Promise<unknown>): FetchMock {
      const fetchMock = jest.fn(impl) as unknown as FetchMock;
      global.fetch = fetchMock as unknown as typeof fetch;
      return fetchMock;
    }

    const okJson = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: async () => body });
    const failed = (status: number) => Promise.resolve({ ok: false, status, json: async () => ({}) });

    async function tick(ms = POLL_MS) {
      await act(async () => {
        jest.advanceTimersByTime(ms);
      });
    }

    afterEach(() => {
      jest.useRealTimers();
    });

    it("EventSource が無いブラウザでは30秒ごとにfetchで取得して一覧を更新し、最終更新時刻を出す", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const fetchMock = installFetch(() => okJson([{ id: "polled", name: "polled-service", status: "NORMAL", state: "running", detail: "" }]));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);

      expect(fetchMock).not.toHaveBeenCalled();
      await tick();

      expect(fetchMock).toHaveBeenCalledWith("/api/dashboard/container-status", { cache: "no-store" });
      expect(screen.getByText("polled-service")).toBeInTheDocument();
      // 個人設定TZ(Asia/Tokyo, +9)の壁時計。固定した現在時刻 20:03:35Z から30秒進んだ 20:04:05Z → 5:04:05。
      expect(screen.getByText(/最終更新: 5:04:05/)).toBeInTheDocument();
      expect(FakeEventSource.instances).toHaveLength(0);
    });

    it("個人設定TZが未設定のときの最終更新時刻はブラウザ既定のTZ(jest ではUTC)で出す", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      installFetch(() => okJson([]));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone={null} />);

      await tick();

      expect(screen.getByText(/最終更新: 20:04:05/)).toBeInTheDocument();
    });

    it.each([
      [401, "認証されていないためコンテナの状態を取得できません。再ログインしてください。"],
      [403, "認証されていないためコンテナの状態を取得できません。再ログインしてください。"],
      [500, "コンテナの状態の取得に失敗しました(HTTP 500)。"],
    ])("取得が HTTP %i で失敗したら、握り潰さず理由を画面に出す(issue #876)", async (status, message) => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      installFetch(() => failed(status));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);

      await tick();

      expect(screen.getByText(message)).toBeInTheDocument();
    });

    it("通信エラーでも直近の表示は維持し、通信エラーを画面に出す。次に取得できればエラーを消す", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      let call = 0;
      installFetch(() => (call++ === 0 ? Promise.reject(new Error("offline")) : okJson([])));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);

      await tick();
      expect(screen.getByText("コンテナの状態を取得できませんでした(通信エラー)。")).toBeInTheDocument();
      expect(screen.getByText("initial-service")).toBeInTheDocument();

      await tick();
      expect(screen.queryByText("コンテナの状態を取得できませんでした(通信エラー)。")).not.toBeInTheDocument();
    });

    it("取得中にアンマウントされたら、結果も失敗も画面に反映しない", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const pending: Array<(v: unknown) => void> = [];
      const fetchMock = installFetch(() => new Promise((resolve) => pending.push(resolve)));
      const { unmount } = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);

      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      unmount();
      // アンマウント後に、成功・失敗(HTTP エラー・例外)の順で応答が返っても例外にならない。
      await act(async () => {
        pending[0](await okJson([{ id: "polled", name: "polled-service", status: "NORMAL", state: "running", detail: "" }]));
      });
      // アンマウントでインターバルは止まるので、次の取得は走らない。
      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("取得中にアンマウントされ、応答が HTTP エラーでも、通信エラーでも、何も起きない", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const pending: Array<{ resolve: (v: unknown) => void; reject: (e: unknown) => void }> = [];
      installFetch(() => new Promise((resolve, reject) => pending.push({ resolve, reject })));
      const first = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      await tick();
      first.unmount();
      await act(async () => {
        pending[0].resolve(await failed(500));
      });

      const second = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      await tick();
      second.unmount();
      await act(async () => {
        pending[1].reject(new Error("offline"));
      });
      expect(document.body).not.toHaveTextContent("通信エラー");
    });

    it("SSE が使えるときは接続を張り、open でリアルタイム表示、status で一覧を更新する", () => {
      useFixedClock();
      installFetch(() => okJson([]));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);

      const es = FakeEventSource.instances[0];
      expect(es.url).toBe("/api/dashboard/container-status/stream");
      expect(screen.queryByText("リアルタイム更新中")).not.toBeInTheDocument();
      act(() => es.dispatch("open"));
      expect(screen.getByText("リアルタイム更新中")).toBeInTheDocument();

      act(() => es.dispatch("status", [{ id: "polled", name: "polled-service", status: "NORMAL", state: "running", detail: "" }]));
      expect(screen.getByText("polled-service")).toBeInTheDocument();
      expect(screen.getByText(/最終更新: 5:03:35/)).toBeInTheDocument();
    });

    it("不正な status ペイロードは無視して、直前の一覧を維持する", () => {
      useFixedClock();
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      const es = FakeEventSource.instances[0];

      act(() => {
        // JSON ではない文字列を直接渡す。
        for (const callback of (es as unknown as { listeners: Record<string, Array<(e: MessageEvent) => void>> }).listeners["status"]) {
          callback({ data: "{not json" } as MessageEvent);
        }
      });

      expect(screen.getByText("initial-service")).toBeInTheDocument();
      expect(screen.queryByText(/最終更新/)).not.toBeInTheDocument();
    });

    it("接続が閉じられたら(CLOSED)リアルタイム表示を消し、ポーリングへ切り替える。再度のエラーで二重に始めない", async () => {
      useFixedClock();
      const fetchMock = installFetch(() => okJson([]));
      render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      const es = FakeEventSource.instances[0];
      act(() => es.dispatch("open"));

      // 再接続中(CONNECTING)の一時的なエラーでは切り替えない。
      act(() => es.onerror?.(new Event("error")));
      expect(screen.getByText("リアルタイム更新中")).toBeInTheDocument();
      await tick();
      expect(fetchMock).not.toHaveBeenCalled();

      es.readyState = FakeEventSource.CLOSED;
      act(() => es.onerror?.(new Event("error")));
      act(() => es.onerror?.(new Event("error")));
      expect(screen.queryByText("リアルタイム更新中")).not.toBeInTheDocument();

      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("アンマウントすると接続を閉じ、アンマウント後のイベントは何も反映しない", () => {
      useFixedClock();
      const close = jest.spyOn(FakeEventSource.prototype, "close");
      const { unmount } = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      const es = FakeEventSource.instances[0];

      unmount();
      expect(close).toHaveBeenCalledTimes(1);
      // アンマウント後に届いたイベント。cancelled なので状態を更新しない(警告・例外が出ない)。
      es.dispatch("open");
      es.dispatch("status", [{ id: "polled", name: "polled-service", status: "NORMAL", state: "running", detail: "" }]);
      es.readyState = FakeEventSource.CLOSED;
      es.onerror?.(new Event("error"));
    });

    it("ポーリング中にアンマウントするとインターバルを止める", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const fetchMock = installFetch(() => okJson([]));
      const { unmount } = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      unmount();
      await tick(POLL_MS * 3);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("SSE が閉じてポーリングへ切り替わった後にアンマウントしても、インターバルを止める", async () => {
      useFixedClock();
      const fetchMock = installFetch(() => okJson([]));
      const { unmount } = render(<ContainerStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL", state: "running", detail: "" }]} personalTimeZone="Asia/Tokyo" />);
      const es = FakeEventSource.instances[0];
      es.readyState = FakeEventSource.CLOSED;
      act(() => es.onerror?.(new Event("error")));
      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      unmount();
      await tick(POLL_MS * 3);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
  });
});
