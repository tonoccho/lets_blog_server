/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
jest.mock("next/navigation", () => ({
  redirect: (p: string) => {
    throw new Error(`NEXT_REDIRECT:${p}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
const getServerSession = jest.fn();
jest.mock("next-auth", () => ({ getServerSession: (...a: unknown[]) => getServerSession(...a) }));
jest.mock("@/lib/auth", () => ({ authOptions: {} }));
import { renderToStaticMarkup } from "react-dom/server";

const api = { listAppSettings: jest.fn(), getComputeDeviceStatus: jest.fn() };
jest.mock("@/lib/apiClient", () => ({
  listAppSettings: (...a: unknown[]) => api.listAppSettings(...a),
  getComputeDeviceStatus: (...a: unknown[]) => api.getComputeDeviceStatus(...a),
}));
jest.mock("../AppSettingsPanel", () => ({ AppSettingsPanel: () => "SETTINGS_PANEL" }));
jest.mock("../ComputeDevicePanel", () => ({
  ComputeDevicePanel: ({ status }: { status: { target: string; currentDevice: string } }) =>
    `DEVICE_PANEL:${status.target}:${status.currentDevice}`,
}));
import Page from "../page";

describe("システム設定画面の演算デバイス欄(issue #1399)", () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    getServerSession.mockResolvedValue({ user: { role: "admin" } });
    api.listAppSettings.mockResolvedValue([]);
  });
  afterEach(() => errorSpy.mockRestore());

  it("管理者には「保存する設定」とは別に演算デバイス欄を表示する", async () => {
    api.getComputeDeviceStatus.mockImplementation(async (target: string) => ({
      target,
      currentDevice: target === "comfyui" ? "GPU" : "CPU",
    }));

    const html = renderToStaticMarkup(await Page());

    expect(html).toContain("SETTINGS_PANEL");
    expect(html).toContain("DEVICE_PANEL:comfyui:GPU");
    expect(api.getComputeDeviceStatus).toHaveBeenCalledWith("comfyui");
  });

  it("ComfyUIとは別にOllamaの欄も表示する(issue #1585)", async () => {
    api.getComputeDeviceStatus.mockImplementation(async (target: string) => ({
      target,
      currentDevice: target === "comfyui" ? "GPU" : "CPU",
    }));

    const html = renderToStaticMarkup(await Page());

    expect(html).toContain("DEVICE_PANEL:ollama:CPU");
    expect(api.getComputeDeviceStatus).toHaveBeenCalledWith("ollama");
  });

  it("Ollamaの状態だけ取得に失敗してもComfyUIの欄は表示し、Ollamaの通知を出す(issue #1585)", async () => {
    api.getComputeDeviceStatus.mockImplementation(async (target: string) => {
      if (target === "ollama") throw new Error("APIエラー (503): down");
      return { target, currentDevice: "GPU" };
    });

    const html = renderToStaticMarkup(await Page());

    expect(html).toContain("DEVICE_PANEL:comfyui:GPU");
    expect(html).not.toContain("DEVICE_PANEL:ollama");
    expect(html).toContain("演算デバイス(Ollama)の状態を取得できませんでした");
  });

  it("状態の取得に失敗しても通知を出し、保存する設定は表示し続ける", async () => {
    api.getComputeDeviceStatus.mockRejectedValue(new Error("APIエラー (503): down"));

    const html = renderToStaticMarkup(await Page());

    expect(html).toContain("演算デバイス(ComfyUI)の状態を取得できませんでした");
    expect(html).not.toContain("DEVICE_PANEL");
    expect(html).toContain("SETTINGS_PANEL");
  });

  it("一般ユーザーは / へリダイレクトされ、状態の取得すら行わない", async () => {
    getServerSession.mockResolvedValue({ user: { role: "user" } });

    await expect(Page()).rejects.toThrow("NEXT_REDIRECT:/");

    expect(api.getComputeDeviceStatus).not.toHaveBeenCalled();
  });
});
