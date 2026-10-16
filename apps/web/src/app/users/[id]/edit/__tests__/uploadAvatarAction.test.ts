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

import { uploadAvatarAction } from "../actions";
import { revalidatePath } from "next/cache";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const UPLOAD = "POST /api/users/1/avatar";
let fetchMock: jest.Mock;
/** identity-service へアップロードされたリクエストの本文(FormData)。 */
const uploads = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/users/1/avatar") && init?.method === "POST")
    .map(([, init]) => init.body as FormData);

function install(viewer: unknown, upload: () => Response = () => jsonResponse({ id: 1 })) {
  fetchMock = installFetch({
    "GET /api/identity/me": () => jsonResponse(viewer),
    [UPLOAD]: upload,
  });
}

/**
 * issue #1241: プロフィール編集画面のアバターアップロードのサーバーアクション。
 * 認可判定(本人またはadmin)・ファイル未選択・アップロード成功/失敗の各分岐を検証する。
 */
describe("uploadAvatarAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  function formDataWithFile(file: File | null): FormData {
    const formData = new FormData();
    if (file) {
      formData.append("file", file);
    }
    return formData;
  }

  it("本人であればファイルをアップロードしsuccessを返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ success: true });
    expect(uploads()).toHaveLength(1);
    const sent = uploads()[0].get("file") as File;
    expect(sent.name).toBe("a.png");
    expect(sent.size).toBe(10);
    expect(revalidatePath).toHaveBeenCalledWith("/users/1/edit");
  });

  it("本人でなくadminでもなければ権限エラーを返しアップロードしない", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 999 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "この操作を行う権限がありません。" });
    expect(uploads()).toHaveLength(0);
  });

  it("本人でなくてもadminであればアップロードする", async () => {
    resetServerRuntime(ADMIN_SESSION);
    install({ id: 999 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ success: true });
    expect(uploads()).toHaveLength(1);
  });

  it("ファイルが選択されていなければエラーを返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });

    const result = await uploadAvatarAction(1, {}, formDataWithFile(null));

    expect(result).toEqual({ error: "画像ファイルを選択してください。" });
    expect(uploads()).toHaveLength(0);
  });

  it("空ファイル(サイズ0)もエラーを返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 });

    const emptyFile = new File([], "empty.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(emptyFile));

    expect(result).toEqual({ error: "画像ファイルを選択してください。" });
    expect(uploads()).toHaveLength(0);
  });

  it("APIが拒否した場合そのmessageを返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 }, () => textResponse("対応していない画像形式です", 400));

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "APIエラー (400): 対応していない画像形式です" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗してError以外が投げられた場合はString化して返す", async () => {
    resetServerRuntime(USER_SESSION);
    install({ id: 1 }, () => {
      throw "network down";
    });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "network down" });
  });

  it("未ログインならログイン画面へ送る", async () => {
    resetServerRuntime(null);
    install({ id: 1 });

    await expect(uploadAvatarAction(1, {}, formDataWithFile(null))).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
