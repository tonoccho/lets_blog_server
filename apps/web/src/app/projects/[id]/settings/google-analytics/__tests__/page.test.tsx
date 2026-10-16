/**
 * @jest-environment node
 */
/**
 * issue #1231: GA設定ページ(サーバーコンポーネント)。連携済みのときだけプロパティ一覧を取得し、
 * 取得に失敗しても画面全体は落とさず理由を出す。
 */
// 自前の @/lib/session・@/lib/apiClient・Breadcrumb・ProjectGoogleAnalyticsSettingsForm は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ProjectGoogleAnalyticsSettingsPage from "../page";

const status = {
  configured: false,
  propertyId: null,
  clientId: "cid",
  hasClientSecret: true,
  connected: false,
};

let getProject: () => Response;
let getStatus: () => Response;
let listProperties: () => Response;
let propertyRequests: number;

async function renderPage(searchParams: { connected?: string; error?: string } = {}) {
  return renderToStaticMarkup(
    await ProjectGoogleAnalyticsSettingsPage({
      params: Promise.resolve({ id: "5" }),
      searchParams: Promise.resolve(searchParams),
    })
  );
}

describe("ProjectGoogleAnalyticsSettingsPage", () => {
  beforeEach(() => {
    propertyRequests = 0;
    resetServerRuntime(ADMIN_SESSION);
    getProject = () => jsonResponse({ id: 5, name: "テストプロジェクト" });
    getStatus = () => jsonResponse(status);
    listProperties = () => jsonResponse([]);
    installFetch({
      "GET /api/projects/:id": () => getProject(),
      "GET /api/projects/:id/api-keys/google-analytics": () => getStatus(),
      "GET /api/projects/:id/api-keys/google-analytics/properties": () => {
        propertyRequests += 1;
        return listProperties();
      },
    });
  });

  it("未連携ならプロパティ一覧を取得せず、状態をフォームへ渡す", async () => {
    const html = await renderPage();

    expect(html).toContain("テストプロジェクト — Google Analytics設定");
    expect(propertyRequests).toBe(0);
    expect(html).toContain('value="cid"');
    expect(html).toContain("設定済み(変更する場合のみ入力)");
    expect(html).not.toContain("GA4プロパティの選択");
  });

  it("連携済みならプロパティ一覧を取得してフォームへ渡す", async () => {
    getStatus = () => jsonResponse({ ...status, connected: true });
    listProperties = () => jsonResponse([{ propertyId: "1", displayName: "A", accountDisplayName: "Acc" }]);

    const html = await renderPage({ connected: "1" });

    expect(propertyRequests).toBe(1);
    expect(html).toContain("GA4プロパティの選択");
    expect(html).toContain("A (1 / Acc)");
    expect(html).toContain("Googleアカウントとの連携が完了しました。");
  });

  it("プロパティ一覧の取得に失敗したら理由をフォームへ渡す(取得失敗の応答も、通信そのものの失敗も文字列にする)", async () => {
    getStatus = () => jsonResponse({ ...status, connected: true });
    listProperties = () => textResponse("拒否されました", 403);
    const first = await renderPage({ error: "access_denied" });
    expect(first).toContain("プロパティ一覧を取得できませんでした: APIエラー (403): 拒否されました");
    expect(first).toContain("連携に失敗しました: access_denied");

    const inner = global.fetch as jest.Mock;
    global.fetch = jest.fn(async (input: unknown, init?: RequestInit) => {
      if (String(input).endsWith("/properties")) throw "失敗";
      return inner(input, init);
    }) as unknown as typeof fetch;
    expect(await renderPage()).toContain("プロパティ一覧を取得できませんでした: 失敗");
  });

  it("状態を取得できなければ未設定として扱う", async () => {
    getStatus = () => textResponse("down", 503);

    const html = await renderPage();

    expect(html).not.toContain('value="cid"');
    expect(html).toContain("未設定</span>");
    expect(html).not.toContain("GA4プロパティの選択");
  });

  it("プロジェクトが無ければ404", async () => {
    getProject = () => textResponse("not found", 404);

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(renderPage()).rejects.toThrow("NEXT_REDIRECT:/");
  });
});
