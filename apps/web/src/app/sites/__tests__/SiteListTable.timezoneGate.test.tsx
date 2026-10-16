import { render, screen } from "@testing-library/react";
import type { Site } from "@/lib/apiClient";

/**
 * issue #1363: `SiteListTable.test.tsx`(既存)は常に`timezone="Asia/Tokyo"`で
 * レンダリングしており、個人設定TZが設定されているとき(gateなし)の分岐は既にカバー
 * されている。ここで追加するのは、個人設定TZが未設定・マウント後(ブラウザTZへ
 * フォールバック)の分岐(#1362のConnectedServiceStatusPanel.test.tsxと同じ役割分担。
 * 「マウント前」は SiteListTable.mountGate.test.tsx が担う)。
 *
 * 自前の formatDate・DeleteSiteButton・CheckConnectionButton は差し替えず、実物の整形結果を見る(#1834)。
 */
jest.mock("server-only", () => ({}))
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule)
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule)
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule)
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule)
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))

import { SiteListTable } from "../SiteListTable";

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
  it("マウント後はTZ指定無しで整形する(ブラウザTZへフォールバック。テストはUTC)", () => {
    render(<SiteListTable sites={[site()]} projects={[]} isAdmin={false} timezone={null} adminPath="wp-admin" />);

    expect(screen.getByText("2026/9/8 20:03:35")).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });
});
