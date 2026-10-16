/**
 * @jest-environment node
 */

// 自前の @/lib/apiClient・@/lib/session・SshKeyPairsPanel・next/linkは差し替えない。差し替えるのは Next.js の実行基盤と
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
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p), notFound: () => notFound() }));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
const SESSION_EXPIRED = "セッションの有効期限が切れました。お手数ですが再度ログインしてください。";
const DOWN = new Error("APIエラー (503): Service Unavailable");

/** ssh-key-service と identity-service の代役。拒否は 401(セッション切れ)か 503 の応答になる。 */
const listSshKeyPairs = jest.fn();
import AdminSshKeysPage from "../page";

describe("SSH鍵管理ページの取得失敗表示(issue #1458)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "GET /api/ssh-key-pairs": async () => {
        try {
          return jsonResponse(await listSshKeyPairs());
        } catch (err) {
          if (err instanceof Error && err.message === SESSION_EXPIRED) return textResponse("Unauthorized", 401);
          return textResponse("Service Unavailable", 503);
        }
      },
      "GET /api/identity/me": () => jsonResponse({ id: 1, timezone: null }),
    });
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    listSshKeyPairs.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("取得に失敗したとき、通知とログを出し、0件の鍵一覧を描画しない", async () => {
    listSshKeyPairs.mockRejectedValue(DOWN);
    const html = renderToStaticMarkup(await AdminSshKeysPage());
    expect(html).toContain('role="alert"');
    expect(html).toContain("SSH鍵一覧を取得できませんでした");
    expect(html).not.toContain("保存済みのSSH鍵ペア");
    expect(errorSpy).toHaveBeenCalled();
  });

  it("成功して0件のときは従来通り通知なしでパネルを描画する", async () => {
    const html = renderToStaticMarkup(await AdminSshKeysPage());
    expect(html).not.toContain('role="alert"');
    expect(html).toContain("保存済みのSSH鍵ペアはありません。");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("成功して1件以上のときは鍵ペアの名前をパネルに出す", async () => {
    listSshKeyPairs.mockResolvedValue([
      { id: 1, name: "production-deploy", comment: "本番用", publicKeyLine: "ssh-ed25519 AAAA", createdAt: "2026-09-08T20:03:35" },
    ]);
    const html = renderToStaticMarkup(await AdminSshKeysPage());
    expect(html).toContain("production-deploy");
    expect(html).toContain("本番用");
  });

  it("セッション切れは /login へ", async () => {
    listSshKeyPairs.mockRejectedValue(new Error(SESSION_EXPIRED));
    await expect(AdminSshKeysPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
