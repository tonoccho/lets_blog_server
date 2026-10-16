/**
 * @jest-environment node
 */

/**
 * sites の Server Action のうち、入力検証・鍵ペア生成・削除・wp-cli の導入。
 * 認可は actionsAuthorization.test.ts、管理画面パスは registerSiteActionAdminPath.test.ts が担う。
 *
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1834)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import {
  createManagedWordPressSiteAction,
  deleteSiteAction,
  generateSshKeyPairAction,
  installWpCliAction,
  registerSiteAction,
} from "../actions";

let registered: Record<string, unknown>[];
let keygenBodies: unknown[];
let deleted: number[];
let managedBodies: Record<string, unknown>[];

beforeEach(() => {
  registered = [];
  keygenBodies = [];
  deleted = [];
  managedBodies = [];
  revalidatePath.mockClear();
  resetServerRuntime(ADMIN_SESSION);
  installFetch({
    "POST /api/sites": (_u, init) => {
      registered.push(JSON.parse(String(init.body)));
      return jsonResponse({ connectionCheckStatus: "FAILED" });
    },
    "POST /api/sites/ssh-keypair": (_u, init) => {
      keygenBodies.push(JSON.parse(String(init.body)));
      return jsonResponse({ publicKeyLine: "ssh-ed25519 AAA", privateKeyPem: "PEM", extra: "x" });
    },
    "DELETE /api/sites/:id": (_u, _i, [id]) => {
      deleted.push(Number(id));
      return { ok: true, status: 204, statusText: "No Content", text: async () => "", headers: { get: () => null } } as unknown as Response;
    },
    "POST /api/sites/:id/install-wp-cli": () => jsonResponse({ message: "installed" }),
    "POST /api/sites/managed-wordpress/jobs": (_u, init) => {
      managedBodies.push(JSON.parse(String(init.body)));
      return jsonResponse({ id: 9, status: "running" });
    },
  });
});

function form(entries: Record<string, string | null> = {}): FormData {
  const base: Record<string, string | null> = {
    name: "N",
    siteKey: "k",
    cmsType: "WORDPRESS",
    baseUrl: "https://e.example.com",
    sshHost: "h",
    sshUser: "u",
    wpPath: "/w",
    sshPrivateKeyPem: "PEM",
    ...entries,
  };
  const fd = new FormData();
  for (const [k, v] of Object.entries(base)) if (v !== null) fd.set(k, v);
  return fd;
}

describe("registerSiteAction の入力検証", () => {
  it("すべて揃っていれば登録し、疎通確認の結果を返して一覧を再検証する", async () => {
    await expect(registerSiteAction({}, form())).resolves.toEqual({ success: true, connectionCheckStatus: "FAILED" });
    expect(revalidatePath).toHaveBeenCalledWith("/sites");
    const credentials = registered[0].credentials as Record<string, string>;
    expect(credentials).toEqual({
      transport: "SSH",
      baseUrl: "https://e.example.com",
      sshHost: "h",
      sshUser: "u",
      wpPath: "/w",
      sshPrivateKeyPem: "PEM",
    });
  });

  it.each(["name", "siteKey"])("%s が空(または無い)なら登録しない", async (field) => {
    for (const value of ["  ", null]) {
      await expect(registerSiteAction({}, form({ [field]: value }))).resolves.toEqual({
        error: "表示名とサイトキーは必須です。",
      });
    }
    expect(registered).toEqual([]);
  });

  it("CMS種別が WORDPRESS でなければ登録しない(無い場合も)", async () => {
    for (const cmsType of ["OTHER", null]) {
      await expect(registerSiteAction({}, form({ cmsType }))).resolves.toEqual({ error: "CMS種別を選択してください。" });
    }
    expect(registered).toEqual([]);
  });

  it.each(["baseUrl", "sshHost", "sshUser", "wpPath"])("%s が空(または無い)なら登録しない", async (field) => {
    for (const value of ["", null]) {
      await expect(registerSiteAction({}, form({ [field]: value }))).resolves.toEqual({ error: `${field} は必須です。` });
    }
    expect(registered).toEqual([]);
  });

  it("SSHポートの指定があれば送る", async () => {
    await registerSiteAction({}, form({ sshPort: " 2222 " }));
    expect((registered[0].credentials as Record<string, string>).sshPort).toBe("2222");
  });

  it("保存済みの鍵ペアIDがあれば、秘密鍵より優先してIDだけを送る", async () => {
    await registerSiteAction({}, form({ sshKeyPairId: "5", sshPrivateKeyPem: "PEM" }));
    const credentials = registered[0].credentials as Record<string, string>;
    expect(credentials.sshKeyPairId).toBe("5");
    expect(credentials).not.toHaveProperty("sshPrivateKeyPem");
  });

  it("鍵ペアIDも秘密鍵も無ければ登録しない", async () => {
    for (const value of ["", null]) {
      const result = await registerSiteAction({}, form({ sshPrivateKeyPem: value }));
      expect(result.error).toContain("SSH秘密鍵を指定してください");
    }
    expect(registered).toEqual([]);
  });

  it("Error 以外の値で通信が失敗しても文字列にして返す", async () => {
    installFetch({
      "POST /api/sites": () => {
        throw "切断";
      },
    });
    await expect(registerSiteAction({}, form())).resolves.toEqual({ error: "切断" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

describe("generateSshKeyPairAction", () => {
  it("コメントを渡して鍵ペアを生成し、公開鍵と秘密鍵だけを返す", async () => {
    await expect(generateSshKeyPairAction("letsblog")).resolves.toEqual({ publicKeyLine: "ssh-ed25519 AAA", privateKeyPem: "PEM" });
    expect(keygenBodies).toEqual([{ comment: "letsblog" }]);
  });

  it("コメントが空なら null で送る", async () => {
    await generateSshKeyPairAction("");
    expect(keygenBodies).toEqual([{ comment: null }]);
  });

  it("失敗したら理由を error として返す(Error 以外も文字列にする)", async () => {
    installFetch({ "POST /api/sites/ssh-keypair": () => textResponse("生成できません", 500) });
    await expect(generateSshKeyPairAction("c")).resolves.toEqual({ error: "APIエラー (500): 生成できません" });
    installFetch({
      "POST /api/sites/ssh-keypair": () => {
        throw "切断";
      },
    });
    await expect(generateSshKeyPairAction("c")).resolves.toEqual({ error: "切断" });
  });
});

describe("deleteSiteAction / installWpCliAction", () => {
  it("サイトを削除して一覧を再検証する", async () => {
    await deleteSiteAction(4);
    expect(deleted).toEqual([4]);
    expect(revalidatePath).toHaveBeenCalledWith("/sites");
  });

  it("wp-cli をインストールした結果を返す", async () => {
    await expect(installWpCliAction(4)).resolves.toEqual({ message: "installed" });
  });
});

describe("createManagedWordPressSiteAction のフォームの欠落", () => {
  it("項目がまったく無いフォームは、ジョブを作らずエラーを返す", async () => {
    await expect(createManagedWordPressSiteAction({}, new FormData())).resolves.toEqual({
      error: "すべての項目を入力してください。",
    });
    expect(managedBodies).toEqual([]);
  });
});
