import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SshKeyPairsPanel } from "../SshKeyPairsPanel";
import { createSshKeyPairAction, deleteSshKeyPairAction } from "../actions";
import type { SavedSshKeyPair } from "@/lib/apiClient";

/**
 * issue #1051: 生成フォームの<form>にmethod="post"を明示した(JS無効時のネイティブGET
 * フォールバックで名前・コメントがURLへ漏れるのを防ぐ)。それ以外の挙動(生成・削除)は
 * 既存のまま変わっていないため、このテストは変更点(method="post")の確認と、
 * 主要な分岐(生成成功/失敗、削除確認ダイアログのキャンセル/削除)を最小限で押さえる。
 */
jest.mock("../actions", () => ({
  createSshKeyPairAction: jest.fn(),
  deleteSshKeyPairAction: jest.fn(),
}));

const createMock = createSshKeyPairAction as jest.MockedFunction<typeof createSshKeyPairAction>;
const deleteMock = deleteSshKeyPairAction as jest.MockedFunction<typeof deleteSshKeyPairAction>;

function keyPair(overrides: Partial<SavedSshKeyPair> = {}): SavedSshKeyPair {
  return {
    id: 1,
    name: "production-deploy",
    comment: null,
    publicKeyLine: "ssh-ed25519 AAAA... production-deploy",
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("SshKeyPairsPanel", () => {
  beforeEach(() => {
    createMock.mockReset();
    deleteMock.mockReset();
    window.confirm = jest.fn();
  });

  it("生成フォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<SshKeyPairsPanel keyPairs={[]} />);

    const form = container.querySelector("form");
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("保存済みの鍵ペアが無ければその旨を表示する", () => {
    render(<SshKeyPairsPanel keyPairs={[]} />);

    expect(screen.getByText("保存済みのSSH鍵ペアはありません。")).toBeInTheDocument();
  });

  it("生成に失敗するとエラーを表示する", async () => {
    createMock.mockResolvedValue({ error: "名前が既に使用されています" });
    render(<SshKeyPairsPanel keyPairs={[]} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "dup" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    await waitFor(() => {
      expect(screen.getByText("名前が既に使用されています")).toBeInTheDocument();
    });
  });

  it("生成に成功すると秘密鍵の表示欄が現れる", async () => {
    createMock.mockResolvedValue({
      keyPair: {
        ...keyPair({ name: "new-pair" }),
        privateKeyPem: "-----BEGIN OPENSSH PRIVATE KEY-----",
      },
    });
    render(<SshKeyPairsPanel keyPairs={[]} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "new-pair" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    await waitFor(() => {
      expect(screen.getByText(/を生成しました。/)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: "閉じる" }));
    expect(screen.queryByText(/を生成しました。/)).not.toBeInTheDocument();
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("削除確認を承認すると削除アクションを呼び、失敗時はエラーを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "削除に失敗しました" });
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(deleteMock).toHaveBeenCalledWith(1);
      expect(screen.getByText("削除に失敗しました")).toBeInTheDocument();
    });
  });
});
