import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { RoleAssignmentPanel } from "../RoleAssignmentPanel";

// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
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

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

/** identity-service の代役(gateway の向こう側)。 */
const assignRole = jest.fn();
const removeRole = jest.fn();

function respond(backend: jest.Mock) {
  return async (_url: string, _init: RequestInit, [id, roleName]: string[]) => {
    try {
      return jsonResponse(await backend(Number(id), roleName));
    } catch (err) {
      return textResponse(err instanceof Error ? err.message : String(err), 500);
    }
  };
}

function installApi() {
  installFetch({
    "POST /api/users/:id/roles/:role": respond(assignRole),
    "DELETE /api/users/:id/roles/:role": respond(removeRole),
  });
}

const ROLES = [
  { roleName: "admin", displayName: "管理者" },
  { roleName: "editor", displayName: "編集者" },
];

describe("RoleAssignmentPanel", () => {
  beforeEach(() => {
    assignRole.mockReset().mockResolvedValue({ message: "ok" });
    removeRole.mockReset().mockResolvedValue({ message: "ok" });
    resetServerRuntime(ADMIN_SESSION);
    installApi();
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

  it("ロールを選択して割り当てると、そのロールの割り当てがサーバーへ送られる", async () => {
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
      expect(assignRole).toHaveBeenCalledWith(1, "editor");
    });
  });

  it("割り当て済みロールの×ボタンで、そのロールの解除がサーバーへ送られる", async () => {
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: ["admin"] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "adminを解除" }));

    await waitFor(() => {
      expect(removeRole).toHaveBeenCalledWith(1, "admin");
    });
  });

  it("割り当てに失敗するとエラーメッセージを表示する", async () => {
    assignRole.mockRejectedValueOnce(new Error("割り当てに失敗しました"));
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(await screen.findByText("APIエラー (500): 割り当てに失敗しました")).toBeInTheDocument();
  });

  it("解除に失敗するとエラーメッセージを表示する", async () => {
    removeRole.mockRejectedValueOnce(new Error("解除に失敗しました"));
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: ["admin"] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "adminを解除" }));

    expect(await screen.findByText("APIエラー (500): 解除に失敗しました")).toBeInTheDocument();
  });

  it("通信そのものが失敗してErrorでない値が投げられても文字列化してエラー表示する(割り当て)", async () => {
    installFetch({
      "POST /api/users/1/roles/admin": () => {
        throw "文字列エラー";
      },
    });
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(await screen.findByText("文字列エラー")).toBeInTheDocument();
  });

  it("通信そのものが失敗してErrorでない値が投げられても文字列化してエラー表示する(解除)", async () => {
    installFetch({
      "DELETE /api/users/1/roles/admin": () => {
        throw "解除失敗の文字列";
      },
    });
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: ["admin"] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "adminを解除" }));

    expect(await screen.findByText("解除失敗の文字列")).toBeInTheDocument();
  });

  it("処理中はボタンを無効にして「処理中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({
      "POST /api/users/1/roles/admin": () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response,
    });
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={ROLES}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    const pending = await screen.findByRole("button", { name: "処理中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse({ message: "ok" }));
    await screen.findByRole("button", { name: "割り当て" });
  });

  it("割り当てロールの候補が無い(roles=[])とき、空文字が選択され割り当てボタンは何もしない", () => {
    const fetchMock = installFetch({});
    render(
      <RoleAssignmentPanel
        users={[{ id: 1, email: "user@example.com", roleNames: [] }]}
        roles={[]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "割り当て" }));

    expect(assignRole).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
