import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { RoleAssignmentPanel } from "../RoleAssignmentPanel";

const assignRoleAction = jest.fn();
const removeRoleAction = jest.fn();

jest.mock("../actions", () => ({
  assignRoleAction: (...args: unknown[]) => assignRoleAction(...args),
  removeRoleAction: (...args: unknown[]) => removeRoleAction(...args),
}));

const ROLES = [
  { roleName: "admin", displayName: "管理者" },
  { roleName: "editor", displayName: "編集者" },
];

describe("RoleAssignmentPanel", () => {
  beforeEach(() => {
    assignRoleAction.mockReset().mockResolvedValue(undefined);
    removeRoleAction.mockReset().mockResolvedValue(undefined);
  });

  it("ユーザーが1人もいなければ「ユーザーがいません。」と表示する", () => {
    render(<RoleAssignmentPanel users={[]} roles={ROLES} />);
    expect(screen.getByText("ユーザーがいません。")).toBeInTheDocument();
  });

  it("割り当て済みロールが無いユーザーは「なし」と表示する", () => {
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );
    expect(screen.getByText("なし")).toBeInTheDocument();
  });

  it("ロールを選択して割り当てるとassignRoleActionが呼ばれる", async () => {
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.change(screen.getByLabelText("user@example.comに追加するロール"), {
      target: { value: "editor" },
    });
    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    await waitFor(() => {
      expect(assignRoleAction).toHaveBeenCalledWith(1, "editor");
    });
  });

  it("割り当て済みロールの×ボタンでremoveRoleActionが呼ばれる", async () => {
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: ["admin"] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "adminを解除" }));

    await waitFor(() => {
      expect(removeRoleAction).toHaveBeenCalledWith(1, "admin");
    });
  });

  it("割り当てに失敗するとエラーメッセージを表示する", async () => {
    assignRoleAction.mockRejectedValueOnce(new Error("割り当てに失敗しました"));
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(await screen.findByText("割り当てに失敗しました")).toBeInTheDocument();
  });

  it("Errorでない値がthrowされても文字列化してエラー表示する(割り当て)", async () => {
    assignRoleAction.mockRejectedValueOnce("文字列エラー");
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(await screen.findByText("文字列エラー")).toBeInTheDocument();
  });

  it("Errorでない値がthrowされても文字列化してエラー表示する(解除)", async () => {
    removeRoleAction.mockRejectedValueOnce("解除失敗の文字列");
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: ["admin"] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "adminを解除" }));

    expect(await screen.findByText("解除失敗の文字列")).toBeInTheDocument();
  });

  it("割り当てロールの候補が無い(roles=[])とき、空文字が選択され割り当てボタンは何もしない", () => {
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={[]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(assignRoleAction).not.toHaveBeenCalled();
  });
});
