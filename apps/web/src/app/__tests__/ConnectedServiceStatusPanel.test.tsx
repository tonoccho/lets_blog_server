import { act, render, screen } from "@testing-library/react";
import { ConnectedServiceStatusPanel } from "../ConnectedServiceStatusPanel";
import type { ConnectedServiceStatusDetail } from "@/lib/apiClient";

/**
 * issue #1236: `new Date(detail.checkedAt).toLocaleString("ja-JP")` を直接呼んでいたため、
 * オフセット無しの日時文字列(バックエンドのLocalDateTime由来)が実行環境のTZでパースされ、
 * SSRとブラウザで表示がずれ得た。共有ヘルパ`formatDateTime`を経由するよう変更する。
 *
 * issue #1362: 個人設定TZ(`personalTimeZone`)を受け取る経路を追加した。個人設定TZが
 * あるときはSSR/クライアントで同じ文字列になるためgate不要。無いときは、マウント後に
 * しか解決できないブラウザTZを使うため、マウント前は固定プレースホルダーを描く
 * (前例: ThemeSwitcher.tsx:23-58のmountedフラグ方式)。
 */
/** jsdomは既定でEventSourceを持たない(#1362実装調査で確認済み)ため、SSE系のuseEffectは
 * 何もしないまま完了する。lastUpdatedAtの更新だけを検証したいテストのために、
 * 最小限のフェイクEventSourceを用意して`status`イベントを合成的に発火させる。
 */
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

function detail(overrides: Partial<ConnectedServiceStatusDetail> = {}): ConnectedServiceStatusDetail {
  return {
    id: "1",
    name: "keycloak",
    status: "NORMAL",
    responseTimeMs: 12,
    httpStatus: 200,
    errorMessage: null,
    impact: null,
    computeDevice: null,
    targetUrl: "https://example.test/health",
    checkedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("ConnectedServiceStatusPanel", () => {
  beforeEach(() => {
    FakeEventSource.instances.length = 0;
    (global as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
  });

  afterEach(() => {
    delete (global as unknown as { EventSource?: unknown }).EventSource;
    jest.restoreAllMocks();
  });

  it("個人設定TZが設定されているとき、最終チェック時刻は個人設定TZで表示する(issue #1362、#1367)", () => {
    const d = detail();
    render(
      <ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} personalTimeZone="Asia/Tokyo" />
    );

    // 実物の ViewerDateTime が、オフセット無し(UTC)の 20:03:35 を個人設定TZ(+9)の壁時計で出す。
    expect(screen.getByText("2026/9/9 5:03:35")).toBeInTheDocument();
  });

  // 「マウント前は固定プレースホルダーを表示する」は
  // ConnectedServiceStatusPanel.mountGate.test.tsx で検証する(このファイルで検証しない
  // 理由は同ファイルの先頭コメント参照)。

  it("個人設定TZが未設定のとき、最終チェック時刻はブラウザ既定のTZで表示する(issue #1362、#1367)", () => {
    const d = detail();
    render(<ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} personalTimeZone={null} />);

    // マウント後はブラウザ既定のTZ(jest ではUTC)の壁時計で出す。
    expect(screen.getByText("2026/9/8 20:03:35")).toBeInTheDocument();
  });

  // issue #1397: 管理者向け詳細診断(initialDetail)に載る演算デバイスを、サービスの行に表示する。
  describe("演算デバイスの表示(issue #1397)", () => {
    const statuses = [
      { id: "comfyui", name: "ComfyUI", status: "NORMAL" as const },
      { id: "llm", name: "LLM", status: "NORMAL" as const },
      { id: "plantuml", name: "PlantUML", status: "NORMAL" as const },
    ];

    it("詳細診断にcomputeDeviceがあるサービスの行に「演算デバイス: <値>」を表示する", () => {
      render(
        <ConnectedServiceStatusPanel
          initialStatuses={statuses}
          initialDetail={[detail({ id: "comfyui", computeDevice: "cuda" })]}
          personalTimeZone="Asia/Tokyo"
        />
      );

      expect(screen.getByText("演算デバイス: cuda")).toBeInTheDocument();
    });

    it("computeDeviceが unknown のときは「不明」と表示する", () => {
      render(
        <ConnectedServiceStatusPanel
          initialStatuses={statuses}
          initialDetail={[detail({ id: "llm", computeDevice: "unknown" })]}
          personalTimeZone="Asia/Tokyo"
        />
      );

      expect(screen.getByText("演算デバイス: 不明")).toBeInTheDocument();
    });

    it("computeDeviceがnullのサービスには演算デバイスを表示しない", () => {
      render(
        <ConnectedServiceStatusPanel
          initialStatuses={statuses}
          initialDetail={[detail({ id: "comfyui", computeDevice: null })]}
          personalTimeZone="Asia/Tokyo"
        />
      );

      expect(screen.queryByText(/演算デバイス/)).not.toBeInTheDocument();
    });

    it("詳細診断(initialDetail)がnull(非admin)なら演算デバイスは表示されない", () => {
      render(
        <ConnectedServiceStatusPanel initialStatuses={statuses} initialDetail={null} personalTimeZone="Asia/Tokyo" />
      );

      expect(screen.queryByText(/演算デバイス/)).not.toBeInTheDocument();
    });
  });

  it("管理者向け詳細診断に理由があるとき、NORMALでない行にその理由を表示する(issue #1774)", () => {
    const penpot = detail({
      id: "penpot",
      name: "Penpot",
      status: "WARNING",
      httpStatus: null,
      errorMessage: "未設定(システム設定で設定してください)",
      targetUrl: null,
    });
    render(
      <ConnectedServiceStatusPanel
        initialStatuses={[{ id: "penpot", name: "Penpot", status: "WARNING" }]}
        initialDetail={[penpot]}
        personalTimeZone={null}
      />
    );

    const row = screen.getAllByText("Penpot")[0].closest("li")!;
    expect(row).toHaveTextContent("未設定(システム設定で設定してください)");
    expect(row).toHaveTextContent("警告");
  });

  it("NORMALの行は理由を持っていても行内に表示しない(issue #1774)", () => {
    const ok = detail({ id: "penpot", name: "Penpot", status: "NORMAL", errorMessage: "参考情報" });
    render(
      <ConnectedServiceStatusPanel
        initialStatuses={[{ id: "penpot", name: "Penpot", status: "NORMAL" }]}
        initialDetail={[ok]}
        personalTimeZone={null}
      />
    );

    const row = screen.getAllByText("Penpot")[0].closest("li")!;
    expect(row).not.toHaveTextContent("参考情報");
  });

  it("詳細診断に対応する行が無い(非管理者)ときは理由を表示しない(issue #1774)", () => {
    render(
      <ConnectedServiceStatusPanel
        initialStatuses={[{ id: "penpot", name: "Penpot", status: "ERROR" }]}
        initialDetail={null}
        personalTimeZone={null}
      />
    );

    const row = screen.getByText("Penpot").closest("li")!;
    expect(row).toHaveTextContent("エラー");
    expect(row.querySelectorAll("span span").length).toBe(1);
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
      const fetchMock = installFetch(() => okJson([{ id: "polled", name: "polled-service", status: "NORMAL" }]));
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);

      expect(fetchMock).not.toHaveBeenCalled();
      await tick();

      expect(fetchMock).toHaveBeenCalledWith("/api/dashboard/service-status", { cache: "no-store" });
      expect(screen.getByText("polled-service")).toBeInTheDocument();
      // 個人設定TZ(Asia/Tokyo, +9)の壁時計。固定した現在時刻 20:03:35Z から30秒進んだ 20:04:05Z → 5:04:05。
      expect(screen.getByText(/最終更新: 5:04:05/)).toBeInTheDocument();
      expect(FakeEventSource.instances).toHaveLength(0);
    });

    it("個人設定TZが未設定のときの最終更新時刻はブラウザ既定のTZ(jest ではUTC)で出す", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      installFetch(() => okJson([]));
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone={null} />);

      await tick();

      expect(screen.getByText(/最終更新: 20:04:05/)).toBeInTheDocument();
    });

    it.each([
      [401, "認証されていないため接続サービスの状態を取得できません。再ログインしてください。"],
      [403, "認証されていないため接続サービスの状態を取得できません。再ログインしてください。"],
      [500, "接続サービスの状態の取得に失敗しました(HTTP 500)。"],
    ])("取得が HTTP %i で失敗したら、握り潰さず理由を画面に出す(issue #876)", async (status, message) => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      installFetch(() => failed(status));
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);

      await tick();

      expect(screen.getByText(message)).toBeInTheDocument();
    });

    it("通信エラーでも直近の表示は維持し、通信エラーを画面に出す。次に取得できればエラーを消す", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      let call = 0;
      installFetch(() => (call++ === 0 ? Promise.reject(new Error("offline")) : okJson([])));
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);

      await tick();
      expect(screen.getByText("接続サービスの状態を取得できませんでした(通信エラー)。")).toBeInTheDocument();
      expect(screen.getByText("initial-service")).toBeInTheDocument();

      await tick();
      expect(screen.queryByText("接続サービスの状態を取得できませんでした(通信エラー)。")).not.toBeInTheDocument();
    });

    it("取得中にアンマウントされたら、結果も失敗も画面に反映しない", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const pending: Array<(v: unknown) => void> = [];
      const fetchMock = installFetch(() => new Promise((resolve) => pending.push(resolve)));
      const { unmount } = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);

      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      unmount();
      // アンマウント後に、成功・失敗(HTTP エラー・例外)の順で応答が返っても例外にならない。
      await act(async () => {
        pending[0](await okJson([{ id: "polled", name: "polled-service", status: "NORMAL" }]));
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
      const first = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
      await tick();
      first.unmount();
      await act(async () => {
        pending[0].resolve(await failed(500));
      });

      const second = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
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
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);

      const es = FakeEventSource.instances[0];
      expect(es.url).toBe("/api/dashboard/service-status/stream");
      expect(screen.queryByText("リアルタイム更新中")).not.toBeInTheDocument();
      act(() => es.dispatch("open"));
      expect(screen.getByText("リアルタイム更新中")).toBeInTheDocument();

      act(() => es.dispatch("status", [{ id: "polled", name: "polled-service", status: "NORMAL" }]));
      expect(screen.getByText("polled-service")).toBeInTheDocument();
      expect(screen.getByText(/最終更新: 5:03:35/)).toBeInTheDocument();
    });

    it("不正な status ペイロードは無視して、直前の一覧を維持する", () => {
      useFixedClock();
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
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
      render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
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
      const { unmount } = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
      const es = FakeEventSource.instances[0];

      unmount();
      expect(close).toHaveBeenCalledTimes(1);
      // アンマウント後に届いたイベント。cancelled なので状態を更新しない(警告・例外が出ない)。
      es.dispatch("open");
      es.dispatch("status", [{ id: "polled", name: "polled-service", status: "NORMAL" }]);
      es.readyState = FakeEventSource.CLOSED;
      es.onerror?.(new Event("error"));
    });

    it("ポーリング中にアンマウントするとインターバルを止める", async () => {
      useFixedClock();
      delete (global as unknown as { EventSource?: unknown }).EventSource;
      const fetchMock = installFetch(() => okJson([]));
      const { unmount } = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
      await tick();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      unmount();
      await tick(POLL_MS * 3);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("SSE が閉じてポーリングへ切り替わった後にアンマウントしても、インターバルを止める", async () => {
      useFixedClock();
      const fetchMock = installFetch(() => okJson([]));
      const { unmount } = render(<ConnectedServiceStatusPanel initialStatuses={[{ id: "init", name: "initial-service", status: "NORMAL" }]} initialDetail={null} personalTimeZone="Asia/Tokyo" />);
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
