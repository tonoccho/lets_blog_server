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
import {
  activatePluginRegistrationAction,
  deletePluginRegistrationAction,
  savePluginRegistrationAction,
} from "../pluginRegistrationActions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const BASE = "/api/system-settings/plugin-registrations";
let fetchMock: jest.Mock;
/** gateway へ送られた「<METHOD> <パス>」と本文(JSON)。 */
const sent = () =>
  fetchMock.mock.calls
    .filter(([url]) => String(url).includes(BASE))
    .map(([url, init]) => ({
      request: `${init?.method ?? "GET"} ${String(url).replace(/^https?:\/\/[^/]+/, "")}`,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    }));

function form(entries: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
}

function installOk() {
  fetchMock = installFetch({
    [`POST ${BASE}`]: () => jsonResponse({}, 201),
    [`PUT ${BASE}/:id`]: () => jsonResponse({}),
    [`DELETE ${BASE}/:id`]: () => jsonResponse(null),
    [`PUT ${BASE}/:id/in-use`]: () => jsonResponse({}),
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  installOk();
});

describe("savePluginRegistrationAction(issue #1807)", () => {
  it("id が無ければ追加。値は trim し、空の drawioUrl は null にして、画面を再検証する", async () => {
    const state = await savePluginRegistrationAction(
      {},
      form({ kind: "LLM", name: " 社内 ", url: " http://lbx-ollama:11434/v1 ", drawioUrl: " " }),
    );

    expect(sent()).toEqual([
      { request: `POST ${BASE}`, body: { kind: "LLM", name: "社内", url: "http://lbx-ollama:11434/v1", drawioUrl: null } },
    ]);
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("drawioUrl があれば送る", async () => {
    await savePluginRegistrationAction(
      {},
      form({ kind: "DIAGRAM_RENDERER", name: "図", url: "http://lbx-plantuml:8085", drawioUrl: "https://d.example/" }),
    );

    expect(sent()[0].body).toMatchObject({ drawioUrl: "https://d.example/" });
  });

  it("id があれば編集(種類は送らない)", async () => {
    const state = await savePluginRegistrationAction(
      {},
      form({ id: "7", kind: "LLM", name: "新", url: "http://lbx-ollama:2" }),
    );

    expect(sent()).toEqual([
      { request: `PUT ${BASE}/7`, body: { name: "新", url: "http://lbx-ollama:2", drawioUrl: null } },
    ]);
    expect(state).toEqual({ saved: true });
  });

  it("フォームの項目が無くても空文字として扱う", async () => {
    await savePluginRegistrationAction({}, new FormData());

    expect(sent()[0].body).toEqual({ kind: "", name: "", url: "", drawioUrl: null });
  });

  it("APIの拒否はJSONの message(理由)だけを返し、再検証しない", async () => {
    installFetch({
      [`POST ${BASE}`]: () => textResponse('{"message":"接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています"}', 409),
    });

    const state = await savePluginRegistrationAction({}, form({ kind: "LLM", name: "a", url: "http://127.0.0.1" }));

    expect(state).toEqual({ error: "接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("JSONでない・message が無い・message が文字列でない・Error 以外のエラーはそのまま文字列にする", async () => {
    installFetch({ [`POST ${BASE}`]: () => textResponse("Bad Gateway", 502) });
    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe("APIエラー (502): Bad Gateway");

    installFetch({ [`POST ${BASE}`]: () => textResponse('{"other":1}', 409) });
    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe('APIエラー (409): {"other":1}');

    installFetch({
      [`POST ${BASE}`]: () => {
        throw "boom";
      },
    });
    expect((await savePluginRegistrationAction({}, form({ kind: "LLM" }))).error).toBe("boom");
  });

  it("管理者でなければトップへ送り、何も保存しない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(savePluginRegistrationAction({}, form({ kind: "LLM" }))).rejects.toThrow("NEXT_REDIRECT:/");
    expect(sent()).toEqual([]);
  });
});

describe("deletePluginRegistrationAction / activatePluginRegistrationAction(issue #1807)", () => {
  it("削除は id を送り、画面を再検証する", async () => {
    const state = await deletePluginRegistrationAction({}, form({ id: "3" }));

    expect(sent()).toEqual([{ request: `DELETE ${BASE}/3`, body: null }]);
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("削除が失敗したらエラーを返す", async () => {
    installFetch({ [`DELETE ${BASE}/3`]: () => textResponse('{"message":"見つかりません"}', 409) });

    expect(await deletePluginRegistrationAction({}, form({ id: "3" }))).toEqual({ error: "見つかりません" });
  });

  it("使用中の切り替えは id を送り、画面を再検証する", async () => {
    const state = await activatePluginRegistrationAction({}, form({ id: "4" }));

    expect(sent()).toEqual([{ request: `PUT ${BASE}/4/in-use`, body: null }]);
    expect(state).toEqual({ saved: true });
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("使用中の切り替えが失敗したらエラーを返す", async () => {
    installFetch({ [`PUT ${BASE}/4/in-use`]: () => textResponse('{"message":"使用中は無い"}', 409) });

    expect(await activatePluginRegistrationAction({}, form({ id: "4" }))).toEqual({ error: "使用中は無い" });
  });

  it("管理者でなければトップへ送り、削除も切り替えもしない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(deletePluginRegistrationAction({}, form({ id: "3" }))).rejects.toThrow("NEXT_REDIRECT:/");
    await expect(activatePluginRegistrationAction({}, form({ id: "4" }))).rejects.toThrow("NEXT_REDIRECT:/");
    expect(sent()).toEqual([]);
  });
});
