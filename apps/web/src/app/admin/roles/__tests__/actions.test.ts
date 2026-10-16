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

import { assignRoleAction, removeRoleAction } from "../actions";
import { revalidatePath } from "next/cache";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

let fetchMock: jest.Mock;
const roleCalls = () =>
  fetchMock.mock.calls
    .map(([url, init]) => `${String(init?.method ?? "GET")} ${String(url).replace(/^https?:\/\/[^/]+/, "")}`)
    .filter((request) => request.includes("/roles/"));

describe.each([
  ["assignRoleAction", assignRoleAction, "POST"],
  ["removeRoleAction", removeRoleAction, "DELETE"],
] as const)("%s", (_name, action, method) => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [`${method} /api/users/3/roles/editor`]: () => jsonResponse({ message: "ok" }) });
  });

  it("ロールの変更をサーバーへ送り、ロール管理とユーザー一覧を再検証する", async () => {
    await action(3, "editor");

    expect(roleCalls()).toEqual([`${method} /api/users/3/roles/editor`]);
    expect(revalidatePath).toHaveBeenCalledWith("/admin/roles");
    expect(revalidatePath).toHaveBeenCalledWith("/users");
  });

  it("サーバーが拒否したら例外を伝え、再検証しない", async () => {
    installFetch({ [`${method} /api/users/3/roles/editor`]: () => textResponse("forbidden", 403) });

    await expect(action(3, "editor")).rejects.toThrow("APIエラー (403): forbidden");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("管理者でなければトップへ送り、何も送らない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(action(3, "editor")).rejects.toThrow("NEXT_REDIRECT:/");
    expect(roleCalls()).toEqual([]);
  });
});
