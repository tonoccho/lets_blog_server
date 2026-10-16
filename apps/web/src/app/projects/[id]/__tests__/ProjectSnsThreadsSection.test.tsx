import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsThreadsSection } from "../ProjectSnsThreadsSection";
import type { XConnectionView } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies, calledRequests, failFetchWith, failWith, installBackend, jsonResponse, noContent } from "./projectCoreRuntime";

/**
 * issue #1579: 設定画面の Threads 欄。接続・切断・テスト投稿・接続状態・告知履歴(更新できず投稿しなかった理由を含む)を示す。
 * 本番サイトに届かないときは「取得できない」と示し、アプリシークレットやトークンは再表示しない。
 * Server Action(`../snsThreadsActions`)・`@/lib/apiClient`・`@/lib/session` は実物を通し、差し替えるのは
 * Next.js の実行基盤と外部との通信(`fetch`)だけ(#1838)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

const CALLBACK_URL = "https://localhost/connect/threads/callback";
const AUTHORIZE = "POST /api/projects/5/sns/threads/authorize";
const TEST = "POST /api/projects/5/sns/threads/test";
const DISCONNECT = "DELETE /api/projects/5/sns/threads";

const connectedView: XConnectionView = {
  connectable: true,
  reason: null,
  siteName: "本番サイト",
  status: { available: true, state: "CONNECTED", accountName: "lets_blog", error: null },
  log: {
    available: true,
    error: null,
    entries: [
      { kind: "test", success: true, error: null, at: "2026-10-04T00:00:00+00:00" },
      {
        kind: "publish",
        success: false,
        error: "Threads のトークンを更新できません(発行から24時間以上経過している必要があります)",
        at: "2026-10-04T01:00:00+00:00",
      },
    ],
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsThreadsSection>> = {}) {
  return render(
    <ProjectSnsThreadsSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} {...props} />
  );
}

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  process.env.NEXTAUTH_URL = "https://localhost";
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    // 認可の開始は、既定では本番サイトに拒否された状態にする(成功は redirect で画面を離れる)。
    [AUTHORIZE]: failWith(400, "本番サイトが設定されていません"),
    [TEST]: () => jsonResponse({ success: true, error: null }),
    [DISCONNECT]: noContent,
  });
});

describe("ProjectSnsThreadsSection", () => {
  it("見出しと、Threads のアプリに登録するリダイレクトURLを示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "Threads", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(CALLBACK_URL)).toBeInTheDocument();
  });

  it("接続済みなら接続済みとアカウント名を示し、本番サイト名も示す", () => {
    renderSection();

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("@lets_blog");
    expect(screen.getByText(/本番サイト/)).toBeInTheDocument();
    expect(screen.queryByTestId("sns-threads-reason")).not.toBeInTheDocument();
  });

  it("アカウント名が無い接続済みでも接続済みとだけ示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "CONNECTED", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-threads-status")).not.toHaveTextContent("@");
  });

  it("未設定なら未接続と示し、テスト投稿と切断はできない", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("未接続");
    expect(screen.getByRole("button", { name: "Threads 投稿テスト" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Threads を切断" })).toBeDisabled();
  });

  it("要再接続ならそう示し、切断はできる", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "RECONNECT", accountName: "a", error: null } },
    });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("要再接続");
    expect(screen.getByRole("button", { name: "Threads を切断" })).toBeEnabled();
  });

  it("接続状態を取得できなければ「取得できない」と示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: false, state: null, accountName: null, error: "接続失敗" } },
    });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("接続失敗");
  });

  it("接続状態を取得できず理由も無ければ「取得できない」とだけ示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: false, state: null, accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent(/^取得できない$/);
  });

  it("接続できないときは理由を示し、接続ボタンは押せない", () => {
    renderSection({
      view: { connectable: false, reason: "本番サイトが設定されていません", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-threads-reason")).toHaveTextContent("本番サイトが設定されていません");
    expect(screen.getByRole("button", { name: "Threads と接続" })).toBeDisabled();
    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("未接続");
  });

  it("画面の情報そのものを取得できなければ、状態も履歴も「取得できない」と示して接続は止める", () => {
    renderSection({ view: null });

    expect(screen.getByTestId("sns-threads-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-threads-log")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "Threads と接続" })).toBeDisabled();
  });

  it("告知履歴を種類・成功/失敗・理由つきで示す(更新できず投稿しなかった理由)", () => {
    renderSection();

    const entries = screen.getAllByTestId("sns-threads-log-entry");
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent("テスト投稿");
    expect(entries[0]).toHaveTextContent("成功");
    expect(entries[1]).toHaveTextContent("記事公開");
    expect(entries[1]).toHaveTextContent("失敗");
    expect(entries[1]).toHaveTextContent("24時間以上経過している必要があります");
  });

  it("知らない種類の履歴はそのままの名前で示し、失敗の理由が無ければ失敗とだけ示す", () => {
    renderSection({
      view: {
        ...connectedView,
        log: { available: true, error: null, entries: [{ kind: "milestone", success: false, error: null, at: "a" }] },
      },
    });

    const entry = screen.getByTestId("sns-threads-log-entry");
    expect(entry).toHaveTextContent("milestone");
    expect(entry).toHaveTextContent("失敗");
    expect(entry).not.toHaveTextContent("失敗:");
  });

  it("履歴が空ならまだ無いと示す", () => {
    renderSection({ view: { ...connectedView, log: { available: true, entries: [], error: null } } });

    expect(screen.getByTestId("sns-threads-log")).toHaveTextContent("まだありません");
  });

  it("履歴を取得できなければ「取得できない」と示す", () => {
    renderSection({ view: { ...connectedView, log: { available: false, entries: [], error: "届かない" } } });

    expect(screen.getByTestId("sns-threads-log")).toHaveTextContent("取得できない");
    expect(screen.queryByTestId("sns-threads-log-entry")).not.toBeInTheDocument();
  });

  it("履歴が null(接続できない状態)なら履歴は空として示す", () => {
    renderSection({
      view: { connectable: false, reason: "未導入", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-threads-log")).toHaveTextContent("まだありません");
  });

  it("アプリの情報を入れて押すと、認可の開始がバックエンドへ送られる", async () => {
    const user = userEvent.setup();
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    await user.type(document.querySelector('input[name="threadsAppId"]') as HTMLInputElement, "app-id");
    await user.type(document.querySelector('input[name="threadsAppSecret"]') as HTMLInputElement, "app-secret");
    await user.click(screen.getByRole("button", { name: "Threads と接続" }));

    expect(await screen.findByText(/本番サイトが設定されていません/)).toBeInTheDocument();
    expect(bodies(backend, AUTHORIZE)).toEqual([
      { clientId: "app-id", clientSecret: "app-secret", redirectUri: CALLBACK_URL },
    ]);
  });

  it("アプリシークレットは伏せ字の入力欄で、保存済みの値を再表示しない", () => {
    renderSection();

    expect(document.querySelector('input[name="threadsAppSecret"]')).toHaveAttribute("type", "password");
    expect(document.querySelector('input[name="threadsAppSecret"]')).toHaveValue("");
    expect(document.querySelector('input[name="threadsAppId"]')).toHaveValue("");
  });

  it("認可の開始に失敗したら理由を示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.type(document.querySelector('input[name="threadsAppId"]') as HTMLInputElement, "app-id");
    await user.type(document.querySelector('input[name="threadsAppSecret"]') as HTMLInputElement, "app-secret");
    await user.click(screen.getByRole("button", { name: "Threads と接続" }));

    expect(await screen.findByText("APIエラー (400): 本番サイトが設定されていません")).toBeInTheDocument();
  });

  it("入力が空のまま押すと、バックエンドへ送らず入力を求める", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads と接続" }));

    expect(await screen.findByText(/アプリID.*を入力してください/)).toBeInTheDocument();
    expect(bodies(backend, AUTHORIZE)).toEqual([]);
  });

  it("接続済みでテスト投稿を押すと、投稿がバックエンドへ送られ成功を示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads 投稿テスト" }));

    expect(await screen.findByText("Threads へ投稿テストを送りました。")).toBeInTheDocument();
    expect(calledRequests(backend)).toEqual([TEST]);
  });

  it("テスト投稿が失敗したら理由を示す", async () => {
    installBackend({ [TEST]: () => jsonResponse({ success: false, error: "Threads の投稿に失敗しました(HTTP 403)" }) });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads 投稿テスト" }));

    expect(await screen.findByText(/HTTP 403/)).toBeInTheDocument();
  });

  it("接続済みで切断を押すと、切断がバックエンドへ送られ切断したことを示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads を切断" }));

    expect(await screen.findByText("Threads の接続を切断しました。")).toBeInTheDocument();
    expect(calledRequests(backend)).toEqual([DISCONNECT]);
  });

  it("切断に失敗したら理由を示す", async () => {
    installBackend({ [DISCONNECT]: failWith(502, "本番サイトに届かない") });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads を切断" }));

    expect(await screen.findByText("APIエラー (502): 本番サイトに届かない")).toBeInTheDocument();
  });

  it("通信そのものが失敗しても、テスト投稿の失敗の理由を示す", async () => {
    failFetchWith("boom");
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Threads 投稿テスト" }));

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });

  it("接続完了と失敗のバナーを示す", () => {
    const { rerender } = renderSection({ connectedBanner: true });
    expect(screen.getByText("Threads アカウントを接続しました。")).toBeInTheDocument();

    rerender(
      <ProjectSnsThreadsSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} errorBanner="invalid_state" />
    );
    expect(screen.getByText(/接続に失敗しました: invalid_state/)).toBeInTheDocument();
    expect(screen.queryByText("Threads アカウントを接続しました。")).not.toBeInTheDocument();
  });
});
