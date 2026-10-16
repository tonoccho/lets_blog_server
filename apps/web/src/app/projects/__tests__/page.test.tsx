/**
 * @jest-environment node
 */

/**
 * issue #1364(親issue #1261 分割C): `/projects` の作成日セルはクライアント部品 `ViewerDateTime` で描く。
 * ページが閲覧者の個人設定タイムゾーン(identity-service の `/api/identity/me`)と、各行の `createdAt` を
 * そのまま渡して、表示が個人設定のタイムゾーンで整形されることを、描画結果で確かめる。
 * 自前のモジュールは差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import ProjectsPage from "../page";

function project(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 1,
    name: "テストプロジェクト",
    slug: "test-project",
    localSite: null,
    testSite: null,
    productionSite: null,
    createdAt: "2026-09-08T20:03:35Z",
    ...overrides,
  };
}

describe("/projects の作成日セルとTZ受け渡し(issue #1364)", () => {
  let profile: () => Response;
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    profile = () => jsonResponse({ id: 1, timezone: "Asia/Tokyo" });
    installFetch({
      "GET /api/projects": () => jsonResponse([project()]),
      "GET /api/identity/me": () => profile(),
    });
  });

  it("個人設定のタイムゾーンがあれば、その時刻で作成日を表示する(UTC の 20:03 は東京の翌 05:03)", async () => {
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain("2026/9/9 5:03:35");
    expect(html).not.toContain(TIMEZONE_PENDING_PLACEHOLDER);
  });

  it("個人設定のタイムゾーンが無ければ、マウント前の固定文字列を出す", async () => {
    profile = () => jsonResponse({ id: 1, timezone: null });
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain(TIMEZONE_PENDING_PLACEHOLDER);
  });

  it("プロフィールを取得できなくても、一覧は描画する(タイムゾーンは未設定扱い)", async () => {
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    profile = () => textResponse("Service Unavailable", 503);
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain("テストプロジェクト");
    expect(html).toContain(TIMEZONE_PENDING_PLACEHOLDER);
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("環境が紐付いたプロジェクトでは、紐付いた環境のバッジに色が付く", async () => {
    installFetch({
      "GET /api/projects": () =>
        jsonResponse([project({ localSite: { id: 1 }, testSite: { id: 2 }, productionSite: { id: 3 } }), project({ id: 2 })]),
      "GET /api/identity/me": () => jsonResponse({ id: 1, timezone: "UTC" }),
    });
    const html = renderToStaticMarkup(await ProjectsPage());
    expect(html).toContain("bg-blue-100");
    expect(html).toContain("bg-amber-100");
    expect(html).toContain("bg-green-100");
    expect(html).toContain('href="/projects/1/plan"');
    expect(html).toContain('href="/projects/2/tags"');
  });
});
