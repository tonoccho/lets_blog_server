import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SshKeyPairsPanel } from "../SshKeyPairsPanel";
import { createSshKeyPairAction, deleteSshKeyPairAction } from "../actions";
import type { SavedSshKeyPair } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";

/**
 * issue #1236: `new Date(keyPair.createdAt).toLocaleString("ja-JP")` を直接呼んでいたため、
 * オフセット無しの日時文字列(バックエンドのLocalDateTime由来)が実行環境のTZでパースされ、
 * SSRとブラウザで表示がずれ得た。共有ヘルパ`formatDateTime`を経由するよう変更する。
 *
 * issue #1362: 個人設定TZ(`personalTimeZone`)を受け取る経路を追加した。個人設定TZが
 * あるときはSSR/クライアントで同じ文字列になるためgate不要。無いときは、マウント後に
 * しか解決できないブラウザTZを使うため、マウント前は固定プレースホルダーを描く
 * (前例: ThemeSwitcher.tsx:23-58のmountedフラグ方式)。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CREATED_AT"),
}));

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
    (formatDateTime as jest.Mock).mockClear();
    window.confirm = jest.fn();
  });

  it("生成フォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    const form = container.querySelector("form");
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("保存済みの鍵ペアが無ければその旨を表示する", () => {
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(screen.getByText("保存済みのSSH鍵ペアはありません。")).toBeInTheDocument();
  });

  it("生成に失敗するとエラーを表示する", async () => {
    createMock.mockResolvedValue({ error: "名前が既に使用されています" });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

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
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

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
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("削除確認を承認すると削除アクションを呼び、失敗時は行が残りエラーを表示する(issue #1361 AC3)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "削除に失敗しました" });
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(deleteMock).toHaveBeenCalledWith(1);
      expect(screen.getByText("削除に失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByText("production-deploy")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "削除" })).toBeInTheDocument();
  });

  /**
   * issue #1361: サーバコンポーネントのprops(`keyPairs`)のみに一覧が依存しており、
   * `deleteSshKeyPairAction`が呼び出す`revalidatePath`によるサーバ再描画がマウント済みの
   * クライアントコンポーネントへ届かない(あるいは届くタイミングが不定)ケースがあった。
   * サーバの再描画を待たずに、削除成功が確定した時点でローカル状態から行を消す。
   */
  it("削除に成功すると手動リロードなしで一覧から行が消える(issue #1361 AC1)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({});
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    expect(screen.getByText("production-deploy")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(deleteMock).toHaveBeenCalledWith(1);
      expect(screen.queryByText("production-deploy")).not.toBeInTheDocument();
    });
    expect(screen.getByText("保存済みのSSH鍵ペアはありません。")).toBeInTheDocument();
  });

  it("削除に成功した鍵ペアだけが一覧から消え、他の行は残る(issue #1361)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({});
    const other = keyPair({ id: 2, name: "other-pair" });
    render(<SshKeyPairsPanel keyPairs={[keyPair(), other]} personalTimeZone={null} />);

    fireEvent.click(screen.getAllByRole("button", { name: "削除" })[0]);

    await waitFor(() => {
      expect(deleteMock).toHaveBeenCalledWith(1);
      expect(screen.queryByText("production-deploy")).not.toBeInTheDocument();
    });
    expect(screen.getByText("other-pair")).toBeInTheDocument();
  });

  it("個人設定TZが設定されているとき、作成日時はformatDateTimeにそのTZを渡す(issue #1362、gateなし)", () => {
    const pair = keyPair({ createdAt: "2026-09-08T20:03:35" });
    render(<SshKeyPairsPanel keyPairs={[pair]} personalTimeZone="Asia/Tokyo" />);

    expect(formatDateTime).toHaveBeenCalledWith(pair.createdAt, "Asia/Tokyo");
    expect(screen.getByText("FORMATTED_CREATED_AT")).toBeInTheDocument();
  });

  // 「マウント前は固定プレースホルダーを表示する」は SshKeyPairsPanel.mountGate.test.tsx で
  // 検証する(このファイルで検証しない理由は同ファイルの先頭コメント参照)。

  it("個人設定TZが未設定のとき、マウント後はformatDateTimeをTZ引数無しで呼ぶ(issue #1362)", () => {
    const pair = keyPair({ createdAt: "2026-09-08T20:03:35" });
    render(<SshKeyPairsPanel keyPairs={[pair]} personalTimeZone={null} />);

    expect(formatDateTime).toHaveBeenCalledWith(pair.createdAt);
    expect(screen.getByText("FORMATTED_CREATED_AT")).toBeInTheDocument();
  });

  /**
   * issue #1413 の対の観点。マウント前はボタンを `disabled` にするが、
   * マウント後(=ハイドレーション完了後)は従来どおり押せなければならない。
   * この2件が揃って初めて「押せない時間帯だけを塞いだ」と言える
   * (マウント前の確認は `SshKeyPairsPanel.mountGate.test.tsx` にある)。
   */
  it("マウント後は生成ボタンを押せる(issue #1413で塞ぐのはハイドレーション前だけ)", () => {
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(screen.getByRole("button", { name: "SSH鍵ペアを生成" })).toBeEnabled();
  });
});
