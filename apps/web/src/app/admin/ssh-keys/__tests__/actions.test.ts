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

import { createSshKeyPairAction, deleteSshKeyPairAction } from "../actions";
import { revalidatePath } from "next/cache";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const GENERATED = {
  id: 9,
  name: "new-pair",
  comment: null,
  publicKeyLine: "ssh-ed25519 AAAA... new-pair",
  privateKeyPem: "-----BEGIN OPENSSH PRIVATE KEY-----",
  createdAt: "2026-09-08T20:03:35",
};

let fetchMock: jest.Mock;
const bodies = (method: string, suffix: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(suffix) && init?.method === method)
    .map(([, init]) => (init.body ? JSON.parse(String(init.body)) : null));

describe("createSshKeyPairAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ "POST /api/ssh-key-pairs": () => jsonResponse(GENERATED, 201) });
  });

  it("名前とコメントを送って鍵ペアを返し、一覧を再検証する", async () => {
    const result = await createSshKeyPairAction("new-pair", "メモ");

    expect(result).toEqual({ keyPair: GENERATED });
    expect(bodies("POST", "/api/ssh-key-pairs")).toEqual([{ name: "new-pair", comment: "メモ" }]);
    expect(revalidatePath).toHaveBeenCalledWith("/admin/ssh-keys");
  });

  it("コメントが空なら null として送る", async () => {
    await createSshKeyPairAction("new-pair", "");

    expect(bodies("POST", "/api/ssh-key-pairs")).toEqual([{ name: "new-pair", comment: null }]);
  });

  it("サーバーが拒否したら理由を返し、再検証しない", async () => {
    installFetch({ "POST /api/ssh-key-pairs": () => textResponse("名前が既に使用されています", 409) });

    const result = await createSshKeyPairAction("dup", "");

    expect(result).toEqual({ error: "APIエラー (409): 名前が既に使用されています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗してError以外が投げられた場合は、文字列にして返す", async () => {
    installFetch({
      "POST /api/ssh-key-pairs": () => {
        throw "network down";
      },
    });

    expect(await createSshKeyPairAction("x", "")).toEqual({ error: "network down" });
  });

  it("管理者でなければトップへ送り、鍵を作らない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(createSshKeyPairAction("x", "")).rejects.toThrow("NEXT_REDIRECT:/");
    expect(bodies("POST", "/api/ssh-key-pairs")).toEqual([]);
  });
});

describe("deleteSshKeyPairAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ "DELETE /api/ssh-key-pairs/4": () => jsonResponse(null) });
  });

  it("鍵ペアを削除して一覧を再検証し、errorを返さない", async () => {
    const result = await deleteSshKeyPairAction(4);

    expect(result).toEqual({});
    expect(bodies("DELETE", "/api/ssh-key-pairs/4")).toHaveLength(1);
    expect(revalidatePath).toHaveBeenCalledWith("/admin/ssh-keys");
  });

  it("サーバーが拒否したら理由を返し、再検証しない", async () => {
    installFetch({ "DELETE /api/ssh-key-pairs/4": () => textResponse("使用中です", 409) });

    expect(await deleteSshKeyPairAction(4)).toEqual({ error: "APIエラー (409): 使用中です" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("通信そのものが失敗してError以外が投げられた場合は、文字列にして返す", async () => {
    installFetch({
      "DELETE /api/ssh-key-pairs/4": () => {
        throw "network down";
      },
    });

    expect(await deleteSshKeyPairAction(4)).toEqual({ error: "network down" });
  });

  it("管理者でなければトップへ送り、削除しない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(deleteSshKeyPairAction(4)).rejects.toThrow("NEXT_REDIRECT:/");
    expect(bodies("DELETE", "/api/ssh-key-pairs/4")).toEqual([]);
  });
});
