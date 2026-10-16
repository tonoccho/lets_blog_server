// 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤
// (next/cache・next/headers・next/server の after・next-auth・next/navigation の redirect)と、
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock("next/cache", () => ({
  revalidatePath: jest.fn(),
}));

import { updatePreferencesAction } from "../actions";
import { revalidatePath } from "next/cache";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const PREFERENCES = "PATCH /api/identity/me/preferences";
let fetchMock: jest.Mock;
/** identity-service に届いた本文(JSON)。 */
const sentBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/identity/me/preferences") && init?.method === "PATCH")
    .map(([, init]) => JSON.parse(String(init.body)));

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。「ブラウザに従う(未設定)」を
 * 選んで保存した場合、timezoneはnullとして送られなければならない(従来は空文字を
 * 「未選択」とみなしてエラーにしていた)。
 */
describe("updatePreferencesAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(USER_SESSION);
    fetchMock = installFetch({ [PREFERENCES]: () => jsonResponse({ id: 7 }) });
  });

  function formData(locale: string, timezone: string): FormData {
    const data = new FormData();
    data.append("locale", locale);
    data.append("timezone", timezone);
    return data;
  }

  it("タイムゾーンを空(ブラウザに従う)で保存するとnullとして送られる", async () => {
    const result = await updatePreferencesAction({}, formData("ja_JP", ""));

    expect(result).toEqual({ success: true });
    expect(sentBodies()).toEqual([{ locale: "ja_JP", timezone: null }]);
    expect(revalidatePath).toHaveBeenCalledWith("/users/7/edit");
  });

  it("言語が空ならエラーを返しAPIを呼ばない", async () => {
    const result = await updatePreferencesAction({}, formData("", "Asia/Tokyo"));

    expect(result).toEqual({ error: "言語を選択してください。" });
    expect(sentBodies()).toEqual([]);
  });

  it("言語のフィールド自体が送られなくても、言語の選択を求める", async () => {
    const result = await updatePreferencesAction({}, new FormData());

    expect(result).toEqual({ error: "言語を選択してください。" });
    expect(sentBodies()).toEqual([]);
  });

  it("timezoneフィールド自体が送られなくてもnullとして扱う", async () => {
    const data = new FormData();
    data.append("locale", "ja_JP");

    const result = await updatePreferencesAction({}, data);

    expect(result).toEqual({ success: true });
    expect(sentBodies()).toEqual([{ locale: "ja_JP", timezone: null }]);
  });

  it("値のあるタイムゾーンを保存できる", async () => {
    const result = await updatePreferencesAction({}, formData("ja_JP", "Pacific/Auckland"));

    expect(result).toEqual({ success: true });
    expect(sentBodies()).toEqual([{ locale: "ja_JP", timezone: "Pacific/Auckland" }]);
  });

  it("APIが拒否した場合そのmessageを返す", async () => {
    installFetch({ [PREFERENCES]: () => textResponse("不正なタイムゾーンです", 400) });

    const result = await updatePreferencesAction({}, formData("ja_JP", "Not/AZone"));

    expect(result).toEqual({ error: "APIエラー (400): 不正なタイムゾーンです" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗してError以外が投げられた場合は、文字列にして返す", async () => {
    installFetch({
      [PREFERENCES]: () => {
        throw "network down";
      },
    });

    const result = await updatePreferencesAction({}, formData("ja_JP", ""));

    expect(result).toEqual({ error: "network down" });
  });

  it("未ログインならログイン画面へ送り、APIを呼ばない", async () => {
    resetServerRuntime(null);

    await expect(updatePreferencesAction({}, formData("ja_JP", ""))).rejects.toThrow("NEXT_REDIRECT:/login");
    expect(sentBodies()).toEqual([]);
  });
});
