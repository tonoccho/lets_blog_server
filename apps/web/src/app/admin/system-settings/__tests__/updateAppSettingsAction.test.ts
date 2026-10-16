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
import { updateAppSettingsAction } from "../actions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const UPDATE = "PUT /api/system-settings/app-settings";
let fetchMock: jest.Mock;
const sentBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/system-settings/app-settings") && init?.method === "PUT")
    .map(([, init]) => JSON.parse(String(init.body)));

describe("updateAppSettingsAction(issue #403 / #444)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [UPDATE]: () => jsonResponse(null) });
  });

  it("値を trim して送り、\"$\"始まりの内部制御フィールドは送らず、画面を再検証する", async () => {
    const data = new FormData();
    data.set("a", "  x  ");
    data.set("$ACTION_REF_1", "ignored");

    const result = await updateAppSettingsAction({}, data);

    expect(result).toEqual({ success: true });
    expect(sentBodies()).toEqual([{ a: "x" }]);
    expect(revalidatePath).toHaveBeenCalledWith("/admin/system-settings");
  });

  it("APIが拒否したらそのメッセージを返し、再検証しない", async () => {
    installFetch({ [UPDATE]: () => textResponse("不正な値", 409) });
    expect(await updateAppSettingsAction({}, new FormData())).toEqual({ error: "APIエラー (409): 不正な値" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗して Error 以外が投げられたら文字列化して返す", async () => {
    installFetch({
      [UPDATE]: () => {
        throw "boom";
      },
    });
    expect(await updateAppSettingsAction({}, new FormData())).toEqual({ error: "boom" });
  });

  it("管理者でなければトップへ送り、何も保存しない", async () => {
    resetServerRuntime(USER_SESSION);
    await expect(updateAppSettingsAction({}, new FormData())).rejects.toThrow("NEXT_REDIRECT:/");
    expect(sentBodies()).toEqual([]);
  });
});
