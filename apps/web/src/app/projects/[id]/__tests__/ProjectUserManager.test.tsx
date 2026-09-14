import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { ProjectUserManager } from "../ProjectUserManager";
import type { ProjectUser } from "@/lib/apiClient";

const updateProjectUserRoleAction = jest.fn();
const removeProjectUserAction = jest.fn();
const syncProjectUserAction = jest.fn();

jest.mock("../actions", () => ({
  updateProjectUserRoleAction: (...args: unknown[]) => updateProjectUserRoleAction(...args),
  removeProjectUserAction: (...args: unknown[]) => removeProjectUserAction(...args),
  syncProjectUserAction: (...args: unknown[]) => syncProjectUserAction(...args),
}));

function buildMember(overrides: Partial<ProjectUser> = {}): ProjectUser {
  return {
    userId: 1,
    email: "member@example.com",
    displayName: "テストメンバー",
    wpRole: "author",
    ...overrides,
  };
}

describe("ProjectUserManager(issue #1242: ユーザー情報同期ボタン)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("各メンバー行に「ユーザー情報を同期」ボタンが表示される", () => {
    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);

    expect(screen.getByRole("button", { name: "ユーザー情報を同期" })).toBeInTheDocument();
  });

  it("ボタンを押すとsyncProjectUserActionが呼ばれ、成功した環境が表示される", async () => {
    syncProjectUserAction.mockResolvedValue({
      results: [
        { siteId: 10, siteKey: "local-key", siteName: "ローカル", success: true, errorMessage: null },
      ],
    });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    await waitFor(() => {
      expect(syncProjectUserAction).toHaveBeenCalledWith(1, 5);
    });
    expect(await screen.findByText(/ローカル/)).toBeInTheDocument();
    expect(screen.getByText(/成功/)).toBeInTheDocument();
  });

  it("一部の環境が失敗すると、成功分は保持したまま失敗した環境名と理由が表示される", async () => {
    syncProjectUserAction.mockResolvedValue({
      results: [
        { siteId: 10, siteKey: "local-key", siteName: "ローカル", success: true, errorMessage: null },
        { siteId: 20, siteKey: "test-key", siteName: "テスト環境", success: false, errorMessage: "接続に失敗しました" },
      ],
    });

    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText(/ローカル/)).toBeInTheDocument();
    expect(screen.getByText(/テスト環境/)).toBeInTheDocument();
    expect(screen.getByText(/接続に失敗しました/)).toBeInTheDocument();
  });

  it("同期そのものが失敗するとエラーメッセージが表示される", async () => {
    syncProjectUserAction.mockResolvedValue({ error: "サーバーエラーが発生しました" });

    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("サーバーエラーが発生しました")).toBeInTheDocument();
  });

  it("紐づくWordPress環境が無い場合はその旨を表示する", async () => {
    syncProjectUserAction.mockResolvedValue({ results: [] });

    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("紐づくWordPress環境がありません。")).toBeInTheDocument();
  });

  it("失敗理由が無い場合は理由の括弧を付けずに「失敗」とだけ表示する", async () => {
    syncProjectUserAction.mockResolvedValue({
      results: [
        { siteId: 20, siteKey: "test-key", siteName: "テスト環境", success: false, errorMessage: null },
      ],
    });

    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("テスト環境: 失敗")).toBeInTheDocument();
  });
});
