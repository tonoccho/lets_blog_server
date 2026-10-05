/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const applyComputeDevice = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  applyComputeDevice: (...a: unknown[]) => applyComputeDevice(...a),
  updateAppSettings: jest.fn(),
}));

import { applyComputeDeviceAction } from "../actions";

function form(device?: string, target: string | null = "comfyui"): FormData {
  const data = new FormData();
  if (device !== undefined) data.set("device", device);
  if (target !== null) data.set("target", target);
  return data;
}

describe("applyComputeDeviceAction(issue #1399)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
  });

  it("管理者セッションを確認してからAPIへ適用を依頼し、画面を再検証する", async () => {
    applyComputeDevice.mockResolvedValue({});

    const result = await applyComputeDeviceAction({}, form("CPU"));

    expect(result).toEqual({ success: true });
    expect(requireAdminSession).toHaveBeenCalled();
    expect(applyComputeDevice).toHaveBeenCalledWith("CPU", "comfyui");
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("GPUも受け付ける", async () => {
    applyComputeDevice.mockResolvedValue({});

    await applyComputeDeviceAction({}, form("GPU"));

    expect(applyComputeDevice).toHaveBeenCalledWith("GPU", "comfyui");
  });

  it("管理者でなければAPIを呼ばない(requireAdminSessionが投げる)", async () => {
    requireAdminSession.mockRejectedValue(new Error("NEXT_REDIRECT:/"));

    await expect(applyComputeDeviceAction({}, form("CPU"))).rejects.toThrow("NEXT_REDIRECT:/");

    expect(applyComputeDevice).not.toHaveBeenCalled();
  });

  it("GPU/CPU以外の値や未指定はAPIを呼ばずにエラーにする", async () => {
    for (const bad of [form("NPU"), form(), form("")]) {
      const result = await applyComputeDeviceAction({}, bad);
      expect(result.error).toContain("GPU か CPU");
    }

    expect(applyComputeDevice).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("APIが拒否したら理由を返し、再検証はしない", async () => {
    applyComputeDevice.mockRejectedValue(new Error("APIエラー (409): 適用中です"));

    const result = await applyComputeDeviceAction({}, form("CPU"));

    expect(result).toEqual({ error: "APIエラー (409): 適用中です" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error以外が投げられても文字列にして返す", async () => {
    applyComputeDevice.mockRejectedValue("boom");

    expect(await applyComputeDeviceAction({}, form("CPU"))).toEqual({ error: "boom" });
  });

  it("Ollamaを対象にした適用は対象をAPIへ渡す(issue #1585)", async () => {
    applyComputeDevice.mockResolvedValue({});

    const result = await applyComputeDeviceAction({}, form("CPU", "ollama"));

    expect(result).toEqual({ success: true });
    expect(applyComputeDevice).toHaveBeenCalledWith("CPU", "ollama");
  });

  it("未知の対象や未指定はAPIを呼ばずにエラーにする(issue #1585)", async () => {
    for (const bad of [form("CPU", "other"), form("CPU", null), form("CPU", "")]) {
      const result = await applyComputeDeviceAction({}, bad);
      expect(result.error).toContain("切り替える対象");
    }

    expect(applyComputeDevice).not.toHaveBeenCalled();
  });
});
