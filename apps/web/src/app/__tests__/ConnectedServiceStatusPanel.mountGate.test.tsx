import { render, screen } from "@testing-library/react";
import type { ConnectedServiceStatusDetail } from "@/lib/apiClient";

/**
 * issue #1362: 「マウント前は固定プレースホルダーを表示する」(ThemeSwitcher.tsx:23-58と
 * 同じmountedフラグ方式)を単独のファイルで検証する。
 *
 * `ConnectedServiceStatusPanel.tsx`は`"use client"`を持ち、Next.jsのSWC変換対象になるため、
 * `jest.spyOn(React, "useEffect")`で個々のテストだけ`useEffect`を無効化する手法が効かない
 * (importが`React.useEffect`というプロパティ経由の呼び出しにならず、spyの差し替えを
 * 素通りすることを実測で確認済み)。`jest.mock("react", ...)`でモジュール自体を差し替える
 * 手法は効くが、同じファイル内の他のテスト(SSEで`lastUpdatedAt`を更新する等、実際の
 * `useEffect`実行を必要とするテスト)を巻き添えにしてしまうため、この検証だけを
 * 専用ファイルへ分離した。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CHECKED_AT"),
}));

import { ConnectedServiceStatusPanel } from "../ConnectedServiceStatusPanel";
import { formatDateTime } from "@/lib/formatDate";

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

describe("ConnectedServiceStatusPanel(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は固定プレースホルダーを表示する(ThemeSwitcher.tsx:23-58と同じmountedフラグ方式、issue #1362)", () => {
    const d = detail();
    render(<ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[d]} personalTimeZone={null} />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_CHECKED_AT")).not.toBeInTheDocument();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  });
});
