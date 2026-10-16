import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// 自前の Server Action(../../../actions)・@/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
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

/**
 * バックエンド(gateway の向こう)の代役 `jest.fn()` を HTTP の応答にする。解決値は 200 の JSON、
 * Error の拒否は 502 の応答、Error 以外の拒否は通信そのものの失敗(fetch が値を投げる)になる。
 */
function route(backend: jest.Mock) {
  return async (_url: string, _init: RequestInit, params: string[]) => {
    try {
      return jsonResponse(await backend(...params.map(Number)));
    } catch (err) {
      if (err instanceof Error) return textResponse(err.message, 502);
      throw err;
    }
  };
}
import { LetsblogSyncPanel } from "../LetsblogSyncPanel";

/** バックエンドの代役。未同期は応答本文が空になる。 */
const getSync = jest.fn();
const resync = jest.fn();

const synced = { status: "SYNCED", error: null, hash: "0123456789abcdef", syncedAt: "2026-10-04T00:00:00Z" };
const failed = { status: "FAILED", error: "wp-cliが失敗しました", hash: null, syncedAt: "2026-10-04T00:00:00Z" };
const skipped = { status: "SKIPPED", error: "プラグインが未導入のため送信しませんでした", hash: null, syncedAt: "2026-10-04T00:00:00Z" };

describe("LetsblogSyncPanel(issue #1558)", () => {
  beforeEach(() => {
    getSync.mockReset();
    resync.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "GET /api/sites/:id/letsblog-sync": async (url, init, params) => {
        const res = await route(getSync)(url, init, params);
        // 未同期(null)のとき、バックエンドは空の本文を返す。
        return (await res.text()) === "null" ? textResponse("", 200) : res;
      },
      "POST /api/sites/:id/letsblog-sync": route(resync),
    });
  });

  it("取得中はその旨を表示する", () => {
    getSync.mockReturnValue(new Promise(() => {}));
    render(<LetsblogSyncPanel siteId={3} />);
    expect(screen.getByText("同期状態を確認中…")).toBeInTheDocument();
  });

  it("同期したことがなければ未同期と表示する", async () => {
    getSync.mockResolvedValue(null);
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("未同期")).toBeInTheDocument();
    expect(getSync).toHaveBeenCalledWith(3);
  });

  it("同期済みならハッシュの先頭を表示し、エラーは出さない", async () => {
    getSync.mockResolvedValue(synced);
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("同期済み(01234567)")).toBeInTheDocument();
    expect(screen.queryByTestId("letsblog-sync-error")).toBeNull();
  });

  it("失敗ならエラーの内容を表示する", async () => {
    getSync.mockResolvedValue(failed);
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("同期失敗")).toBeInTheDocument();
    expect(screen.getByTestId("letsblog-sync-error")).toHaveTextContent("wp-cliが失敗しました");
  });

  it("見送りなら理由を表示する", async () => {
    getSync.mockResolvedValue(skipped);
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("見送り")).toBeInTheDocument();
    expect(screen.getByTestId("letsblog-sync-error")).toHaveTextContent("未導入");
  });

  it("再同期すると結果の状態に変わり、エラーが消える", async () => {
    getSync.mockResolvedValue(failed);
    resync.mockResolvedValue(synced);
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("同期失敗")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "再同期" }));

    expect(await screen.findByText("同期済み(01234567)")).toBeInTheDocument();
    expect(resync).toHaveBeenCalledWith(3);
    expect(screen.queryByTestId("letsblog-sync-error")).toBeNull();
  });

  it("再同期に失敗したらエラーを表示し、状態は変えない", async () => {
    getSync.mockResolvedValue(failed);
    resync.mockRejectedValue(new Error("upstream"));
    render(<LetsblogSyncPanel siteId={3} />);
    await userEvent.click(await screen.findByRole("button", { name: "再同期" }));
    await waitFor(() => expect(screen.getByText(/再同期に失敗しました/)).toBeInTheDocument());
    expect(screen.getByText("同期失敗")).toBeInTheDocument();
  });

  it("再同期に失敗したのが Error でなくても表示する", async () => {
    getSync.mockResolvedValue(failed);
    resync.mockRejectedValue("nope");
    render(<LetsblogSyncPanel siteId={3} />);
    await userEvent.click(await screen.findByRole("button", { name: "再同期" }));
    expect(await screen.findByText(/nope/)).toBeInTheDocument();
  });

  it("状態を取得できなかったときはエラーを表示する", async () => {
    getSync.mockRejectedValue(new Error("bad gateway"));
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText(/同期状態を取得できませんでした/)).toBeInTheDocument();
    expect(screen.getByText(/bad gateway/)).toBeInTheDocument();
  });

  it("取得に失敗したのが Error でなくても表示する", async () => {
    getSync.mockRejectedValue("boom");
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
  });

  it("アンマウント後に取得が完了しても状態を更新しない", async () => {
    let resolve: (v: unknown) => void = () => {};
    getSync.mockReturnValue(new Promise((r) => (resolve = r)));
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    const { unmount } = render(<LetsblogSyncPanel siteId={3} />);
    unmount();
    resolve(synced);
    await Promise.resolve();
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("アンマウント後に取得が失敗してもエラーを更新しない", async () => {
    let reject: (e: unknown) => void = () => {};
    getSync.mockReturnValue(new Promise((_, r) => (reject = r)));
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    const { unmount } = render(<LetsblogSyncPanel siteId={3} />);
    unmount();
    reject(new Error("x"));
    await Promise.resolve();
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("同期済みでもハッシュが無ければ空のハッシュで表示する", async () => {
    getSync.mockResolvedValue({ status: "SYNCED", error: null, hash: null, syncedAt: null });
    render(<LetsblogSyncPanel siteId={3} />);
    expect(await screen.findByText("同期済み()")).toBeInTheDocument();
  });
});
