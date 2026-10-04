/**
 * @jest-environment node
 */
/**
 * issue #1558: サイトの letsblog プラグインへの同期状態の取得と再同期の Server Action。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({
  requireAdminSession: () => requireAdminSession(),
  requireSession: jest.fn(),
}));
const getLetsblogSync = jest.fn();
const resyncLetsblog = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  getLetsblogSync: (...a: unknown[]) => getLetsblogSync(...a),
  resyncLetsblog: (...a: unknown[]) => resyncLetsblog(...a),
}));
import { getLetsblogSyncAction, resyncLetsblogAction } from "../actions";

describe("letsblog 同期の Server Action(issue #1558)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue(undefined);
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
    requireAdminSession.mockRejectedValue(new Error("NEXT_REDIRECT:/"));
    await expect(getLetsblogSyncAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(getLetsblogSync).not.toHaveBeenCalled();
  });

  it("非 admin は再同期できず、バックエンドへ到達しない", async () => {
    requireAdminSession.mockRejectedValue(new Error("NEXT_REDIRECT:/"));
    await expect(resyncLetsblogAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(resyncLetsblog).not.toHaveBeenCalled();
  });
});
