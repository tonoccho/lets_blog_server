import { fireEvent, render, screen } from "@testing-library/react";

type State = { error?: string; success?: boolean; jobId?: number };
let mockState: State = {};
let mockPending = false;
jest.mock("../actions", () => ({ createManagedWordPressSiteAction: jest.fn() }));
jest.mock("react", () => ({
  ...jest.requireActual("react"),
  useActionState: () => [mockState, jest.fn(), mockPending],
}));

import { ManagedWordPressForm } from "../ManagedWordPressForm";

const users = [{ id: 1, email: "u@example.com" }] as never;

beforeEach(() => {
  mockState = {};
  mockPending = false;
});

describe("ManagedWordPressForm(issue #1696: ジョブ受理の非同期)", () => {
  it("受理されたら、処理キューに追加された旨を示し、完了したとは示さない", () => {
    mockState = { success: true, jobId: 3 };
    render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    expect(screen.getByText(/構築を要求しました。処理キューに追加されました/)).toBeInTheDocument();
    expect(screen.queryByText("構築しました。")).toBeNull();
  });

  it("受理後も構築ボタンは押せる状態のままで、長時間の待機表示にならない", () => {
    mockState = { success: true, jobId: 3 };
    render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    const button = screen.getByRole("button", { name: "構築する" });
    expect(button).toBeEnabled();
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull();
  });

  it("エラーは赤字で示し、受理の表示は出さない", () => {
    mockState = { error: "失敗しました" };
    render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    expect(screen.getByText("失敗しました")).toBeInTheDocument();
    expect(screen.queryByText(/構築を要求しました/)).toBeNull();
  });

  it("受理の往復の間だけ送信ボタンを無効にする", () => {
    mockPending = true;
    render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    expect(screen.getByRole("button", { name: /要求中/ })).toBeDisabled();
  });

  it("成功したらフォームを初期状態へ戻す", () => {
    const { container, rerender } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    const name = container.querySelector('input[name="managedName"]') as HTMLInputElement;
    fireEvent.change(name, { target: { value: "入力済み" } });
    expect(name.value).toBe("入力済み");
    mockState = { success: true, jobId: 3 };
    rerender(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    expect(name.value).toBe("");
  });

  it("サーバー登録ユーザーを選ぶと、管理者ユーザー名とメールアドレスを補完する", () => {
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    const pick = container.querySelector("select:not([name])") as HTMLSelectElement;
    fireEvent.change(pick, { target: { value: "1" } });
    expect((container.querySelector('input[name="managedAdminUser"]') as HTMLInputElement).value).toBe("u");
    expect((container.querySelector('input[name="managedAdminEmail"]') as HTMLInputElement).value).toBe("u@example.com");
  });

  it("ユーザー名に使えない文字は取り除き、@の無いメールは全体を使う", () => {
    const odd = [{ id: 2, email: "a+b c@example.com" }, { id: 3, email: "plain" }] as never;
    const { container } = render(<ManagedWordPressForm users={odd} templateCandidates={[]} />);
    const pick = container.querySelector("select:not([name])") as HTMLSelectElement;
    const name = container.querySelector('input[name="managedAdminUser"]') as HTMLInputElement;
    fireEvent.change(pick, { target: { value: "2" } });
    expect(name.value).toBe("abc");
    fireEvent.change(pick, { target: { value: "3" } });
    expect(name.value).toBe("plain");
  });

  it("未選択に戻した場合と、知らないユーザーIDでは何も補完しない", () => {
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    const pick = container.querySelector("select:not([name])") as HTMLSelectElement;
    const name = container.querySelector('input[name="managedAdminUser"]') as HTMLInputElement;
    fireEvent.change(pick, { target: { value: "" } });
    expect(name.value).toBe("");
    fireEvent.change(pick, { target: { value: "999" } });
    expect(name.value).toBe("");
  });

  it("テンプレートサイトの候補を選択肢に並べる", () => {
    const templates = [{ id: 5, name: "Tpl", siteKey: "tpl" }] as never;
    render(<ManagedWordPressForm users={[]} templateCandidates={templates} />);
    expect(screen.getByRole("option", { name: "Tpl(tpl)" })).toBeInTheDocument();
  });
});
