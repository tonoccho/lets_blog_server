import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { UserForm } from "../UserForm";

/**
 * ユーザー追加フォーム。useActionState 経由で createUserAction へ <form action> を直結している。
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
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

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const CREATE = "POST /api/users";
let fetchMock: jest.Mock;
const createCalls = () =>
  fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith("/api/users") && init?.method === "POST");

function fill(email: string, password: string, role?: string) {
  fireEvent.change(screen.getByLabelText("メールアドレス"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("初期パスワード"), { target: { value: password } });
  if (role) fireEvent.change(screen.getByLabelText("権限"), { target: { value: role } });
}

describe("UserForm", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [CREATE]: () => jsonResponse({ id: 5 }, 201) });
  });

  it("送信前はフォームを表示し、結果のメッセージは出さない。権限の既定は user", () => {
    render(<UserForm />);

    expect(screen.getByRole("button", { name: "追加" })).toBeEnabled();
    expect(screen.queryByText("追加しました。")).not.toBeInTheDocument();
    expect((screen.getByLabelText("権限") as HTMLSelectElement).value).toBe("user");
  });

  it("送信に成功すると成功メッセージを出し、入力欄を空に戻す", async () => {
    render(<UserForm />);

    fill("new@example.com", "pass1234", "admin");
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    await screen.findByText("追加しました。");
    expect(JSON.parse(String(createCalls()[0][1].body))).toEqual({
      email: "new@example.com",
      password: "pass1234",
      role: "admin",
    });
    await waitFor(() => expect((screen.getByLabelText("メールアドレス") as HTMLInputElement).value).toBe(""));
    expect((screen.getByLabelText("初期パスワード") as HTMLInputElement).value).toBe("");
  });

  it("サーバーが拒否したら理由を表示し、成功メッセージは出さない", async () => {
    fetchMock = installFetch({ [CREATE]: () => textResponse("duplicate", 409) });
    render(<UserForm />);

    fill("dup@example.com", "pass1234");
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    await screen.findByText("APIエラー (409): duplicate");
    expect(screen.queryByText("追加しました。")).not.toBeInTheDocument();
    expect(createCalls()).toHaveLength(1);
  });

  it("空白だけのパスワードはサーバーへ送らず、入力を求める", async () => {
    render(<UserForm />);

    fill("new@example.com", "   ");
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    await screen.findByText("メールアドレスとパスワードを入力してください。");
    expect(createCalls()).toHaveLength(0);
  });

  it("送信中はボタンを無効にして「追加中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({ [CREATE]: () => new Promise<Response>((resolve) => (release = resolve)) });
    render(<UserForm />);

    fill("new@example.com", "pass1234");
    fireEvent.click(screen.getByRole("button", { name: "追加" }));

    const pending = await screen.findByRole("button", { name: "追加中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse({ id: 5 }, 201));
    await screen.findByText("追加しました。");
  });
});
