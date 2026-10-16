/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const api = {
  listProjectPluginRegistrations: jest.fn(),
  createProjectPluginRegistration: jest.fn(),
  updateProjectPluginRegistration: jest.fn(),
  deleteProjectPluginRegistration: jest.fn(),
  activateProjectPluginRegistration: jest.fn(),
  clearProjectPluginRegistrationCredential: jest.fn(),
};
jest.mock("@/lib/apiClient", () => ({
  listProjectPluginRegistrations: (...a: unknown[]) => api.listProjectPluginRegistrations(...a),
  createProjectPluginRegistration: (...a: unknown[]) => api.createProjectPluginRegistration(...a),
  updateProjectPluginRegistration: (...a: unknown[]) => api.updateProjectPluginRegistration(...a),
  deleteProjectPluginRegistration: (...a: unknown[]) => api.deleteProjectPluginRegistration(...a),
  activateProjectPluginRegistration: (...a: unknown[]) => api.activateProjectPluginRegistration(...a),
  clearProjectPluginRegistrationCredential: (...a: unknown[]) => api.clearProjectPluginRegistrationCredential(...a),
}));

import {
  activateProjectPluginRegistrationAction,
  clearProjectPluginRegistrationCredentialAction,
  deleteProjectPluginRegistrationAction,
  fetchProjectPluginRegistrationsAction,
  saveProjectPluginRegistrationAction,
} from "../projectPluginRegistrationActions";

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
});

describe("fetchProjectPluginRegistrationsAction(issue #1808)", () => {
  it("一覧を data で返し、失敗は理由だけを error で返す", async () => {
    api.listProjectPluginRegistrations.mockResolvedValueOnce([{ id: 1 }]);
    expect(await fetchProjectPluginRegistrationsAction(10)).toEqual({ data: [{ id: 1 }] });
    expect(api.listProjectPluginRegistrations).toHaveBeenCalledWith(10);

    api.listProjectPluginRegistrations.mockRejectedValueOnce(new Error('APIエラー (403): {"message":"メンバーが必要です"}'));
    expect(await fetchProjectPluginRegistrationsAction(10)).toEqual({ error: "メンバーが必要です" });
  });
});

describe("saveProjectPluginRegistrationAction(issue #1808)", () => {
  it("id が無ければ追加。値は trim し、空の drawioUrl・認証情報は null にして画面を再検証する", async () => {
    api.createProjectPluginRegistration.mockResolvedValue({});

    const state = await saveProjectPluginRegistrationAction(10, {
      kind: "LLM", name: " 社内 ", url: " http://gpu-pc:11434/v1 ", drawioUrl: " ", credential: "  ",
    });

    expect(api.createProjectPluginRegistration).toHaveBeenCalledWith(10, {
      kind: "LLM", name: "社内", url: "http://gpu-pc:11434/v1", drawioUrl: null, credential: null,
    });
    expect(state).toEqual({});
    expect(revalidatePath).toHaveBeenCalledWith("/projects/10");
  });

  it("認証情報と drawioUrl があれば送る", async () => {
    api.createProjectPluginRegistration.mockResolvedValue({});

    await saveProjectPluginRegistrationAction(10, {
      kind: "DIAGRAM_RENDERER", name: "図", url: "http://gpu-pc:8085", drawioUrl: "https://d.example/", credential: "sk-1",
    });

    expect(api.createProjectPluginRegistration).toHaveBeenCalledWith(
      10, expect.objectContaining({ drawioUrl: "https://d.example/", credential: "sk-1" }),
    );
  });

  it("id があれば編集(種類は送らない)", async () => {
    api.updateProjectPluginRegistration.mockResolvedValue({});

    const state = await saveProjectPluginRegistrationAction(10, {
      id: 7, kind: "LLM", name: "新", url: "http://gpu-pc:2", drawioUrl: null, credential: null,
    });

    expect(api.updateProjectPluginRegistration).toHaveBeenCalledWith(10, 7, {
      name: "新", url: "http://gpu-pc:2", drawioUrl: null, credential: null,
    });
    expect(api.createProjectPluginRegistration).not.toHaveBeenCalled();
    expect(state).toEqual({});
  });

  it("APIの拒否は message(理由)だけを返し、再検証しない。JSONでないものはそのまま、Error 以外は文字列にする", async () => {
    const input = { kind: "LLM", name: "a", url: "http://lbx-ollama:1", drawioUrl: null, credential: null };
    api.createProjectPluginRegistration.mockRejectedValueOnce(
      new Error('APIエラー (400): {"message":"プロジェクト設定の「LLM の接続先」(lbx-ollama)がサーバ内部のネットワークのため"}'),
    );
    api.createProjectPluginRegistration.mockRejectedValueOnce(new Error("APIエラー (502): Bad Gateway"));
    api.createProjectPluginRegistration.mockRejectedValueOnce(new Error('APIエラー (400): {"other":1}'));
    api.createProjectPluginRegistration.mockRejectedValueOnce("boom");

    expect((await saveProjectPluginRegistrationAction(10, input)).error).toContain("サーバ内部のネットワーク");
    expect((await saveProjectPluginRegistrationAction(10, input)).error).toBe("APIエラー (502): Bad Gateway");
    expect((await saveProjectPluginRegistrationAction(10, input)).error).toBe('APIエラー (400): {"other":1}');
    expect((await saveProjectPluginRegistrationAction(10, input)).error).toBe("boom");
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

describe("delete / activate / clearCredential(issue #1808)", () => {
  it("削除・使用中の切り替え・認証情報の消去は projectId と id を送り、画面を再検証する", async () => {
    api.deleteProjectPluginRegistration.mockResolvedValue(undefined);
    api.activateProjectPluginRegistration.mockResolvedValue({});
    api.clearProjectPluginRegistrationCredential.mockResolvedValue({});

    expect(await deleteProjectPluginRegistrationAction(10, 3)).toEqual({});
    expect(await activateProjectPluginRegistrationAction(10, 4)).toEqual({});
    expect(await clearProjectPluginRegistrationCredentialAction(10, 5)).toEqual({});

    expect(api.deleteProjectPluginRegistration).toHaveBeenCalledWith(10, 3);
    expect(api.activateProjectPluginRegistration).toHaveBeenCalledWith(10, 4);
    expect(api.clearProjectPluginRegistrationCredential).toHaveBeenCalledWith(10, 5);
    expect(revalidatePath).toHaveBeenCalledWith("/projects/10");
  });

  it("失敗したらエラーを返す", async () => {
    api.deleteProjectPluginRegistration.mockRejectedValue(new Error('APIエラー (400): {"message":"見つかりません"}'));
    api.activateProjectPluginRegistration.mockRejectedValue(new Error('APIエラー (400): {"message":"使用中は無い"}'));
    api.clearProjectPluginRegistrationCredential.mockRejectedValue(new Error('APIエラー (403): {"message":"権限が無い"}'));

    expect(await deleteProjectPluginRegistrationAction(10, 3)).toEqual({ error: "見つかりません" });
    expect(await activateProjectPluginRegistrationAction(10, 4)).toEqual({ error: "使用中は無い" });
    expect(await clearProjectPluginRegistrationCredentialAction(10, 5)).toEqual({ error: "権限が無い" });
  });
});
