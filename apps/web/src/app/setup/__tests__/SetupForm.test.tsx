import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SetupForm } from "../SetupForm";
import { setupAction } from "../actions";

/**
 * issue #1051: SetupFormはuseActionState経由でsetupActionへ<form action={formAction}>を
 * 直結する形に変更した(JS無効時のネイティブGETフォールバックによる資格情報のURL漏洩対策)。
 * このテストはその変更で生じた分岐(送信前/成功/失敗の3状態の出し分け)を確認する。
 */
jest.mock("../actions", () => ({
  setupAction: jest.fn(),
}));

const setupActionMock = setupAction as jest.MockedFunction<typeof setupAction>;

describe("SetupForm", () => {
  beforeEach(() => {
    setupActionMock.mockReset();
  });

  it("送信前はフォームを表示し、成功メッセージは表示しない", () => {
    render(<SetupForm />);

    expect(screen.getByRole("button", { name: "管理者アカウントを作成" })).toBeInTheDocument();
    expect(screen.queryByText("管理者アカウントを作成しました。")).not.toBeInTheDocument();
  });

  it("送信に失敗するとエラーメッセージを表示し、フォームのままにする", async () => {
    setupActionMock.mockResolvedValue({ error: "パスワードは8文字以上である必要があります。" });
    render(<SetupForm />);

    fireEvent.change(screen.getByLabelText("メールアドレス"), {
      target: { value: "admin@example.com" },
    });
    fireEvent.change(screen.getByLabelText("パスワード(8文字以上)"), {
      target: { value: "short" },
    });
    fireEvent.click(screen.getByRole("button", { name: "管理者アカウントを作成" }));

    await waitFor(() => {
      expect(screen.getByText("パスワードは8文字以上である必要があります。")).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "管理者アカウントを作成" })).toBeInTheDocument();
    expect(setupActionMock).toHaveBeenCalledTimes(1);
  });

  it("送信に成功すると成功メッセージへ切り替わり、フォームを消す", async () => {
    setupActionMock.mockResolvedValue({});
    render(<SetupForm />);

    fireEvent.change(screen.getByLabelText("メールアドレス"), {
      target: { value: "admin@example.com" },
    });
    fireEvent.change(screen.getByLabelText("パスワード(8文字以上)"), {
      target: { value: "password1234" },
    });
    fireEvent.click(screen.getByRole("button", { name: "管理者アカウントを作成" }));

    await waitFor(() => {
      expect(screen.getByText("管理者アカウントを作成しました。")).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "管理者アカウントを作成" })).not.toBeInTheDocument();
  });
});

/**
 * <form>が実際にネイティブGETへフォールバックしないこと(method/actionの実効性)は、
 * Next.jsのServer Action progressive enhancementというフレームワーク側の配線に依存し、
 * jsdom上のReactコンポーネントテストでは再現できない(RTL/jsdomはNext.jsのビルド・実行時
 * インスツルメンテーションを経由しない)。この境界の確認は
 * `apps/web/e2e/features/auth/setup.feature`(javaScriptEnabled:falseでの実ブラウザ検証)が担う。
 */
