import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 記事タイトル提案。自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
import { ArticlePlanProposals } from "../ArticlePlanProposals";

let backend: PlanBackend;
const HISTORY = [{ role: "user" as const, content: "テーマ" }];

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
});

const suggest = () => fireEvent.click(screen.getByRole("button", { name: "タイトル提案を取得" }));
const accept = () => fireEvent.click(screen.getByRole("button", { name: /計画を受け入れる/ }));

describe("ArticlePlanProposals", () => {
  it("履歴が空のあいだは提案を取得できず、案内を出す。受け入れも選ぶまで押せない", () => {
    render(<ArticlePlanProposals projectId={7} history={[]} />);
    expect(screen.getByRole("button", { name: "タイトル提案を取得" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /計画を受け入れる/ })).toBeDisabled();
    expect(screen.getByText(/タイトル提案はまだありません/)).toBeInTheDocument();
  });

  it("提案を取得すると、履歴をバックエンドへ送り、タイトルを選択肢として並べる", async () => {
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);

    suggest();

    expect(await screen.findByText("案A")).toBeInTheDocument();
    expect(screen.getByText("案B")).toBeInTheDocument();
    expect(backend.bodies.suggestTitles).toEqual([{ history: HISTORY }]);
  });

  it("取得中は取得ボタンを押せず「提案取得中…」を出す", async () => {
    let resolve: (r: Response) => void = () => {};
    backend.suggestTitles = () => new Promise<Response>((r) => (resolve = r));
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);

    suggest();

    expect(await screen.findByRole("button", { name: "提案取得中…" })).toBeDisabled();
    resolve(jsonResponse({ titles: ["案A"] }));
    expect(await screen.findByText("案A")).toBeInTheDocument();
  });

  it("取得に失敗したら理由を示す", async () => {
    backend.suggestTitles = () => textResponse("LLM down", 502);
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);

    suggest();

    expect(await screen.findByText(/LLM down/)).toBeInTheDocument();
  });

  it("選んだタイトルだけで計画を受け入れ、Issue として登録できたもの・失敗したものを結果に出し、「別のテーマ」で最初に戻る", async () => {
    backend.accept = () =>
      jsonResponse({
        results: [
          { title: "案A", issueNumber: 3, issueUrl: "https://github.com/o/r/issues/3" },
          { title: "案B", error: "重複しています" },
        ],
      });
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);
    suggest();
    await screen.findByText("案A");

    fireEvent.click(screen.getByLabelText("案A"));
    fireEvent.click(screen.getByLabelText("案B"));
    expect(screen.getByRole("button", { name: "選択した記事 (2 件) の計画を受け入れる" })).toBeEnabled();
    fireEvent.click(screen.getByLabelText("案B")); // 選択を外す
    expect(screen.getByRole("button", { name: "選択した記事 (1 件) の計画を受け入れる" })).toBeEnabled();
    fireEvent.click(screen.getByLabelText("案B"));
    accept();

    expect(await screen.findByRole("link", { name: "#3" })).toHaveAttribute("href", "https://github.com/o/r/issues/3");
    expect(screen.getByText(/重複しています/)).toBeInTheDocument();
    expect(backend.bodies.accept).toEqual([{ titles: ["案A", "案B"] }]);

    fireEvent.click(screen.getByRole("button", { name: "別のテーマで提案を取得" }));
    expect(screen.getByRole("button", { name: "タイトル提案を取得" })).toBeInTheDocument();
    expect(screen.getByText(/タイトル提案はまだありません/)).toBeInTheDocument();
  });

  it("受け入れ中は「登録中…」を出して押せなくする。失敗したら理由を示し、選択は残る", async () => {
    let resolve: (r: Response) => void = () => {};
    backend.accept = () => new Promise<Response>((r) => (resolve = r));
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);
    suggest();
    await screen.findByText("案A");
    fireEvent.click(screen.getByLabelText("案A"));

    accept();
    expect(await screen.findByRole("button", { name: "登録中…" })).toBeDisabled();

    resolve(textResponse("GitHub down", 502));
    expect(await screen.findByText(/GitHub down/)).toBeInTheDocument();
    expect(screen.getByLabelText("案A")).toBeChecked();
  });

  it("タイトルを選んでいなければ、受け入れの押下は何もしない(ボタンが無効のため通信しない)", async () => {
    render(<ArticlePlanProposals projectId={7} history={HISTORY} />);
    suggest();
    await screen.findByText("案A");
    accept();
    await waitFor(() => expect(backend.bodies.accept).toBeUndefined());
  });
});
