/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const fetchExtensionsConnections = jest.fn();
const applyExtensionsConnections = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  fetchExtensionsConnections: (...a: unknown[]) => fetchExtensionsConnections(...a),
  applyExtensionsConnections: (...a: unknown[]) => applyExtensionsConnections(...a),
}));

import { applyExtensionsConnectionsAction, fetchExtensionsConnectionsAction } from "../extensionsImportActions";

function form(entries: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
}

describe("fetchExtensionsConnectionsAction(issue #1783)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
  });

  it("ポータルのURLを trim して経路とともに送り、候補と入力値を返す", async () => {
    const result = { candidates: [{ key: "comfyui_base_url", label: "ComfyUI", currentValue: null, candidateValue: "http://c" }], ignoredIds: ["gitlab"] };
    fetchExtensionsConnections.mockResolvedValue(result);

    const state = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "  http://lbx-portal:3100 ", route: "OTHER_HOST" }));

    expect(fetchExtensionsConnections).toHaveBeenCalledWith("http://lbx-portal:3100", "OTHER_HOST");
    expect(state).toEqual({ ...result, portalUrl: "http://lbx-portal:3100", route: "OTHER_HOST" });
  });

  it("経路が未指定・不明なら「同じ PC」として送る", async () => {
    fetchExtensionsConnections.mockResolvedValue({ candidates: [], ignoredIds: [] });

    await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p", route: "bogus" }));
    await fetchExtensionsConnectionsAction({}, new FormData());

    expect(fetchExtensionsConnections).toHaveBeenNthCalledWith(1, "http://p", "SAME_PC");
    expect(fetchExtensionsConnections).toHaveBeenNthCalledWith(2, "", "SAME_PC");
  });

  it("APIエラーはJSONの message だけを取り出し、入力値を残して候補を返さない", async () => {
    fetchExtensionsConnections.mockRejectedValue(
      new Error('APIエラー (409): {"message":"ポータルがエラーを返しました(HTTP 500)"}'),
    );

    const state = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p", route: "SAME_PC" }));

    expect(state).toEqual({ error: "ポータルがエラーを返しました(HTTP 500)", portalUrl: "http://p", route: "SAME_PC" });
  });

  it("JSONでない・message が無いAPIエラーや Error 以外はそのまま文字列にする", async () => {
    fetchExtensionsConnections.mockRejectedValueOnce(new Error("APIエラー (502): Bad Gateway"));
    fetchExtensionsConnections.mockRejectedValueOnce(new Error('APIエラー (409): {"other":1}'));
    fetchExtensionsConnections.mockRejectedValueOnce("boom");

    const first = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));
    const second = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));
    const third = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    expect(first.error).toBe("APIエラー (502): Bad Gateway");
    expect(second.error).toBe('APIエラー (409): {"other":1}');
    expect(third.error).toBe("boom");
  });
});

describe("applyExtensionsConnectionsAction(issue #1783)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
  });

  it("value: 接頭辞の項目だけを値として送り、結果を返して画面を再検証する", async () => {
    applyExtensionsConnections.mockResolvedValue({ saved: ["comfyui_base_url"], rejected: [{ key: "penpot_base_url", reason: "不可" }] });

    const state = await applyExtensionsConnectionsAction(
      {},
      form({
        portalUrl: " http://lbx-portal:3100 ",
        "value:comfyui_base_url": "http://lbx-comfyui:8188",
        "value:penpot_base_url": "http://127.0.0.1:3000",
        "$ACTION_REF_1": "ignored",
        other: "ignored",
      }),
    );

    expect(applyExtensionsConnections).toHaveBeenCalledWith("http://lbx-portal:3100", {
      comfyui_base_url: "http://lbx-comfyui:8188",
      penpot_base_url: "http://127.0.0.1:3000",
    });
    expect(state).toEqual({ saved: ["comfyui_base_url"], rejected: [{ key: "penpot_base_url", reason: "不可" }] });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("APIが失敗したらエラーを返し、再検証しない", async () => {
    applyExtensionsConnections.mockRejectedValue(new Error('APIエラー (409): {"message":"不正"}'));

    const state = await applyExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    expect(state).toEqual({ error: "不正" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
