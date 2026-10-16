import { act, fireEvent, render, screen } from "@testing-library/react";

/**
 * 自前の Server Action(../../../actions)・@/lib/session・@/lib/apiClient は差し替えない。
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
import { InstallWpCliButton } from "../InstallWpCliButton";

let respond: () => Promise<Response> | Response;
const installed: number[] = [];

beforeEach(() => {
  installed.length = 0;
  resetServerRuntime(ADMIN_SESSION);
  respond = () => jsonResponse({ message: "wp-cliをインストールしました" });
  installFetch({
    "POST /api/sites/:id/install-wp-cli": (_u, _i, [id]) => {
      installed.push(Number(id));
      return respond();
    },
  });
});

describe("InstallWpCliButton", () => {
  it("押すとそのサイトの wp-cli をインストールし、結果のメッセージを示す", async () => {
    render(<InstallWpCliButton id={7} />);
    fireEvent.click(screen.getByRole("button", { name: "wp-cliをインストール" }));
    expect(await screen.findByText("wp-cliをインストールしました")).toBeInTheDocument();
    expect(installed).toEqual([7]);
  });

  it("インストール中は押せず、その旨を示す", async () => {
    let release: (r: Response) => void = () => {};
    respond = () => new Promise<Response>((resolve) => (release = resolve));
    render(<InstallWpCliButton id={7} />);
    fireEvent.click(screen.getByRole("button", { name: "wp-cliをインストール" }));
    expect(await screen.findByRole("button", { name: "インストール中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({ message: "完了" }));
    });
    expect(await screen.findByText("完了")).toBeInTheDocument();
  });

  it("失敗したら理由を示し、再び押すと前の失敗を消してやり直す", async () => {
    respond = () => textResponse("SSH接続に失敗", 500);
    render(<InstallWpCliButton id={7} />);
    fireEvent.click(screen.getByRole("button", { name: "wp-cliをインストール" }));
    expect(await screen.findByText("APIエラー (500): SSH接続に失敗")).toBeInTheDocument();

    respond = () => jsonResponse({ message: "やり直しました" });
    fireEvent.click(screen.getByRole("button", { name: "wp-cliをインストール" }));
    expect(await screen.findByText("やり直しました")).toBeInTheDocument();
    expect(screen.queryByText("APIエラー (500): SSH接続に失敗")).toBeNull();
  });

  it("Error 以外の値で通信が失敗しても文字列にして示す", async () => {
    installFetch({
      "POST /api/sites/:id/install-wp-cli": () => {
        throw "切断";
      },
    });
    render(<InstallWpCliButton id={7} />);
    fireEvent.click(screen.getByRole("button", { name: "wp-cliをインストール" }));
    expect(await screen.findByText("切断")).toBeInTheDocument();
  });
});
