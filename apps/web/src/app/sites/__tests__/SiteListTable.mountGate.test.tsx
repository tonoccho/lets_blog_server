import { renderToString } from "react-dom/server";
import type { Site } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)をこのファイル単独で検証する。
 *
 * マウント前の状態は、`react` の `useEffect` や `@/lib/formatDate` を差し替えて作らず、
 * サーバー描画(`renderToString`。effect は走らない)で作る。実際にサーバーが返す HTML と同じ状態を見る(#1834)。
 * 自前の DeleteSiteButton・CheckConnectionButton・Server Action は差し替えない。差し替えるのは
 * Next.js の実行基盤だけ。
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

describe("SiteListTable(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は登録日に固定プレースホルダーを表示する(#1362と同じmountedフラグ方式、issue #1363)", () => {
    const html = renderToString(
      <SiteListTable sites={[site()]} projects={[]} isAdmin={false} timezone={null} adminPath="wp-admin" />,
    );

    expect(html).not.toContain("2026/9/");
    expect(html).toContain("読み込み中…");
  });
});
