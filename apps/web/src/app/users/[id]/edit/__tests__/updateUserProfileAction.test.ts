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

import { updateUserProfileAction } from "../actions";
import { revalidatePath } from "next/cache";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const UPDATE = "PUT /api/users/1";
let fetchMock: jest.Mock;
/** identity-service に届いた本文(JSON)。 */
const sentBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/users/1") && init?.method === "PUT")
    .map(([, init]) => JSON.parse(String(init.body)));

function install(viewer: unknown, update: () => Response = () => jsonResponse({ id: 1 })) {
  fetchMock = installFetch({
    "GET /api/identity/me": () => jsonResponse(viewer),
    [UPDATE]: update,
  });
}

/** issue #1192: メールアドレスは管理者のときだけAPIへ送る。 */
describe("updateUserProfileAction のメールアドレス", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  function form(email: string | null): FormData {
    const formData = new FormData();
    if (email !== null) {
      formData.append("email", email);
    }
    return formData;
  }

  it("adminはemailを送信でき、一覧側も再検証される", async () => {
    resetServerRuntime(ADMIN_SESSION);
    install({ id: 999 });

    const result = await updateUserProfileAction(1, {}, form(" new@example.com "));

    expect(result).toEqual({ success: true });
    expect(sentBodies()[0].email).toBe("new@example.com");
    expect(revalidatePath).toHaveBeenCalledWith("/users");
    expect(revalidatePath).toHaveBeenCalledWith("/users/1/edit");
  });

  it("adminでも欄が空ならemailはnull(変更なし)で送る", async () => {
    resetServerRuntime(ADMIN_SESSION);
    install({ id: 999 });

    await updateUserProfileAction(1, {}, form(""));

    expect(sentBodies()[0].email).toBeNull();
  });

  it("本人(admin以外)はフォームにemailがあってもnullで送る", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });

    await updateUserProfileAction(1, {}, form("hacker@example.com"));

    expect(sentBodies()[0].email).toBeNull();
  });

  it("重複などでAPIが失敗したらそのメッセージを返す", async () => {
    resetServerRuntime(ADMIN_SESSION);
    install({ id: 999 }, () => textResponse("既に登録されています", 409));

    const result = await updateUserProfileAction(1, {}, form("dup@example.com"));

    expect(result).toEqual({ error: "APIエラー (409): 既に登録されています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗してError以外が投げられた場合は、文字列にして返す", async () => {
    resetServerRuntime(ADMIN_SESSION);
    install({ id: 999 }, () => {
      throw "network down";
    });

    const result = await updateUserProfileAction(1, {}, form("a@example.com"));

    expect(result).toEqual({ error: "network down" });
  });
});

describe("updateUserProfileAction の認可と入力", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("本人でもadminでもなければ権限エラーを返し、更新しない", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 999 });

    const result = await updateUserProfileAction(1, {}, new FormData());

    expect(result).toEqual({ error: "この操作を行う権限がありません。" });
    expect(sentBodies()).toEqual([]);
  });

  it("本人かどうかを確定できない(プロフィールを取得できない)非adminは、権限エラーにする", async () => {
    resetServerRuntime(USER_SESSION);
    install(null);

    const result = await updateUserProfileAction(1, {}, new FormData());

    expect(result).toEqual({ error: "この操作を行う権限がありません。" });
    expect(sentBodies()).toEqual([]);
  });

  it("カスタムリンクが JSON として読めなければ、更新せずにエラーを返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });
    const formData = new FormData();
    formData.append("customLinks", "{not json");

    const result = await updateUserProfileAction(1, {}, formData);

    expect(result).toEqual({ error: "カスタムリンクの解析に失敗しました。" });
    expect(sentBodies()).toEqual([]);
  });

  it("入力を前後の空白を除いて送り、空の項目は null にする(カスタムリンク未指定は空配列)", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });
    const formData = new FormData();
    formData.append("firstName", " 太郎 ");
    formData.append("lastName", "   ");
    formData.append("socialLinks.github", " https://github.com/taro ");

    await updateUserProfileAction(1, {}, formData);

    const body = sentBodies()[0];
    expect(body).toMatchObject({
      firstName: "太郎",
      lastName: null,
      displayName: null,
      customLinks: [],
      email: null,
    });
    expect(body.socialLinks.github).toBe("https://github.com/taro");
    expect(body.socialLinks.facebook).toBeNull();
    expect(Object.keys(body.socialLinks)).toHaveLength(14);
  });

  it("カスタムリンクはそのまま配列として送る", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });
    const formData = new FormData();
    formData.append("customLinks", JSON.stringify([{ label: "blog", url: "https://example.com" }]));

    await updateUserProfileAction(1, {}, formData);

    expect(sentBodies()[0].customLinks).toEqual([{ label: "blog", url: "https://example.com" }]);
  });

  it("未ログインならログイン画面へ送る", async () => {
    resetServerRuntime(null);
    install({ id: 1 });

    await expect(updateUserProfileAction(1, {}, new FormData())).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
