/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const api = {
  createPluginRegistration: jest.fn(),
  updatePluginRegistration: jest.fn(),
  deletePluginRegistration: jest.fn(),
  activatePluginRegistration: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  createPluginRegistration: (...a: unknown[]) => api.createPluginRegistration(...a),
  updatePluginRegistration: (...a: unknown[]) => api.updatePluginRegistration(...a),
  deletePluginRegistration: (...a: unknown[]) => api.deletePluginRegistration(...a),
  activatePluginRegistration: (...a: unknown[]) => api.activatePluginRegistration(...a),
}));

import {
  activatePluginRegistrationAction,
  deletePluginRegistrationAction,
  savePluginRegistrationAction,
} from "../pluginRegistrationActions";

function form(entries: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
}

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
});

describe("savePluginRegistrationAction(issue #1807)", () => {
  it("id が無ければ追加。値は trim し、空の drawioUrl は null にして、画面を再検証する", async () => {
    api.createPluginRegistration.mockResolvedValue({});

    const state = await savePluginRegistrationAction(
      {},
      form({ kind: "LLM", name: " 社内 ", url: " http://lbx-ollama:11434/v1 ", drawioUrl: " " }),
    );

    expect(api.createPluginRegistration).toHaveBeenCalledWith({
      kind: "LLM", name: "社内", url: "http://lbx-ollama:11434/v1", drawioUrl: null,
    });
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("drawioUrl があれば送る", async () => {
    api.createPluginRegistration.mockResolvedValue({});

    await savePluginRegistrationAction(
      {},
      form({ kind: "DIAGRAM_RENDERER", name: "図", url: "http://lbx-plantuml:8085", drawioUrl: "https://d.example/" }),
    );

    expect(api.createPluginRegistration).toHaveBeenCalledWith(
      expect.objectContaining({ drawioUrl: "https://d.example/" }),
    );
  });

  it("id があれば編集(種類は送らない)", async () => {
    api.updatePluginRegistration.mockResolvedValue({});

    const state = await savePluginRegistrationAction(
      {},
      form({ id: "7", kind: "LLM", name: "新", url: "http://lbx-ollama:2" }),
    );

    expect(api.updatePluginRegistration).toHaveBeenCalledWith(7, { name: "新", url: "http://lbx-ollama:2", drawioUrl: null });
    expect(api.createPluginRegistration).not.toHaveBeenCalled();
    expect(state).toEqual({ saved: true });
  });

  it("フォームの項目が無くても空文字として扱う", async () => {
    api.createPluginRegistration.mockResolvedValue({});

    await savePluginRegistrationAction({}, new FormData());

    expect(api.createPluginRegistration).toHaveBeenCalledWith({ kind: "", name: "", url: "", drawioUrl: null });
  });

  it("APIの拒否はJSONの message(理由)だけを返し、再検証しない", async () => {
    api.createPluginRegistration.mockRejectedValue(
      new Error('APIエラー (409): {"message":"接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています"}'),
    );

    const state = await savePluginRegistrationAction({}, form({ kind: "LLM", name: "a", url: "http://127.0.0.1" }));

    expect(state).toEqual({ error: "接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("JSONでない・message が無い・Error 以外のエラーはそのまま文字列にする", async () => {
    api.createPluginRegistration.mockRejectedValueOnce(new Error("APIエラー (502): Bad Gateway"));
    api.createPluginRegistration.mockRejectedValueOnce(new Error('APIエラー (409): {"other":1}'));
    api.createPluginRegistration.mockRejectedValueOnce("boom");

    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe("APIエラー (502): Bad Gateway");
    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe('APIエラー (409): {"other":1}');
    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe("boom");
  });
});

describe("deletePluginRegistrationAction / activatePluginRegistrationAction(issue #1807)", () => {
  it("削除は id を送り、画面を再検証する", async () => {
    api.deletePluginRegistration.mockResolvedValue(undefined);

    const state = await deletePluginRegistrationAction({}, form({ id: "3" }));

    expect(api.deletePluginRegistration).toHaveBeenCalledWith(3);
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("削除が失敗したらエラーを返す", async () => {
    api.deletePluginRegistration.mockRejectedValue(new Error('APIエラー (409): {"message":"見つかりません"}'));

    expect(await deletePluginRegistrationAction({}, form({ id: "3" }))).toEqual({ error: "見つかりません" });
  });

  it("使用中の切り替えは id を送り、画面を再検証する", async () => {
    api.activatePluginRegistration.mockResolvedValue({});

    const state = await activatePluginRegistrationAction({}, form({ id: "4" }));

    expect(api.activatePluginRegistration).toHaveBeenCalledWith(4);
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("使用中の切り替えが失敗したらエラーを返す", async () => {
    api.activatePluginRegistration.mockRejectedValue(new Error('APIエラー (409): {"message":"使用中は無い"}'));

    expect(await activatePluginRegistrationAction({}, form({ id: "4" }))).toEqual({ error: "使用中は無い" });
  });
});
