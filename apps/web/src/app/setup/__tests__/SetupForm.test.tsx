import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SetupForm } from "../SetupForm";

/**
 * issue #1051: SetupFormはuseActionState経由でsetupActionへ<form action={formAction}>を
 * 直結する形に変更した(JS無効時のネイティブGETフォールバックによる資格情報のURL漏洩対策)。
 * このテストはその変更で生じた分岐(送信前/成功/失敗の3状態の出し分け)を確認する。
 *
 * 自前の Server Action(../actions)・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1832)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";

const SETUP = "POST /api/auth/setup";
let fetchMock: jest.Mock;
const setupCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/api/auth/setup"));

describe("SetupForm", () => {
  beforeEach(() => {
    resetServerRuntime(null);
    fetchMock = installFetch({ [SETUP]: () => jsonResponse({ id: 1, email: "admin@example.com", role: "admin" }, 201) });
  });

  it("送信前はフォームを表示し、成功メッセージは表示しない", () => {
    render(<SetupForm />);

    expect(screen.getByRole("button", { name: "管理者アカウントを作成" })).toBeInTheDocument();
    expect(screen.queryByText("管理者アカウントを作成しました。")).not.toBeInTheDocument();
  });

  it("送信に失敗するとエラーメッセージを表示し、フォームのままにする", async () => {
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
    // 8文字未満はサーバーへ送る前に弾かれる。
    expect(setupCalls()).toHaveLength(0);
  });

  it("送信に成功すると成功メッセージへ切り替わり、フォームを消す", async () => {
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
    expect(setupCalls()).toHaveLength(1);
    expect(JSON.parse(String(setupCalls()[0][1].body))).toEqual({ email: "admin@example.com", password: "password1234" });
  });

  it("サーバーが作成を拒否したら、その理由を表示してフォームのままにする", async () => {
    installFetch({ [SETUP]: () => textResponse("既にセットアップ済みです", 409) });
    render(<SetupForm />);

    fireEvent.change(screen.getByLabelText("メールアドレス"), { target: { value: "admin@example.com" } });
    fireEvent.change(screen.getByLabelText("パスワード(8文字以上)"), { target: { value: "password1234" } });
    fireEvent.click(screen.getByRole("button", { name: "管理者アカウントを作成" }));

    await waitFor(() => {
      expect(screen.getByText(/既にセットアップ済みです/)).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "管理者アカウントを作成" })).toBeInTheDocument();
  });
});

/**
 * <form>が実際にネイティブGETへフォールバックしないこと(method/actionの実効性)は、
 * Next.jsのServer Action progressive enhancementというフレームワーク側の配線に依存し、
 * jsdom上のReactコンポーネントテストでは再現できない(RTL/jsdomはNext.jsのビルド・実行時
 * インスツルメンテーションを経由しない)。この境界の確認は
 * `apps/web/e2e/features/auth/setup.feature`(javaScriptEnabled:falseでの実ブラウザ検証)が担う。
 */
