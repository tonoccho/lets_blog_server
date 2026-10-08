import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { UserRow } from "../UserRow";
import { DeleteUserButton } from "../DeleteUserButton";
import { deleteUserAction } from "../actions";

/**
 * issue #1383: 削除成功時はサーバ再描画の到達を待たずクライアント側の状態で行を除く
 * (#1361 SshKeyPairsPanel と同じ方針)。失敗時は行を残し理由を表示し、ボタンを再度押せる。
 */
jest.mock("../actions", () => ({ deleteUserAction: jest.fn() }));
const deleteMock = deleteUserAction as jest.MockedFunction<typeof deleteUserAction>;

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
    deleteMock.mockReset();
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
    deleteMock.mockResolvedValue({});
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(screen.queryByText("a@example.com")).toBeNull());
    expect(deleteMock).toHaveBeenCalledWith(7);
  });

  it("失敗すると行は残り、理由が表示され、ボタンが再び押せる", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "自分自身のアカウントは削除できません。" });
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await screen.findByText("自分自身のアカウントは削除できません。");
    expect(screen.getByText("a@example.com")).toBeTruthy();
    const button = screen.getByRole("button", { name: "削除" }) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
  });

  it("Server Actionが例外を投げても行は残り、理由が表示される", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockRejectedValue(new Error("network down"));
    renderRow();
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await screen.findByText("network down");
    expect(screen.getByText("a@example.com")).toBeTruthy();
  });

  it("例外がErrorでないときも既定の文言を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockRejectedValue("x");
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
    deleteMock.mockResolvedValue({});
    render(<DeleteUserButton id={3} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(3));
  });
});
