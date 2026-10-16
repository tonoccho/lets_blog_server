import { fireEvent, render, screen } from "@testing-library/react";

/**
 * 自前の SiteForm・ManagedWordPressForm・Server Action は差し替えない。差し替えるのは
 * Next.js の実行基盤だけ(#1834)。
 */
jest.mock("server-only", () => ({}))
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule)
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule)
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule)
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule)
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))
import { SiteCreationPanel } from "../SiteCreationPanel";

describe("SiteCreationPanel(issue #1533: 既定の管理画面パスをSiteFormへ渡す)", () => {
  it("既存サイト登録フォームへ既定の管理画面パスを渡す", () => {
    render(<SiteCreationPanel users={[]} sites={[]} sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(screen.getByPlaceholderText("既定: wp-admin")).toBeInTheDocument();
  });

  it("「WordPressを新規構築」へ切り替えると構築フォームを表示する", () => {
    render(<SiteCreationPanel users={[]} sites={[]} sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByText("WordPressを新規構築"));
    expect(screen.getByRole("button", { name: "構築する" })).toBeInTheDocument();
    expect(screen.queryByText("サイトを登録")).toBeNull();
  });

  it("構築フォームのテンプレート候補には、自動構築されたサイトだけを並べる", () => {
    const site = (id: number, name: string, managedWordpress: boolean) =>
      ({ id, name, siteKey: `k${id}`, managedWordpress }) as never;
    render(
      <SiteCreationPanel
        users={[]}
        sites={[site(1, "手動", false), site(2, "自動", true)]}
        sshKeyPairs={[]}
        defaultAdminPath={null}
      />,
    );
    fireEvent.click(screen.getByText("WordPressを新規構築"));
    expect(screen.getByRole("option", { name: "自動(k2)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "手動(k1)" })).toBeNull();
  });

  it("構築フォームから既存サイト登録へ戻せる", () => {
    render(<SiteCreationPanel users={[]} sites={[]} sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByText("WordPressを新規構築"));
    fireEvent.click(screen.getByText("既存サイトを登録"));
    expect(screen.getByText("サイトを登録")).toBeInTheDocument();
  });
});
