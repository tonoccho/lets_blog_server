/**
 * @jest-environment node
 */
/**
 * issue #1557: サイトの letsblog プラグインの導入状態の取得と再導入の Server Action。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({
  requireAdminSession: () => requireAdminSession(),
  requireSession: jest.fn(),
}));
const getLetsblogPluginStatus = jest.fn();
const installLetsblogPlugin = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  getLetsblogPluginStatus: (...a: unknown[]) => getLetsblogPluginStatus(...a),
  installLetsblogPlugin: (...a: unknown[]) => installLetsblogPlugin(...a),
}));
import { getLetsblogPluginStatusAction, installLetsblogPluginAction } from "../actions";

describe("letsblog プラグインの Server Action(issue #1557)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue(undefined);
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
    requireAdminSession.mockRejectedValue(new Error("NEXT_REDIRECT:/"));
    await expect(getLetsblogPluginStatusAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(getLetsblogPluginStatus).not.toHaveBeenCalled();
  });

  it("非 admin は再導入できず、バックエンドへ到達しない", async () => {
    requireAdminSession.mockRejectedValue(new Error("NEXT_REDIRECT:/"));
    await expect(installLetsblogPluginAction(7)).rejects.toThrow("NEXT_REDIRECT");
    expect(installLetsblogPlugin).not.toHaveBeenCalled();
  });
});
