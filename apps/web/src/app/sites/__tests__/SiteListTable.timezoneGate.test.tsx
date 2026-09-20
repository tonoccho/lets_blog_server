import { render, screen } from "@testing-library/react";
import type { Site } from "@/lib/apiClient";

/**
 * issue #1363: `SiteListTable.test.tsx`(既存)は常に`timezone="Asia/Tokyo"`で
 * レンダリングしており、個人設定TZが設定されているとき(gateなし)の分岐は既にカバー
 * されている。ここで追加するのは、個人設定TZが未設定・マウント後(ブラウザTZへ
 * フォールバック)の分岐(#1362のConnectedServiceStatusPanel.test.tsxと同じ役割分担。
 * 「マウント前」は SiteListTable.mountGate.test.tsx が担う)。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn((iso: string, tz?: string | null) => `FORMATTED(${iso}|${tz})`),
}));

jest.mock("../DeleteSiteButton", () => ({
  DeleteSiteButton: () => <div>Delete Button</div>,
}));

jest.mock("../CheckConnectionButton", () => ({
  CheckConnectionButton: () => <button>Check Connection</button>,
}));

import { SiteListTable } from "../SiteListTable";
import { formatDateTime } from "@/lib/formatDate";

function site(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    siteKey: "test-site-1",
    name: "Test Site 1",
    baseUrl: "https://test1.example.com",
    cmsType: "WORDPRESS",
    managedWordpress: false,
    connectionCheckStatus: null,
    sshConfigured: false,
    createdAt: "2026-09-08T20:03:35",
    updatedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("SiteListTable 個人設定TZ未設定(issue #1363)", () => {
  it("マウント後はformatDateTimeをTZ引数無しで呼ぶ(ブラウザTZへフォールバック)", () => {
    const s = site();
    render(<SiteListTable sites={[s]} projects={[]} isAdmin={false} timezone={null} />);

    expect(formatDateTime).toHaveBeenCalledWith(s.createdAt);
    expect(screen.getByText(`FORMATTED(${s.createdAt}|undefined)`)).toBeInTheDocument();
  });
});
