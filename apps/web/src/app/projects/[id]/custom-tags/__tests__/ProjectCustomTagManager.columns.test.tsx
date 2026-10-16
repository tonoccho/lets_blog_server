import { render, screen } from "@testing-library/react";
import { useSession } from "next-auth/react";
import type { CustomTag } from "@/lib/apiClient";
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
  });
});

const base = { projectId: 7, projectName: "案件", projectSlug: "slug", cssSelectorPrefix: null };

const tag: CustomTag = {
  id: 1,
  tagName: "note",
  htmlTemplate: "<div>{{content}}</div>",
  description: "注意書き",
  cssContent: ".note{}",
  tagFormat: "BLOCK",
  projectId: 7,
  createdAt: "2026-10-12T00:00:00Z",
  updatedAt: "2026-10-12T00:00:00Z",
};

/** issue #1750: Penpotのデザインファイル連携を削除したので、一覧に Penpot の列は無い。 */
describe("ProjectCustomTagManager: タグ一覧の列(issue #1750)", () => {
  it("列見出しに Penpot は無い", () => {
    render(<ProjectCustomTagManager {...base} tags={[tag]} />);

    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["タグ名", "形式", "説明", "表示サンプル", ""]);
    expect(screen.queryByText("Penpot")).not.toBeInTheDocument();
  });

  it("古い応答に Penpot のURLが残っていても、リンクは出さない", () => {
    const legacy = { ...tag, ["penpot" + "FileUrl"]: "https://penpot.example/file-1" } as CustomTag;

    render(<ProjectCustomTagManager {...base} tags={[legacy]} />);

    expect(screen.queryByRole("link", { name: /開く/ })).not.toBeInTheDocument();
  });

  it("タグが無いときの案内行は、全ての列にまたがる", () => {
    render(<ProjectCustomTagManager {...base} tags={[]} />);

    const cell = screen.getByText("登録済みカスタムタグはありません").closest("td");
    expect(cell).toHaveAttribute("colspan", "5");
  });
});
