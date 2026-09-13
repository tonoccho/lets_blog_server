import { render, screen } from "@testing-library/react";
import { ConnectedServiceStatusPanel } from "../ConnectedServiceStatusPanel";
import { formatDateTime } from "@/lib/formatDate";
import type { ConnectedServiceStatusDetail } from "@/lib/apiClient";

/**
 * issue #1236: `new Date(detail.checkedAt).toLocaleString("ja-JP")` を直接呼んでいたため、
 * オフセット無しの日時文字列(バックエンドのLocalDateTime由来)が実行環境のTZでパースされ、
 * SSRとブラウザで表示がずれ得た。共有ヘルパ`formatDateTime`を経由するよう変更する。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CHECKED_AT"),
}));

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
  it("最終チェック時刻は共有ヘルパformatDateTime経由で表示する(issue #1236)", () => {
    const d = detail();
    render(<ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} />);

    expect(formatDateTime).toHaveBeenCalledWith(d.checkedAt);
    expect(screen.getByText("FORMATTED_CHECKED_AT")).toBeInTheDocument();
  });
});
