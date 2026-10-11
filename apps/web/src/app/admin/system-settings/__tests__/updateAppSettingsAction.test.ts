/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const updateAppSettings = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  updateAppSettings: (...a: unknown[]) => updateAppSettings(...a),
}));

import { updateAppSettingsAction } from "../actions";

describe("updateAppSettingsAction(issue #403 / #444)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
  });

  it("値を trim して送り、\"$\"始まりの内部制御フィールドは送らず、画面を再検証する", async () => {
    updateAppSettings.mockResolvedValue({});
    const data = new FormData();
    data.set("a", "  x  ");
    data.set("$ACTION_REF_1", "ignored");

    const result = await updateAppSettingsAction({}, data);

    expect(result).toEqual({ success: true });
    expect(updateAppSettings).toHaveBeenCalledWith({ a: "x" });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("APIが Error で失敗したらそのメッセージを返し、再検証しない", async () => {
    updateAppSettings.mockRejectedValue(new Error("不正な値"));
    expect(await updateAppSettingsAction({}, new FormData())).toEqual({ error: "不正な値" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error 以外で失敗したら文字列化して返す", async () => {
    updateAppSettings.mockRejectedValue("boom");
    expect(await updateAppSettingsAction({}, new FormData())).toEqual({ error: "boom" });
  });
});
