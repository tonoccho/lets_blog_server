/**
 * @jest-environment node
 */

/**
 * issue #1366(親issue #1261 分割B-2): `/projects/[id]/plan` が閲覧者のタイムゾーンを壁打ち一覧へ渡し、
 * セッションの作成日がそのタイムゾーンで表示されることを、描画結果で確かめる。
 * あわせて、`?issue=` で開いたときの Issue のタイトル・構成案・紐付くセッションの受け渡しも確かめる。
 * 自前のモジュールは差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import { installPlanBackend, PROJECT, type PlanBackend } from "./planBackend";
import ArticlePlanPage from "../page";

let backend: PlanBackend;

const render = async (issue?: string) =>
  renderToStaticMarkup(await ArticlePlanPage({ params: Promise.resolve({ id: "7" }), searchParams: Promise.resolve({ issue }) }));

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
  backend.project = () => jsonResponse({ ...PROJECT, githubRepository: null });
  backend.sessions = () =>
    jsonResponse([{ id: 1, title: "企画テーマ", githubIssueNumber: null, createdAt: "2026-09-08T20:03:35Z", updatedAt: "2026-09-08T20:03:35Z" }]);
});

describe("/projects/[id]/plan のタイムゾーン受け渡し(issue #1366)", () => {
  it("個人設定TZが設定されているとき、そのTZの日付でセッションの作成日を表示する(UTC の 9/8 20:03 は東京の 9/9)", async () => {
    backend.profile = () => jsonResponse({ id: 1, timezone: "Asia/Tokyo" });
    const html = await render();
    expect(html).toContain("20260909-企画テーマ");
  });

  it("個人設定TZが未設定(null)のとき、マウント前の固定文字列を出す", async () => {
    const html = await render();
    expect(html).toContain(`${TIMEZONE_PENDING_PLACEHOLDER}-企画テーマ`);
  });
});

describe("/projects/[id]/plan?issue= (Issue を指定して開く)", () => {
  beforeEach(() => {
    backend.project = () => jsonResponse(PROJECT);
    backend.issues = () =>
      jsonResponse([
        { number: 3, title: "対象のIssue", htmlUrl: "https://github.com/o/r/issues/3", state: "open" },
        { number: 4, title: "別のIssue", htmlUrl: "https://github.com/o/r/issues/4", state: "open" },
      ]);
    backend.sessionByIssue = () =>
      jsonResponse({
        id: 9,
        title: "壁打ち",
        githubIssueNumber: 3,
        history: [{ role: "user", content: "最初の発言" }],
        createdAt: "2026-09-08T20:03:35Z",
        updatedAt: "2026-09-08T20:03:35Z",
      });
    backend.issueDescription = () => jsonResponse({ body: "## 既存の構成案" });
  });

  it("Issue のタイトル・紐付くセッションの履歴・既存の構成案を、壁打ちの画面へ渡す", async () => {
    const html = await render("3");
    expect(html).toContain("Issue #3: 対象のIssue");
    expect(html).toContain("最初の発言");
    expect(html).toContain("## 既存の構成案");
    expect(backend.requests).toContain("GET by-issue/3");
    expect(backend.requests).toContain("GET description/3");
  });

  it("一覧に無い Issue 番号ならタイトルは出さず、番号だけを示す", async () => {
    const html = await render("99");
    expect(html).toContain("Issue #99");
    expect(html).not.toContain("Issue #99:");
  });

  it("Issue の指定が無ければ、Issue 単位の取得をしない", async () => {
    await render();
    expect(backend.requests.filter((r) => r.includes("by-issue") || r.includes("description"))).toEqual([]);
  });
});
