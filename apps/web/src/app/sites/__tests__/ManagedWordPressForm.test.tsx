import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient、React の useActionState は差し替えない。
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
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ManagedWordPressForm } from "../ManagedWordPressForm";

const JOBS = "POST /api/sites/managed-wordpress/jobs";
/** ジョブ受理 API の応答。テストごとに差し替える。 */
let accept: () => Promise<Response> | Response;

function fillAndSubmit(container: HTMLElement) {
  const set = (name: string, value: string) =>
    fireEvent.change(container.querySelector(`[name="${name}"]`) as HTMLInputElement, { target: { value } });
  set("managedName", "My Blog");
  set("managedSiteKey", "main");
  set("managedTitle", "My Blog");
  set("managedAdminUser", "admin");
  set("managedAdminEmail", "a@example.com");
  set("managedAdminPassword", "secret-pass");
  fireEvent.submit(container.querySelector("form") as HTMLFormElement);
}

const users = [{ id: 1, email: "u@example.com" }] as never;

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  accept = () => jsonResponse({ id: 3, status: "running" });
  installFetch({ [JOBS]: () => accept() });
});

describe("ManagedWordPressForm(issue #1696: ジョブ受理の非同期)", () => {
  it("受理されたら、処理キューに追加された旨を示し、完了したとは示さない", async () => {
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    fillAndSubmit(container);
    expect(await screen.findByText(/構築を要求しました。処理キューに追加されました/)).toBeInTheDocument();
    expect(screen.queryByText("構築しました。")).toBeNull();
  });

  it("受理後も構築ボタンは押せる状態のままで、長時間の待機表示にならない", async () => {
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    fillAndSubmit(container);
    await screen.findByText(/構築を要求しました/);
    const button = screen.getByRole("button", { name: "構築する" });
    expect(button).toBeEnabled();
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull();
  });

  it("エラーは赤字で示し、受理の表示は出さない", async () => {
    accept = () => textResponse("失敗しました", 500);
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    fillAndSubmit(container);
    expect(await screen.findByText("APIエラー (500): 失敗しました")).toBeInTheDocument();
    expect(screen.queryByText(/構築を要求しました/)).toBeNull();
  });

  it("受理の往復の間だけ送信ボタンを無効にする", async () => {
    let release: (r: Response) => void = () => {};
    accept = () => new Promise<Response>((resolve) => (release = resolve));
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    fillAndSubmit(container);
    expect(await screen.findByRole("button", { name: /要求中/ })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({ id: 3, status: "running" }));
    });
    expect(await screen.findByRole("button", { name: "構築する" })).toBeEnabled();
  });

  it("成功したらフォームを初期状態へ戻す", async () => {
    const { container } = render(<ManagedWordPressForm users={users} templateCandidates={[]} />);
    const name = container.querySelector('input[name="managedName"]') as HTMLInputElement;
    fireEvent.change(name, { target: { value: "入力済み" } });
    expect(name.value).toBe("入力済み");
    fillAndSubmit(container);
    await screen.findByText(/構築を要求しました/);
    await waitFor(() => expect(name.value).toBe(""));
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

  it("選択肢の値から該当するユーザーを引けないときも何も補完しない", () => {
    // 数値に変換すると id と一致しない(1.0 → 1)ユーザー。選択肢は存在するが、ユーザーとして引けない。
    const odd = [{ id: "1.0", email: "x@example.com" }] as never;
    const { container } = render(<ManagedWordPressForm users={odd} templateCandidates={[]} />);
    const pick = container.querySelector("select:not([name])") as HTMLSelectElement;
    fireEvent.change(pick, { target: { value: "1.0" } });
    expect((container.querySelector('input[name="managedAdminUser"]') as HTMLInputElement).value).toBe("");
  });

  it("テンプレートサイトの候補を選択肢に並べる", () => {
    const templates = [{ id: 5, name: "Tpl", siteKey: "tpl" }] as never;
    render(<ManagedWordPressForm users={[]} templateCandidates={templates} />);
    expect(screen.getByRole("option", { name: "Tpl(tpl)" })).toBeInTheDocument();
  });
});
