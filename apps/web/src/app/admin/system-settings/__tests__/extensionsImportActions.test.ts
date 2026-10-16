/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { revalidatePath } from "next/cache";
import { applyExtensionsConnectionsAction, fetchExtensionsConnectionsAction } from "../extensionsImportActions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const FETCH = "POST /api/system-settings/app-settings/extensions-import/fetch";
const APPLY = "POST /api/system-settings/app-settings/extensions-import/apply";
let fetchMock: jest.Mock;
const sentBodies = (suffix: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(suffix) && init?.method === "POST")
    .map(([, init]) => JSON.parse(String(init.body)));

function form(entries: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
}

describe("fetchExtensionsConnectionsAction(issue #1783)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [FETCH]: () => jsonResponse({ candidates: [], ignoredIds: [] }) });
  });

  it("ポータルのURLを trim して経路とともに送り、候補と入力値を返す", async () => {
    const result = { candidates: [{ key: "comfyui_base_url", label: "ComfyUI", currentValue: null, candidateValue: "http://c" }], ignoredIds: ["gitlab"] };
    fetchMock = installFetch({ [FETCH]: () => jsonResponse(result) });

    const state = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "  http://lbx-portal:3100 ", route: "OTHER_HOST" }));

    expect(sentBodies("/extensions-import/fetch")).toEqual([{ portalUrl: "http://lbx-portal:3100", route: "OTHER_HOST" }]);
    expect(state).toEqual({ ...result, portalUrl: "http://lbx-portal:3100", route: "OTHER_HOST" });
  });

  it("経路が未指定・不明なら「同じ PC」として送る", async () => {
    await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p", route: "bogus" }));
    await fetchExtensionsConnectionsAction({}, new FormData());

    expect(sentBodies("/extensions-import/fetch")).toEqual([
      { portalUrl: "http://p", route: "SAME_PC" },
      { portalUrl: "", route: "SAME_PC" },
    ]);
  });

  it("APIエラーはJSONの message だけを取り出し、入力値を残して候補を返さない", async () => {
    installFetch({ [FETCH]: () => textResponse('{"message":"ポータルがエラーを返しました(HTTP 500)"}', 409) });

    const state = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p", route: "SAME_PC" }));

    expect(state).toEqual({ error: "ポータルがエラーを返しました(HTTP 500)", portalUrl: "http://p", route: "SAME_PC" });
  });

  it("JSONでない・message が無いAPIエラーや Error 以外はそのまま文字列にする", async () => {
    installFetch({ [FETCH]: () => textResponse("Bad Gateway", 502) });
    const first = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    installFetch({ [FETCH]: () => textResponse('{"other":1}', 409) });
    const second = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    installFetch({
      [FETCH]: () => {
        throw "boom";
      },
    });
    const third = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    expect(first.error).toBe("APIエラー (502): Bad Gateway");
    expect(second.error).toBe('APIエラー (409): {"other":1}');
    expect(third.error).toBe("boom");
  });

  it("message が文字列でないJSONは、そのまま文字列にする", async () => {
    installFetch({ [FETCH]: () => textResponse('{"message":5}', 409) });

    const state = await fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    expect(state.error).toBe('APIエラー (409): {"message":5}');
  });

  it("管理者でなければトップへ送り、ポータルへ取りに行かない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(fetchExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }))).rejects.toThrow("NEXT_REDIRECT:/");
    expect(sentBodies("/extensions-import/fetch")).toEqual([]);
  });
});

describe("applyExtensionsConnectionsAction(issue #1783)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
  });

  it("value: 接頭辞の項目だけを値として送り、結果を返して画面を再検証する", async () => {
    const applied = { saved: ["comfyui_base_url"], rejected: [{ key: "penpot_base_url", reason: "不可" }] };
    fetchMock = installFetch({ [APPLY]: () => jsonResponse(applied) });

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

    expect(sentBodies("/extensions-import/apply")).toEqual([
      {
        portalUrl: "http://lbx-portal:3100",
        values: { comfyui_base_url: "http://lbx-comfyui:8188", penpot_base_url: "http://127.0.0.1:3000" },
      },
    ]);
    expect(state).toEqual(applied);
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("APIが失敗したらエラーを返し、再検証しない", async () => {
    installFetch({ [APPLY]: () => textResponse('{"message":"不正"}', 409) });

    const state = await applyExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }));

    expect(state).toEqual({ error: "不正" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("管理者でなければトップへ送り、何も保存しない", async () => {
    resetServerRuntime(USER_SESSION);
    fetchMock = installFetch({ [APPLY]: () => jsonResponse({}) });

    await expect(applyExtensionsConnectionsAction({}, form({ portalUrl: "http://p" }))).rejects.toThrow("NEXT_REDIRECT:/");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
