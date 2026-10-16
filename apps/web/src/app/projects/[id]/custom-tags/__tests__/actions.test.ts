/**
 * @jest-environment node
 */
// プロジェクトのカスタムタグの Server Action。自前の @/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import {
  deleteProjectCustomTagAction,
  updateProjectCssSelectorPrefixAction,
  upsertProjectCustomTagAction,
} from "../actions";

let prefixBodies: unknown[];
let created: Record<string, unknown>[];
let updated: { id: string; body: Record<string, unknown> }[];
let deleted: string[];
let prefixResponse: () => Response;
let createResponse: () => Response;

beforeEach(() => {
  prefixBodies = [];
  created = [];
  updated = [];
  deleted = [];
  revalidatePath.mockClear();
  resetServerRuntime(ADMIN_SESSION);
  prefixResponse = () => jsonResponse({});
  createResponse = () => jsonResponse({ id: 1 }, 201);
  installFetch({
    "PUT /api/projects/:id/css-selector-prefix": (_u, init) => {
      prefixBodies.push(JSON.parse(String(init.body)));
      return prefixResponse();
    },
    "POST /api/custom-tags": (_u, init) => {
      created.push(JSON.parse(String(init.body)));
      return createResponse();
    },
    "PUT /api/custom-tags/:id": (_u, init, [id]) => {
      updated.push({ id, body: JSON.parse(String(init.body)) });
      return jsonResponse({ id: Number(id) });
    },
    "DELETE /api/custom-tags/:id": (_u, _i, [id]) => {
      deleted.push(id);
      return { ok: true, status: 204, statusText: "No Content", text: async () => "", headers: { get: () => null } } as unknown as Response;
    },
  });
});

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(values)) data.set(k, v);
  return data;
}

describe("updateProjectCssSelectorPrefixAction", () => {
  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(updateProjectCssSelectorPrefixAction(5, {}, form({ cssSelectorPrefix: "a" }))).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it("前後の空白を除いた接頭辞を保存し、タグ画面を再検証する", async () => {
    expect(await updateProjectCssSelectorPrefixAction(5, {}, form({ cssSelectorPrefix: "  alpha " }))).toEqual({ success: true });
    expect(prefixBodies).toEqual([{ cssSelectorPrefix: "alpha" }]);
    expect(revalidatePath).toHaveBeenCalledWith("/projects/5/tags");
  });

  it("項目が無ければ空文字で保存する(未設定に戻す)", async () => {
    await updateProjectCssSelectorPrefixAction(5, {}, new FormData());
    expect(prefixBodies).toEqual([{ cssSelectorPrefix: "" }]);
  });

  it("保存に失敗したら、バックエンドのエラーを返し、再検証しない", async () => {
    prefixResponse = () => textResponse("接頭辞が不正です", 400);
    const result = await updateProjectCssSelectorPrefixAction(5, {}, form({ cssSelectorPrefix: "a" }));
    expect(result.error).toContain("接頭辞が不正です");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error でない値で失敗しても文字列にして返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("network down") as unknown as typeof fetch;
    expect(await updateProjectCssSelectorPrefixAction(5, {}, form({ cssSelectorPrefix: "a" }))).toEqual({ error: "network down" });
  });
});

describe("upsertProjectCustomTagAction", () => {
  const valid = { tagName: " note ", htmlTemplate: " <div>{{content}}</div> ", projectId: "7" };

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(upsertProjectCustomTagAction({}, form(valid))).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it.each([
    ["タグ名が空", { ...valid, tagName: " " }],
    ["HTMLテンプレートが空", { ...valid, htmlTemplate: "" }],
    ["タグ名もテンプレートも無い", { projectId: "7" }],
  ])("%s なら必須エラーを返し、通信しない", async (_name, values) => {
    expect(await upsertProjectCustomTagAction({}, form(values))).toEqual({ error: "タグ名とHTMLテンプレートは必須です。" });
    expect(created).toEqual([]);
  });

  it("プロジェクトIDが無ければ不正として返す", async () => {
    expect(await upsertProjectCustomTagAction({}, form({ tagName: "a", htmlTemplate: "b" }))).toEqual({ error: "プロジェクトIDが不正です。" });
  });

  it("新規作成: 空白を除き、説明・CSS が空なら送らず、形式は既定でブロック", async () => {
    expect(await upsertProjectCustomTagAction({}, form(valid))).toEqual({ success: true });
    expect(created).toEqual([{ tagName: "note", htmlTemplate: "<div>{{content}}</div>", tagFormat: "BLOCK", projectId: 7 }]);
    expect(revalidatePath).toHaveBeenCalledWith("/projects/7/tags");
  });

  it("説明・CSS・インライン形式を指定すれば、そのまま送る", async () => {
    await upsertProjectCustomTagAction({}, form({ ...valid, description: "注意", cssContent: ".a{}", tagFormat: "INLINE" }));
    expect(created[0]).toMatchObject({ description: "注意", cssContent: ".a{}", tagFormat: "INLINE" });
  });

  it("id があれば更新する", async () => {
    expect(await upsertProjectCustomTagAction({}, form({ ...valid, id: "3" }))).toEqual({ success: true });
    expect(updated).toEqual([expect.objectContaining({ id: "3" })]);
    expect(created).toEqual([]);
  });

  it("保存に失敗したら、バックエンドのエラーを返し、再検証しない", async () => {
    createResponse = () => textResponse("タグ名が重複しています", 409);
    const result = await upsertProjectCustomTagAction({}, form(valid));
    expect(result.error).toContain("タグ名が重複しています");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error でない値で失敗しても文字列にして返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("network down") as unknown as typeof fetch;
    expect(await upsertProjectCustomTagAction({}, form(valid))).toEqual({ error: "network down" });
  });
});

describe("deleteProjectCustomTagAction", () => {
  it("削除してタグ画面を再検証する", async () => {
    await deleteProjectCustomTagAction(7, 3);
    expect(deleted).toEqual(["3"]);
    expect(revalidatePath).toHaveBeenCalledWith("/projects/7/tags");
  });
});
