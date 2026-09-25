import { act, render, screen } from "@testing-library/react";
import { ConnectedServiceStatusPanel } from "../ConnectedServiceStatusPanel";
import { formatDateTime } from "@/lib/formatDate";
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
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CHECKED_AT"),
}));

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
    targetUrl: "https://example.test/health",
    checkedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("ConnectedServiceStatusPanel", () => {
  beforeEach(() => {
    FakeEventSource.instances.length = 0;
    (global as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
    (formatDateTime as jest.Mock).mockClear();
  });

  afterEach(() => {
    delete (global as unknown as { EventSource?: unknown }).EventSource;
    jest.restoreAllMocks();
  });

  it("個人設定TZが設定されているとき、最終チェック時刻はformatDateTimeにそのTZを渡す(issue #1362、gateなし)", () => {
    const d = detail();
    render(
      <ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} personalTimeZone="Asia/Tokyo" />
    );

    expect(formatDateTime).toHaveBeenCalledWith(d.checkedAt, "Asia/Tokyo");
    expect(screen.getByText("FORMATTED_CHECKED_AT")).toBeInTheDocument();
  });

  // 「マウント前は固定プレースホルダーを表示する」は
  // ConnectedServiceStatusPanel.mountGate.test.tsx で検証する(このファイルで検証しない
  // 理由は同ファイルの先頭コメント参照)。

  it("個人設定TZが未設定のとき、マウント後はformatDateTimeをTZ引数無しで呼ぶ(issue #1362)", () => {
    const d = detail();
    render(<ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} personalTimeZone={null} />);

    expect(formatDateTime).toHaveBeenCalledWith(d.checkedAt);
    expect(screen.getByText("FORMATTED_CHECKED_AT")).toBeInTheDocument();
  });

  it("個人設定TZが設定されているとき、最終更新時刻はtoLocaleTimeStringにそのTZを渡す(issue #1362)", () => {
    const spy = jest.spyOn(Date.prototype, "toLocaleTimeString").mockReturnValue("MOCKED_TIME");
    render(
      <ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={null} personalTimeZone="Asia/Tokyo" />
    );

    const es = FakeEventSource.instances[0];
    act(() => {
      es.dispatch("status", []);
    });

    expect(spy).toHaveBeenCalledWith("ja-JP", { timeZone: "Asia/Tokyo" });
    expect(screen.getByText(/MOCKED_TIME/)).toBeInTheDocument();
  });

  it("個人設定TZが未設定のとき、最終更新時刻はtoLocaleTimeStringをtimeZone undefinedで呼ぶ(issue #1362)", () => {
    const spy = jest.spyOn(Date.prototype, "toLocaleTimeString").mockReturnValue("MOCKED_TIME");
    render(<ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={null} personalTimeZone={null} />);

    const es = FakeEventSource.instances[0];
    act(() => {
      es.dispatch("status", []);
    });

    expect(spy).toHaveBeenCalledWith("ja-JP", { timeZone: undefined });
  });
});
