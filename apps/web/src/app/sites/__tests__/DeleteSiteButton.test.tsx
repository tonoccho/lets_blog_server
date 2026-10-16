import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installFetch, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { DeleteSiteButton } from "../DeleteSiteButton";

const deleted: number[] = [];
let confirm: jest.SpyInstance;

beforeEach(() => {
  deleted.length = 0;
  resetServerRuntime(ADMIN_SESSION);
  installFetch({
    "DELETE /api/sites/:id": (_u, _i, [id]) => {
      deleted.push(Number(id));
      return { ok: true, status: 204, statusText: "No Content", text: async () => "", headers: { get: () => null } } as unknown as Response;
    },
  });
  confirm = jest.spyOn(window, "confirm");
});
afterEach(() => confirm.mockRestore());

describe("DeleteSiteButton", () => {
  it("確認すると、そのサイトを削除する(登録情報のみ削除される旨を確認する)", async () => {
    confirm.mockReturnValue(true);
    render(<DeleteSiteButton id={4} managedWordpress={false} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(deleted).toEqual([4]));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("外部サイト自体には影響しません"));
  });

  it("自動構築されたサイトでは、構築物も削除される旨を確認する", async () => {
    confirm.mockReturnValue(true);
    render(<DeleteSiteButton id={5} managedWordpress />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(deleted).toEqual([5]));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("専用データベースも削除されます"));
  });

  it("確認を取り消したら削除しない", () => {
    confirm.mockReturnValue(false);
    render(<DeleteSiteButton id={4} managedWordpress={false} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(deleted).toEqual([]);
  });
});
