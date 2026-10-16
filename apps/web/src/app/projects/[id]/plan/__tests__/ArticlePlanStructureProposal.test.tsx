import { fireEvent, render, screen } from "@testing-library/react";

/**
 * 記事構成の提案。自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
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
import { resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installPlanBackend, type PlanBackend } from "./planBackend";
import { ArticlePlanStructureProposal } from "../ArticlePlanStructureProposal";

let backend: PlanBackend;
const HISTORY = [{ role: "user" as const, content: "テーマ" }];

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
});

function renderProposal(over: Partial<React.ComponentProps<typeof ArticlePlanStructureProposal>> = {}) {
  return render(<ArticlePlanStructureProposal projectId={7} issueNumber={3} history={HISTORY} {...over} />);
}
const suggest = () => fireEvent.click(screen.getByRole("button", { name: "記事の構成を提案" }));
const update = () => fireEvent.click(screen.getByRole("button", { name: "この内容でIssueを更新" }));

describe("ArticlePlanStructureProposal", () => {
  it("既存の構成案が無ければ案内を出し、更新は押せない。履歴が空なら提案も取得できない", () => {
    renderProposal({ history: [], initialStructure: undefined });
    expect(screen.getByText(/構成案はまだありません/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "この内容でIssueを更新" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "記事の構成を提案" })).toBeDisabled();
    expect(screen.getByRole("heading", { name: "記事構成の提案(Issue #3)" })).toBeInTheDocument();
  });

  it("既存の構成案が空白だけなら、無いものとして扱う", () => {
    renderProposal({ initialStructure: "  \n " });
    expect(screen.getByText(/構成案はまだありません/)).toBeInTheDocument();
  });

  it("既存の構成案があれば編集欄に入れる", () => {
    renderProposal({ initialStructure: "## 既存" });
    expect(screen.getByDisplayValue("## 既存")).toBeInTheDocument();
  });

  it("構成の提案を取得すると編集欄へ入れ、編集した内容で Issue を更新し、更新した Issue へのリンクを出す。「別の構成案」で戻る", async () => {
    renderProposal();

    suggest();
    const textbox = await screen.findByDisplayValue("# 構成");
    expect(backend.bodies.suggestStructure).toEqual([{ history: HISTORY }]);

    fireEvent.change(textbox, { target: { value: "# 編集後の構成" } });
    update();

    expect(await screen.findByRole("link", { name: "#3" })).toHaveAttribute("href", "https://github.com/o/r/issues/3");
    expect(backend.bodies.acceptStructure).toEqual([{ structure: "# 編集後の構成" }]);

    fireEvent.click(screen.getByRole("button", { name: "別の構成案を作成する" }));
    expect(screen.getByText(/構成案はまだありません/)).toBeInTheDocument();
  });

  it("取得中は「提案取得中…」で押せず、失敗したら理由を示す", async () => {
    let resolve: (r: Response) => void = () => {};
    backend.suggestStructure = () => new Promise<Response>((r) => (resolve = r));
    renderProposal();

    suggest();
    expect(await screen.findByRole("button", { name: "提案取得中…" })).toBeDisabled();

    resolve(textResponse("LLM down", 502));
    expect(await screen.findByText(/LLM down/)).toBeInTheDocument();
  });

  it("更新中は「更新中…」で押せず、失敗したら理由を示す。編集欄を空にすれば更新できない", async () => {
    let resolve: (r: Response) => void = () => {};
    backend.acceptStructure = () => new Promise<Response>((r) => (resolve = r));
    renderProposal({ initialStructure: "## 既存" });

    update();
    expect(await screen.findByRole("button", { name: "更新中…" })).toBeDisabled();
    resolve(textResponse("GitHub down", 502));
    expect(await screen.findByText(/GitHub down/)).toBeInTheDocument();

    fireEvent.change(screen.getByDisplayValue("## 既存"), { target: { value: " " } });
    expect(screen.getByRole("button", { name: "この内容でIssueを更新" })).toBeDisabled();
  });

  it("履歴が空のときは提案を取得せず、構成案が空のときは更新しない(ボタンが無効のため通信しない)", () => {
    renderProposal({ history: [], initialStructure: null });
    suggest();
    update();
    expect(backend.requests).toEqual([]);
  });
});
