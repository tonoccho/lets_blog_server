/**
 * @jest-environment node
 */
/**
 * issue #1557: サイトの letsblog プラグインの導入状態の取得と再導入の Server Action。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
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
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installFetch, jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { getLetsblogPluginStatusAction, installLetsblogPluginAction } from "../actions";

/** バックエンド(gateway の向こう)の代役。 */
const getLetsblogPluginStatus = jest.fn();
const installLetsblogPlugin = jest.fn();

function setup(session: unknown) {
  resetServerRuntime(session);
  getLetsblogPluginStatus.mockReset();
  installLetsblogPlugin.mockReset();
  installFetch({
    "GET /api/sites/:id/letsblog-plugin": async (_u, _i, [id]) => jsonResponse(await getLetsblogPluginStatus(Number(id))),
    "POST /api/sites/:id/letsblog-plugin/install": async (_u, _i, [id]) => jsonResponse(await installLetsblogPlugin(Number(id))),
  });
}

describe("letsblog プラグインの Server Action(issue #1557)", () => {
  beforeEach(() => {
    setup(ADMIN_SESSION);
  });

  it("導入状態を取得して返す", async () => {
    const status = { state: "INSTALLED", version: "1.0.0", protocolVersion: 1 };
    getLetsblogPluginStatus.mockResolvedValue(status);
    await expect(getLetsblogPluginStatusAction(7)).resolves.toEqual(status);
    expect(getLetsblogPluginStatus).toHaveBeenCalledWith(7);
  });

  it("再導入すると導入後の状態を返す", async () => {
    const status = { state: "INSTALLED", version: "1.0.0", protocolVersion: 1 };
    installLetsblogPlugin.mockResolvedValue(status);
    await expect(installLetsblogPluginAction(7)).resolves.toEqual(status);
    expect(installLetsblogPlugin).toHaveBeenCalledWith(7);
  });

  it("非 admin は状態を取得できず、バックエンドへ到達しない", async () => {
    setup(USER_SESSION);
    await expect(getLetsblogPluginStatusAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(getLetsblogPluginStatus).not.toHaveBeenCalled();
  });

  it("非 admin は再導入できず、バックエンドへ到達しない", async () => {
    setup(USER_SESSION);
    await expect(installLetsblogPluginAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(installLetsblogPlugin).not.toHaveBeenCalled();
  });
});
