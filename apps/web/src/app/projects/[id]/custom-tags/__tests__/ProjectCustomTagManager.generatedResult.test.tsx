import { render, screen, waitFor } from "@testing-library/react";
import { useSession } from "next-auth/react";
import { installFetch, jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { ProjectCustomTagManager } from "../ProjectCustomTagManager";

/**
 * 自前のモジュール(CssSelectorPrefixForm・CustomTagGenerationForm・CustomTagGenerationResult・CustomTagManager・
 * Server Action・@/lib/session・@/lib/apiClient)は差し替えない。差し替えるのは Next.js の実行基盤・`next-auth/react`
 * (ブラウザのセッション取得)と外部との通信(fetch)だけ(#1835)。
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
jest.mock("next-auth/react");

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  (useSession as jest.Mock).mockReturnValue({
    data: { user: { email: "a@example.com", role: "admin" }, expires: "2099-01-01" },
    status: "authenticated",
    update: jest.fn(),
  });
  installFetch({
    "POST /projects/7/custom-tags/preview": () => jsonResponse({ html: "", css: "" }),
    "POST /api/custom-tags/validate": () => jsonResponse({ isValid: true, errors: [], warnings: [] }),
  });
});

const base = { projectId: 7, projectName: "案件", projectSlug: "slug", cssSelectorPrefix: null, tags: [] };

/** issue #1409: 「結果を見る」で開いたときだけ、生成結果(未保存)を生成フォームの近くに出す。 */
const RESULT = { jobId: 42, tagName: "blue", description: null, projectId: 7, htmlTemplate: "<b/>", cssContent: ".b{}" };

/** issue #1409: 「結果を見る」で開いたときだけ、生成結果(未保存)を生成フォームの近くに出す。 */
describe("ProjectCustomTagManager: 生成結果(issue #1409)", () => {
  it("生成結果が無ければ生成フォームだけを出す", () => {
    render(<ProjectCustomTagManager {...base} />);

    expect(screen.getByRole("button", { name: /生成/ })).toBeInTheDocument();
    expect(screen.queryByTestId("custom-tag-generation-result")).not.toBeInTheDocument();
  });

  it("生成結果があれば、生成結果のカードを出し、接頭辞は未設定ならslugを使う", async () => {
    render(<ProjectCustomTagManager {...base} generatedResult={RESULT} />);

    const card = await screen.findByTestId("custom-tag-generation-result");
    expect(card).toHaveAttribute("data-job-id", "42");
    expect(card).toHaveTextContent("[blue]");
    expect(card).toHaveTextContent("「.slug」");
    await waitFor(() => expect(card).toHaveTextContent("HTMLとCSSは有効です。"));
  });

  it("接頭辞が設定されていればそれを使う", async () => {
    render(<ProjectCustomTagManager {...base} cssSelectorPrefix="custom" generatedResult={RESULT} />);

    const card = await screen.findByTestId("custom-tag-generation-result");
    expect(card).toHaveTextContent("「.custom」");
    await waitFor(() => expect(card).toHaveTextContent("HTMLとCSSは有効です。"));
  });
});
