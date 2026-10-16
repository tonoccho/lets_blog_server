import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { UserRow } from "../UserRow";
import { DeleteUserButton } from "../DeleteUserButton";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

/**
 * issue #1383: 削除成功時はサーバ再描画の到達を待たずクライアント側の状態で行を除く
 * (#1361 SshKeyPairsPanel と同じ方針)。失敗時は行を残し理由を表示し、ボタンを再度押せる。
 */
// 自前の ../actions・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
let mockRedirectThrows: unknown = new Error("NEXT_REDIRECT");
jest.mock("next/navigation", () => ({
  redirect: () => {
    throw mockRedirectThrows;
  },
}));

/** identity-service の代役(gateway の向こう側)。 */
const deleteMock = jest.fn();
const getMyProfile = jest.fn();
const DELETE_USER = "DELETE /api/users/:id";

function installApi() {
  installFetch({
    [DELETE_USER]: async (_url, _init, [id]) => {
      try {
        return jsonResponse((await deleteMock(Number(id))) ?? null);
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 500);
      }
    },
    "GET /api/identity/me": async () => jsonResponse(await getMyProfile()),
  });
}

function renderRow(canDelete = true) {
  return render(
    <table>
      <tbody>
        <UserRow id={7} email="a@example.com" role="user" projectsText="P1" canDelete={canDelete}>
          <span>2026-01-01</span>
        </UserRow>
      </tbody>
    </table>
  );
}

describe("UserRow / DeleteUserButton (issue #1383)", () => {
  beforeEach(() => {
    mockRedirectThrows = new Error("NEXT_REDIRECT");
    deleteMock.mockReset();
    getMyProfile.mockReset();
    getMyProfile.mockResolvedValue({ id: 1 });
    resetServerRuntime(ADMIN_SESSION);
    installApi();
    window.confirm = jest.fn();
  });

  it("セルの内容と編集リンクを描画する", () => {
    renderRow();
    expect(screen.getByText("a@example.com")).toBeTruthy();
    expect(screen.getByText("P1")).toBeTruthy();
    expect(screen.getByText("2026-01-01")).toBeTruthy();
    expect(screen.getByRole("link", { name: "編集" }).getAttribute("href")).toBe("/users/7/edit");
  });

  it("canDeleteがfalseなら削除ボタンを出さない", () => {
    renderRow(false);
    expect(screen.queryByRole("button", { name: "削除" })).toBeNull();
  });

  it("承認して成功すると、再描画を待たずに行が消える", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue(undefined);
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(screen.queryByText("a@example.com")).toBeNull());
    expect(deleteMock).toHaveBeenCalledWith(7);
  });

  it("失敗すると行は残り、理由が表示され、ボタンが再び押せる", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    getMyProfile.mockResolvedValue({ id: 7 });
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await screen.findByText("自分自身のアカウントは削除できません。");
    expect(screen.getByText("a@example.com")).toBeTruthy();
    expect(deleteMock).not.toHaveBeenCalled();
    // エラー表示と isPending の解除は別の render になりうる(負荷時は「削除中…」が残る)ため待つ
    const button = (await screen.findByRole("button", { name: "削除" })) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
  });

  it("Server Actionが例外を投げても行は残り、理由が表示される", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    // 認可の失敗(管理者でない)は、{ error } ではなく例外としてクライアントへ伝わる。
    resetServerRuntime(USER_SESSION);
    mockRedirectThrows = new Error("network down");
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await screen.findByText("network down");
    expect(screen.getByText("a@example.com")).toBeTruthy();
  });

  it("例外がErrorでないときも既定の文言を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    resetServerRuntime(USER_SESSION);
    mockRedirectThrows = "x";
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await screen.findByText("削除に失敗しました。");
  });

  it("確認ダイアログでキャンセルすると削除は呼ばれず行も残る", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(deleteMock).not.toHaveBeenCalled();
    expect(screen.getByText("a@example.com")).toBeTruthy();
  });

  it("onDeletedを渡さなくても成功時に落ちない", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue(undefined);
    render(<DeleteUserButton id={3} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(3));
  });
});
