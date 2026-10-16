import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 登録済み記事(Issue)一覧。自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
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
import { ArticlePlanIssueList } from "../ArticlePlanIssueList";

let backend: PlanBackend;

const issue = (number: number, title: string, state = "open") => ({
  number,
  title,
  htmlUrl: `https://github.com/o/r/issues/${number}`,
  state,
});

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
});

describe("ArticlePlanIssueList", () => {
  it("初期の Issue を、番号・タイトル・状態・計画ページへのリンクつきで並べる", () => {
    render(<ArticlePlanIssueList projectId={7} initialIssues={[issue(1, "記事A"), issue(2, "記事B", "closed")]} initialState="open" />);

    const link = screen.getByRole("link", { name: "#1 記事A" });
    expect(link).toHaveAttribute("href", "https://github.com/o/r/issues/1");
    expect(screen.getAllByRole("link", { name: "計画" })[1]).toHaveAttribute("href", "/projects/7/plan?issue=2");
    expect(screen.getByText("open").className).toContain("bg-green-100");
    expect(screen.getByText("closed").className).not.toContain("bg-green-100");
  });

  it("Issue が無いときは該当なしの案内を出す", () => {
    render(<ArticlePlanIssueList projectId={7} initialIssues={[]} initialState="open" />);
    expect(screen.getByText("該当するissueはありません。")).toBeInTheDocument();
  });

  it("状態を切り替えると、その状態の一覧を取り直して差し替える。読み込み中は選択を無効にし、案内は読み込み中にする", async () => {
    let resolve: (r: Response) => void = () => {};
    backend.issues = () => new Promise<Response>((r) => (resolve = r));
    render(<ArticlePlanIssueList projectId={7} initialIssues={[]} initialState="open" />);

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "closed" } });

    expect(screen.getByRole("combobox")).toBeDisabled();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    await waitFor(() => expect(backend.requests).toEqual(["GET issues?state=closed"]));

    resolve(jsonResponse([issue(5, "完了した記事", "closed")]));
    expect(await screen.findByRole("link", { name: "#5 完了した記事" })).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeEnabled();
    expect(screen.getByRole("combobox")).toHaveValue("closed");
  });

  it("取り直しに失敗したら理由を示し、前の一覧は残す。次に成功すれば理由は消える", async () => {
    backend.issues = () => textResponse("rate limited", 429);
    render(<ArticlePlanIssueList projectId={7} initialIssues={[issue(1, "記事A")]} initialState="open" />);

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "all" } });
    expect(await screen.findByText(/rate limited/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "#1 記事A" })).toBeInTheDocument();

    backend.issues = () => jsonResponse([]);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "open" } });
    await waitFor(() => expect(screen.queryByText(/rate limited/)).not.toBeInTheDocument());
  });
});
