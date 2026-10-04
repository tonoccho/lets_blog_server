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

  it("個人設定TZが設定されているとき、最終更新時刻はtoLocaleTimeStringにそのTZを渡す(issue #1362)", () => {
    const spy = jest.spyOn(Date.prototype, "toLocaleTimeString").mockReturnValue("MOCKED_TIME");
    render(<ContainerStatusPanel initialStatuses={[]} personalTimeZone="Asia/Tokyo" />);

    const es = FakeEventSource.instances[0];
    act(() => {
      es.dispatch("status", []);
    });

    expect(spy).toHaveBeenCalledWith("ja-JP", { timeZone: "Asia/Tokyo" });
    expect(screen.getByText(/MOCKED_TIME/)).toBeInTheDocument();
  });

  it("個人設定TZが未設定のとき、最終更新時刻はtoLocaleTimeStringをtimeZone undefinedで呼ぶ(issue #1362)", () => {
    const spy = jest.spyOn(Date.prototype, "toLocaleTimeString").mockReturnValue("MOCKED_TIME");
    render(<ContainerStatusPanel initialStatuses={[]} personalTimeZone={null} />);

    const es = FakeEventSource.instances[0];
    act(() => {
      es.dispatch("status", []);
    });

    expect(spy).toHaveBeenCalledWith("ja-JP", { timeZone: undefined });
  });
});
