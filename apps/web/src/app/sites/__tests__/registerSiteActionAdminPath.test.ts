/**
 * @jest-environment node
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
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { registerSiteAction } from "../actions";

/** バックエンド(gateway の向こう)の代役。受け取った本文は `calls` から読む。 */
const registerSite = jest.fn();

function form(extra: Record<string, string> = {}): FormData {
  const fd = new FormData();
  const base: Record<string, string> = {
    name: "N",
    siteKey: "k",
    cmsType: "WORDPRESS",
    baseUrl: "https://e.example.com",
    sshHost: "h",
    sshUser: "u",
    wpPath: "/w",
    sshPrivateKeyPem: "PEM",
    ...extra,
  };
  for (const [k, v] of Object.entries(base)) fd.set(k, v);
  return fd;
}

describe("registerSiteAction の管理画面パス(issue #1533)", () => {
  beforeEach(() => {
    registerSite.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "POST /api/sites": async (_url, init) => {
        try {
          return jsonResponse(await registerSite(JSON.parse(String(init.body))));
        } catch (err) {
          return textResponse(err instanceof Error ? err.message : String(err), 400);
        }
      },
    });
    registerSite.mockResolvedValue({ connectionCheckStatus: "SUCCESS" });
  });

  it("adminPath を前後の空白を除いて送る", async () => {
    await registerSiteAction({}, form({ adminPath: "  secret-login " }));
    expect(registerSite.mock.calls[0][0].adminPath).toBe("secret-login");
  });

  it("空欄の adminPath は送らない(グローバル既定を使う)", async () => {
    await registerSiteAction({}, form({ adminPath: "" }));
    expect(registerSite.mock.calls[0][0].adminPath).toBeUndefined();
  });

  it("adminPath のフィールドが無い従来の登録も成功し、送らない", async () => {
    const result = await registerSiteAction({}, form());
    expect(result.success).toBe(true);
    expect(registerSite.mock.calls[0][0].adminPath).toBeUndefined();
  });

  it("バックエンドの 400 メッセージを error として返し、成功にしない", async () => {
    registerSite.mockRejectedValue(new Error("adminPath には相対パスを指定してください"));
    const result = await registerSiteAction({}, form({ adminPath: "//evil.example.com" }));
    expect(result.error).toContain("相対パス");
    expect(result.success).toBeUndefined();
  });
});
