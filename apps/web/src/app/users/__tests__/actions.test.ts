/**
 * issue #1383: deleteUserAction は失敗を例外ではなく { error } で返す(SshKeyPairsPanel の
 * deleteSshKeyPairAction と同じ形)。投げっぱなしだと失敗の理由が画面に出ない。
 * 自己削除防止(viewer 判定)の意味は変えない。
 *
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤
 * (next/cache・next/headers・next/server の after・next-auth・next/navigation の redirect)と、
 * 外部との通信(fetch)だけ(#1833)。
 */
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
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));

import { createUserAction, deleteUserAction } from "../actions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

/** identity-service の代役(gateway の向こう側)。 */
const deleteUser = jest.fn();
const createUser = jest.fn();
const getMyProfile = jest.fn();

function installApi() {
  return installFetch({
    "DELETE /api/users/:id": async (_url, _init, [id]) => {
      try {
        return jsonResponse((await deleteUser(Number(id))) ?? null);
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 500);
      }
    },
    "POST /api/users": async (_url, init) => {
      try {
        return jsonResponse(await createUser(JSON.parse(String(init.body))), 201);
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 500);
      }
    },
    "GET /api/identity/me": async () => jsonResponse(await getMyProfile()),
  });
}

describe("deleteUserAction (issue #1383)", () => {
  beforeEach(() => {
    for (const backend of [deleteUser, createUser, getMyProfile]) backend.mockReset();
    revalidatePath.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    getMyProfile.mockResolvedValue({ id: 1 });
    deleteUser.mockResolvedValue(undefined);
    installApi();
  });

  it("成功すると削除して一覧を再検証し、errorを返さない", async () => {
    const result = await deleteUserAction(2);
    expect(deleteUser).toHaveBeenCalledWith(2);
    expect(revalidatePath).toHaveBeenCalledWith("/users");
    expect(result.error).toBeUndefined();
  });

  it("ログイン中ユーザーを取得できないときは削除せずerrorを返す(フェイルクローズ)", async () => {
    getMyProfile.mockResolvedValue(null);
    const result = await deleteUserAction(2);
    expect(deleteUser).not.toHaveBeenCalled();
    expect(result.error).toContain("ログイン中のユーザー情報を取得できなかった");
  });

  it("自分自身の削除は拒否してerrorを返す", async () => {
    const result = await deleteUserAction(1);
    expect(deleteUser).not.toHaveBeenCalled();
    expect(result.error).toBe("自分自身のアカウントは削除できません。");
  });

  it("APIエラーはerrorとして返す", async () => {
    deleteUser.mockRejectedValueOnce(new Error("internal"));
    expect((await deleteUserAction(2)).error).toBe("APIエラー (500): internal");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗したときも、Errorでない値を文字列にしてerrorとして返す", async () => {
    installFetch({
      "GET /api/identity/me": async () => jsonResponse({ id: 1 }),
      "DELETE /api/users/2": () => {
        throw "boom";
      },
    });
    expect((await deleteUserAction(2)).error).toBe("boom");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("管理者でなければトップへ送り、何も削除しない", async () => {
    resetServerRuntime(USER_SESSION);
    await expect(deleteUserAction(2)).rejects.toThrow("NEXT_REDIRECT:/");
    expect(deleteUser).not.toHaveBeenCalled();
  });

  it("未ログインならログイン画面へ送る", async () => {
    resetServerRuntime(null);
    await expect(deleteUserAction(2)).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});

describe("createUserAction", () => {
  function form(fields: Record<string, string>): FormData {
    const data = new FormData();
    for (const [key, value] of Object.entries(fields)) data.set(key, value);
    return data;
  }

  beforeEach(() => {
    for (const backend of [deleteUser, createUser, getMyProfile]) backend.mockReset();
    revalidatePath.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    createUser.mockResolvedValue({ id: 5 });
    installApi();
  });

  it("前後の空白を除いてユーザーを作り、一覧を再検証して成功を返す", async () => {
    const result = await createUserAction({}, form({ email: " a@example.com ", password: " pass1234 ", role: " admin " }));

    expect(result).toEqual({ success: true });
    expect(createUser).toHaveBeenCalledWith({ email: "a@example.com", password: "pass1234", role: "admin" });
    expect(revalidatePath).toHaveBeenCalledWith("/users");
  });

  it.each([
    ["メールアドレスが空", { email: "", password: "pass1234", role: "user" }],
    ["パスワードが空", { email: "a@example.com", password: "", role: "user" }],
    ["項目が無い", {}],
  ])("%s なら入力を求め、サーバーへは送らない", async (_label, fields) => {
    const result = await createUserAction({}, form(fields));

    expect(result).toEqual({ error: "メールアドレスとパスワードを入力してください。" });
    expect(createUser).not.toHaveBeenCalled();
  });

  it.each([["" ], ["superuser"]])("role が %p なら選択を求める", async (role) => {
    const result = await createUserAction({}, form({ email: "a@example.com", password: "pass1234", role }));

    expect(result).toEqual({ error: "roleを選択してください。" });
    expect(createUser).not.toHaveBeenCalled();
  });

  it("role が無いときも選択を求める", async () => {
    const result = await createUserAction({}, form({ email: "a@example.com", password: "pass1234" }));

    expect(result).toEqual({ error: "roleを選択してください。" });
  });

  it("サーバーが拒否したら理由を返し、一覧は再検証しない", async () => {
    createUser.mockRejectedValue(new Error("duplicate"));

    const result = await createUserAction({}, form({ email: "a@example.com", password: "pass1234", role: "user" }));

    expect(result).toEqual({ error: "APIエラー (500): duplicate" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗したときは、Errorでない値を文字列にして返す", async () => {
    installFetch({
      "POST /api/users": () => {
        throw "boom";
      },
    });

    const result = await createUserAction({}, form({ email: "a@example.com", password: "pass1234", role: "user" }));

    expect(result).toEqual({ error: "boom" });
  });

  it("管理者でなければトップへ送り、ユーザーを作らない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(
      createUserAction({}, form({ email: "a@example.com", password: "pass1234", role: "user" }))
    ).rejects.toThrow("NEXT_REDIRECT:/");
    expect(createUser).not.toHaveBeenCalled();
  });
});
