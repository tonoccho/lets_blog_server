import { fireEvent, render, screen } from "@testing-library/react";
import { BackupPanel } from "../BackupPanel";

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
import { ADMIN_SESSION, attachFile } from "@/lib/__tests__/testUtils/webAdminRuntime";

const RESTORE = "POST /api/backup/restore";
let fetchMock: jest.Mock;
const restores = () =>
  fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith("/api/backup/restore") && init?.method === "POST");

function chooseFile() {
  attachFile(
    screen.getByLabelText("バックアップファイル(.zip)") as HTMLInputElement,
    new File([new ArrayBuffer(8)], "backup.zip", { type: "application/zip" })
  );
}

describe("BackupPanel", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [RESTORE]: () => jsonResponse(null) });
    window.confirm = jest.fn().mockReturnValue(true);
  });

  it("ダウンロードのリンクと、リストアの警告を表示する", () => {
    render(<BackupPanel />);

    expect(screen.getByRole("link", { name: "ダウンロード" })).toHaveAttribute("href", "/admin/backup/download");
    expect(screen.getByText(/警告: リストアを実行すると/)).toBeInTheDocument();
    expect(screen.queryByText("リストアが完了しました。")).not.toBeInTheDocument();
  });

  it("確認ダイアログを承認するとリストアを送り、完了を表示する", async () => {
    render(<BackupPanel />);

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "リストアを実行" }));

    expect(await screen.findByText("リストアが完了しました。")).toBeInTheDocument();
    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(restores()).toHaveLength(1);
    expect(((restores()[0][1].body as FormData).get("file") as File).name).toBe("backup.zip");
  });

  it("確認ダイアログでキャンセルするとリストアは送られない", async () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    render(<BackupPanel />);

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "リストアを実行" }));

    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(restores()).toHaveLength(0);
    expect(screen.queryByText("リストアが完了しました。")).not.toBeInTheDocument();
  });

  it("ファイルを選ばずに送るとサーバーへは送らず、選択を求める", async () => {
    render(<BackupPanel />);
    const form = screen.getByRole("button", { name: "リストアを実行" }).closest("form") as HTMLFormElement;

    // `required` の検証はブラウザが行う。ここでは検証を通り抜けた送信を直接再現する。
    fireEvent.submit(form);

    expect(await screen.findByText("バックアップファイルを選択してください。")).toBeInTheDocument();
    expect(restores()).toHaveLength(0);
  });

  it("サーバーが拒否したら理由を表示する", async () => {
    installFetch({ [RESTORE]: () => textResponse("キーが一致しません", 409) });
    render(<BackupPanel />);

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "リストアを実行" }));

    expect(await screen.findByText("APIエラー (409): キーが一致しません")).toBeInTheDocument();
    expect(screen.queryByText("リストアが完了しました。")).not.toBeInTheDocument();
  });

  it("リストア中はボタンを無効にして「リストア中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({ [RESTORE]: () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response });
    render(<BackupPanel />);

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "リストアを実行" }));

    const pending = await screen.findByRole("button", { name: "リストア中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse(null));
    await screen.findByText("リストアが完了しました。");
  });
});
