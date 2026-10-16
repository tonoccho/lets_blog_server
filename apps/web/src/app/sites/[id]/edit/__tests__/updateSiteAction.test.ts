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
import { updateSiteAction } from "../actions";

/** バックエンド(gateway の向こう)の代役。受け取った本文は `calls` から読む。 */
const updateSite = jest.fn();

function form(entries: Record<string, string>): FormData {
  const fd = new FormData();
  for (const [k, v] of Object.entries(entries)) fd.set(k, v);
  return fd;
}

describe("updateSiteAction の管理画面パス(issue #1532)", () => {
  beforeEach(() => {
    updateSite.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "PUT /api/sites/:id": async (_url, init, [id]) => {
        try {
          return jsonResponse(await updateSite(Number(id), JSON.parse(String(init.body))));
        } catch (err) {
          if (typeof err === "string") throw err;
          return textResponse(err instanceof Error ? err.message : String(err), 400);
        }
      },
    });
    updateSite.mockResolvedValue({ connectionCheckStatus: null });
  });

  it("adminPath の値はそのまま送り、credentials には含めない", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "secret-login" }));
    expect(updateSite).toHaveBeenCalledWith(1, { name: "N", credentials: undefined, adminPath: "secret-login" });
  });

  it("空文字の adminPath は「上書き解除」として空文字のまま送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "" }));
    expect(updateSite.mock.calls[0][1].adminPath).toBe("");
  });

  it("前後の空白は取り除いて送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "  x  " }));
    expect(updateSite.mock.calls[0][1].adminPath).toBe("x");
  });

  it("adminPath のフィールドが無いときは送らない(変更しない)", async () => {
    await updateSiteAction(1, {}, form({ name: "N" }));
    expect(updateSite.mock.calls[0][1].adminPath).toBeUndefined();
  });

  it("credentials の空文字は従来どおり落とし、入力があるものだけ送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", baseUrl: "", username: "u", adminPath: "" }));
    expect(updateSite.mock.calls[0][1].credentials).toEqual({ username: "u" });
  });

  it("バックエンドの 400 メッセージを error として返す", async () => {
    updateSite.mockRejectedValue(new Error("相対パスを指定してください"));
    const result = await updateSiteAction(1, {}, form({ name: "N", adminPath: "//evil" }));
    expect(result).toEqual({ error: "APIエラー (400): 相対パスを指定してください" });
  });

  it("Error 以外が投げられても文字列化して返す", async () => {
    updateSite.mockRejectedValue("boom");
    expect(await updateSiteAction(1, {}, form({ name: "N" }))).toEqual({ error: "boom" });
  });

  it("成功時は success と疎通確認の状態を返す", async () => {
    updateSite.mockResolvedValue({ connectionCheckStatus: "SUCCESS" });
    expect(await updateSiteAction(1, {}, form({ name: "N" }))).toEqual({
      success: true,
      connectionCheckStatus: "SUCCESS",
    });
  });

  it("表示名が空・無いときは名前を送らない(変更しない)", async () => {
    await updateSiteAction(1, {}, form({ name: "  ", adminPath: "x" }));
    expect(updateSite.mock.calls[0][1]).not.toHaveProperty("name");
    await updateSiteAction(1, {}, form({ adminPath: "x" }));
    expect(updateSite.mock.calls[1][1]).not.toHaveProperty("name");
  });
});
