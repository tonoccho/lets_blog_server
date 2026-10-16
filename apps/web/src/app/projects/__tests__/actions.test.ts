/**
 * @jest-environment node
 */
// プロジェクトの Server Action。自前の @/lib/session・@/lib/apiClient は差し替えない。
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

import { installFetch, jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { createProjectAction, deleteProjectAction } from "../actions";

let deleted: string[];

beforeEach(() => {
  deleted = [];
  revalidatePath.mockClear();
  resetServerRuntime(ADMIN_SESSION);
  installFetch({
    "POST /api/projects": () => jsonResponse({ id: 1 }, 201),
    "DELETE /api/projects/:id": (_u, _i, [id]) => {
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

describe("createProjectAction", () => {
  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(createProjectAction({}, form({ name: "a", slug: "a" }))).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it("フォームに項目が無ければ(null)入力を促す", async () => {
    expect(await createProjectAction({}, new FormData())).toEqual({ error: "プロジェクト名とslugを入力してください。" });
  });

  it("作成したら一覧を再検証して成功を返す", async () => {
    expect(await createProjectAction({}, form({ name: " 案件 ", slug: " a " }))).toEqual({ success: true });
    expect(revalidatePath).toHaveBeenCalledWith("/projects");
  });

  it("バックエンドが Error 以外の値で失敗しても、文字列にして返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("network down") as unknown as typeof fetch;
    expect(await createProjectAction({}, form({ name: "a", slug: "a" }))).toEqual({ error: "network down" });
  });
});

describe("deleteProjectAction", () => {
  it("削除して一覧を再検証し、/projects へ戻す", async () => {
    await expect(deleteProjectAction(7)).rejects.toThrow("NEXT_REDIRECT:/projects");
    expect(deleted).toEqual(["7"]);
    expect(revalidatePath).toHaveBeenCalledWith("/projects");
  });
});
