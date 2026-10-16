/**
 * @jest-environment node
 */
/**
 * issue #1558: サイトの letsblog プラグインへの同期状態の取得と再同期の Server Action。
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
import { getLetsblogSyncAction, resyncLetsblogAction } from "../actions";

/** バックエンド(gateway の向こう)の代役。 */
const getLetsblogSync = jest.fn();
const resyncLetsblog = jest.fn();

function setup(session: unknown) {
  resetServerRuntime(session);
  getLetsblogSync.mockReset();
  resyncLetsblog.mockReset();
  installFetch({
    "GET /api/sites/:id/letsblog-sync": async (_u, _i, [id]) => jsonResponse(await getLetsblogSync(Number(id))),
    "POST /api/sites/:id/letsblog-sync": async (_u, _i, [id]) => jsonResponse(await resyncLetsblog(Number(id))),
  });
}

describe("letsblog 同期の Server Action(issue #1558)", () => {
  beforeEach(() => {
    setup(ADMIN_SESSION);
  });

  it("同期状態を取得して返す", async () => {
    const state = { status: "FAILED", error: "接続できません", hash: null, syncedAt: "2026-10-04T00:00:00Z" };
    getLetsblogSync.mockResolvedValue(state);
    await expect(getLetsblogSyncAction(7)).resolves.toEqual(state);
    expect(getLetsblogSync).toHaveBeenCalledWith(7);
  });

  it("再同期すると同期後の状態を返す", async () => {
    const state = { status: "SYNCED", error: null, hash: "abc", syncedAt: "2026-10-04T00:00:00Z" };
    resyncLetsblog.mockResolvedValue(state);
    await expect(resyncLetsblogAction(7)).resolves.toEqual(state);
    expect(resyncLetsblog).toHaveBeenCalledWith(7);
  });

  it("非 admin は状態を取得できず、バックエンドへ到達しない", async () => {
    setup(USER_SESSION);
    await expect(getLetsblogSyncAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(getLetsblogSync).not.toHaveBeenCalled();
  });

  it("非 admin は再同期できず、バックエンドへ到達しない", async () => {
    setup(USER_SESSION);
    await expect(resyncLetsblogAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(resyncLetsblog).not.toHaveBeenCalled();
  });

  it("未同期(応答本文が空)なら null を返す", async () => {
    installFetch({
      "GET /api/sites/:id/letsblog-sync": () => ({ ok: true, status: 200, text: async () => "", headers: { get: () => null } }) as unknown as Response,
    });
    await expect(getLetsblogSyncAction(7)).resolves.toBeNull();
  });
});
