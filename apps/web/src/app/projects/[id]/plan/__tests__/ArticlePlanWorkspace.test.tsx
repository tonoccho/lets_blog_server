import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 記事計画のワークスペース(壁打ち一覧・チャット・提案)。自前の Server Action(../actions)・子コンポーネント・
 * @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 *
 * issue #1366(親issue #1261 分割B-2): `plan/page.tsx` → `ArticlePlanWorkspace` → `ArticlePlanSessionList` に
 * タイムゾーンを渡す経路も、描画された作成日で確かめる。
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
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installPlanBackend, type PlanBackend } from "./planBackend";
import { ArticlePlanWorkspace } from "../ArticlePlanWorkspace";

let backend: PlanBackend;

const summary = (over: Record<string, unknown> = {}) => ({
  id: 1,
  title: "企画A",
  githubIssueNumber: null,
  createdAt: "2026-09-08T20:03:35Z",
  updatedAt: "2026-09-08T20:03:35Z",
  ...over,
});

const detail = (over: Record<string, unknown> = {}) => ({
  ...summary(),
  history: [
    { role: "user", content: "過去の質問" },
    { role: "assistant", content: "過去の回答" },
  ],
  ...over,
});

function renderWorkspace(over: Partial<React.ComponentProps<typeof ArticlePlanWorkspace>> = {}) {
  return render(
    <ArticlePlanWorkspace
      projectId={7}
      initialSessions={[]}
      initialIssueNumber={null}
      initialIssueTitle={null}
      initialIssueSession={null}
      initialIssueStructure={null}
      timezone={null}
      {...over}
    />
  );
}

const input = () => screen.getByPlaceholderText("質問や企画案を入力...");

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
});

describe("ArticlePlanWorkspace のタイムゾーン受け渡し(issue #1366)", () => {
  it("個人設定TZが設定されているとき、そのTZの日付でセッションの作成日を出す", () => {
    renderWorkspace({ initialSessions: [summary()], timezone: "Asia/Tokyo" });
    expect(screen.getByRole("button", { name: /20260909-企画A/ })).toBeInTheDocument();
  });

  it("個人設定TZが未設定(null)のとき、マウント後はブラウザのTZ(テストは UTC)の日付を出す", () => {
    renderWorkspace({ initialSessions: [summary()], timezone: null });
    expect(screen.getByRole("button", { name: /20260908-企画A/ })).toBeInTheDocument();
  });
});

describe("ArticlePlanWorkspace: チャット", () => {
  it("送信すると履歴・メッセージ・セッションID・Issue番号をバックエンドへ送り、返答を履歴に足し、セッション一覧を取り直す", async () => {
    backend.sessions = () => jsonResponse([summary({ id: 11, title: "新しい壁打ち" })]);
    renderWorkspace();

    fireEvent.change(input(), { target: { value: "こんにちは" } });
    fireEvent.click(screen.getByRole("button", { name: "送信" }));

    expect(await screen.findByText("返答")).toBeInTheDocument();
    expect(screen.getByText("こんにちは")).toBeInTheDocument();
    expect(backend.bodies.chat).toEqual([{ history: [], message: "こんにちは", sessionId: null, githubIssueNumber: null }]);
    expect(await screen.findByRole("button", { name: /新しい壁打ち/ })).toBeInTheDocument();

    // 2 回目は、前回の返答のセッション ID と履歴を引き継ぐ
    fireEvent.change(input(), { target: { value: "続き" } });
    fireEvent.click(screen.getByRole("button", { name: "送信" }));
    await waitFor(() => expect(backend.bodies.chat).toHaveLength(2));
    expect(backend.bodies.chat[1]).toMatchObject({ sessionId: 11, history: [{ content: "こんにちは" }, { content: "返答" }] });
  });

  it("送信に失敗したら理由を示し、履歴には足さない", async () => {
    backend.chat = () => textResponse("LLM down", 502);
    renderWorkspace();

    fireEvent.change(input(), { target: { value: "こんにちは" } });
    fireEvent.click(screen.getByRole("button", { name: "送信" }));

    expect(await screen.findByText(/LLM down/)).toBeInTheDocument();
    expect(screen.queryByText("返答")).not.toBeInTheDocument();
  });

  it("セッション一覧の取り直しに失敗しても、返答は表示し、一覧は前のまま", async () => {
    backend.sessions = () => textResponse("down", 503);
    renderWorkspace({ initialSessions: [summary()] });

    fireEvent.change(input(), { target: { value: "こんにちは" } });
    fireEvent.click(screen.getByRole("button", { name: "送信" }));

    expect(await screen.findByText("返答")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /企画A/ })).toBeInTheDocument();
  });

  it("「新規チャットを開始」で履歴・セッション・エラーを空に戻す", async () => {
    backend.chat = () => textResponse("LLM down", 502);
    renderWorkspace({ initialIssueSession: detail() as never });
    expect(screen.getByText("過去の質問")).toBeInTheDocument();

    fireEvent.change(input(), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "送信" }));
    await screen.findByText(/LLM down/);

    fireEvent.click(screen.getByRole("button", { name: "新規チャットを開始" }));

    expect(screen.queryByText("過去の質問")).not.toBeInTheDocument();
    expect(screen.queryByText(/LLM down/)).not.toBeInTheDocument();
    expect(screen.getByText(/チャットを始めましょう/)).toBeInTheDocument();
  });
});

describe("ArticlePlanWorkspace: セッションの切り替え", () => {
  it("セッションを選ぶと、その履歴を開く。Issue に紐付かないセッションなら構成案は空", async () => {
    backend.session = () => jsonResponse(detail({ id: 1 }));
    renderWorkspace({ initialSessions: [summary()] });

    fireEvent.click(screen.getByRole("button", { name: /企画A/ }));

    expect(await screen.findByText("過去の質問")).toBeInTheDocument();
    expect(backend.requests).toContain("GET session/1");
    expect(screen.queryByRole("button", { name: "構成提案" })).not.toBeInTheDocument();
  });

  it("Issue に紐付くセッションなら、Issue の構成案を読み込み、構成提案とタイトル提案を切り替えられる", async () => {
    backend.session = () => jsonResponse(detail({ id: 2, githubIssueNumber: 3 }));
    backend.issueDescription = () => jsonResponse({ body: "## 構成案の本文" });
    renderWorkspace({ initialSessions: [summary({ id: 2, githubIssueNumber: 3 })] });

    fireEvent.click(screen.getByRole("button", { name: /企画A/ }));

    expect(await screen.findByDisplayValue("## 構成案の本文")).toBeInTheDocument();
    expect(screen.getByText("Issue #3")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "タイトル提案" }));
    expect(screen.getByRole("heading", { name: "記事タイトル提案" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "構成提案" }));
    expect(screen.getByRole("heading", { name: /記事構成の提案\(Issue #3\)/ })).toBeInTheDocument();
  });

  it("構成案を読み込めなくても、セッションは開く(構成案は空)", async () => {
    backend.session = () => jsonResponse(detail({ id: 2, githubIssueNumber: 3 }));
    backend.issueDescription = () => textResponse("down", 503);
    renderWorkspace({ initialSessions: [summary({ id: 2, githubIssueNumber: 3 })] });

    fireEvent.click(screen.getByRole("button", { name: /企画A/ }));

    expect(await screen.findByText("過去の質問")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/構成案はまだありません/)).toBeInTheDocument());
  });

  it("セッションを開けなければ理由を示し、いまの履歴は残す", async () => {
    backend.session = () => textResponse("gone", 404);
    renderWorkspace({ initialSessions: [summary()], initialIssueSession: detail({ id: 5 }) as never });

    fireEvent.click(screen.getByRole("button", { name: /企画A/ }));

    expect(await screen.findByText(/gone/)).toBeInTheDocument();
    expect(screen.getByText("過去の質問")).toBeInTheDocument();
  });
});

describe("ArticlePlanWorkspace: Issue を指定して開いたとき", () => {
  it("Issue 番号とタイトルを示し、既存の構成案を構成提案の欄へ入れる", () => {
    renderWorkspace({ initialIssueNumber: 3, initialIssueTitle: "対象のIssue", initialIssueStructure: "## 既存" });
    expect(screen.getByText("Issue #3: 対象のIssue")).toBeInTheDocument();
    expect(screen.getByDisplayValue("## 既存")).toBeInTheDocument();
  });
});
