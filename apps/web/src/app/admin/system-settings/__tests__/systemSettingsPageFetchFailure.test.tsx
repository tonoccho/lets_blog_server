/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・各パネル・next/linkは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
const notFound = jest.fn(() => {
  throw new Error("NEXT_NOT_FOUND");
});
jest.mock("next/navigation", () => ({
  redirect: (p: string) => redirect(p),
  notFound: () => notFound(),
  useRouter: () => ({ refresh: jest.fn() }),
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** system-settings 系サービスの代役(gateway の向こう側)。拒否は 401(セッション切れ)か 503 の応答になる。 */
const api = { listAppSettings: jest.fn(), listPluginRegistrations: jest.fn() };
function respond(backend: jest.Mock) {
  return async () => {
    try {
      return jsonResponse(await backend());
    } catch (err) {
      if (err instanceof Error && err.message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
      return textResponse("Service Unavailable", 503);
    }
  };
}
import Page from "../page";

describe("システム設定の取得失敗表示(issue #1235)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    resetServerRuntime(ADMIN_SESSION);
    api.listPluginRegistrations.mockResolvedValue([]);
    installFetch({
      "GET /api/system-settings/app-settings": respond(api.listAppSettings),
      "GET /api/system-settings/plugin-registrations": respond(api.listPluginRegistrations),
    });
  });
  afterEach(() => errorSpy.mockRestore());

  it("失敗したとき通知を出し、空の設定を保存させかねない編集パネルを描画しない", async () => {
    api.listAppSettings.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await Page());
    expect(html).toContain('role="alert"');
    expect(html).toContain("システム設定を取得できませんでした");
    expect(html).not.toContain("まとめて保存");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功したときは通知なしでパネルを描画する", async () => {
    api.listAppSettings.mockResolvedValue([]);
    const html = renderToStaticMarkup(await Page());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("まとめて保存");
  });

  it("保存済みのポータルのURLを取り込み欄へ渡す(issue #1783)。無ければ空、取得失敗時は描画しない", async () => {
    api.listAppSettings.mockResolvedValue([
      { key: "extensions_portal_url", label: "x", secret: false, configured: true, source: "DATABASE", value: "http://lbx-portal:3100" },
    ]);
    expect(renderToStaticMarkup(await Page())).toContain('value="http://lbx-portal:3100"');

    api.listAppSettings.mockResolvedValue([]);
    const empty = renderToStaticMarkup(await Page());
    expect(empty).toContain("extensions から取り込む");
    expect(empty).not.toContain('value="http://lbx-portal:3100"');

    api.listAppSettings.mockRejectedValue(DOWN);
    expect(renderToStaticMarkup(await Page())).not.toContain("extensions から取り込む");
  });

  it("プラグインの登録の欄へ登録の一覧を渡す(issue #1807)。取得に失敗したら欄を描画せず通知する", async () => {
    api.listAppSettings.mockResolvedValue([]);
    api.listPluginRegistrations.mockResolvedValue([
      { id: 1, kind: "LLM", name: "社内のOllama", url: "http://lbx-ollama:11434/v1", inUse: true, drawioUrl: null },
      { id: 2, kind: "PENPOT", name: "社内のPenpot", url: "http://lbx-penpot:9001", inUse: false, drawioUrl: null },
    ]);
    const withRegistrations = renderToStaticMarkup(await Page());
    expect(withRegistrations).toContain("社内のOllama");
    expect(withRegistrations).toContain("社内のPenpot");

    api.listPluginRegistrations.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await Page());
    expect(html).not.toContain("LLM の登録");
    expect(html).toContain("プラグインの登録を取得できませんでした");
  });

  it("セッション切れは /login へ", async () => {
    api.listAppSettings.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(Page()).rejects.toThrow("NEXT_REDIRECT:/login");
  });

  it("管理者でなければトップへ送り、設定を取得しない", async () => {
    resetServerRuntime(USER_SESSION);
    await expect(Page()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(api.listAppSettings).not.toHaveBeenCalled();
  });
});
