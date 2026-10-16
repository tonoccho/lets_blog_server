import { render, screen } from "@testing-library/react";
import type { SiteDetail } from "@/lib/apiClient";

// 自前の Server Action・InstallWpCliButton は差し替えない。差し替えるのは Next.js の実行基盤だけ(#1834)。
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
import { SiteEditForm } from "../SiteEditForm";

const site = (over: Partial<SiteDetail> = {}): SiteDetail => ({
  id: 1,
  name: "S",
  siteKey: "s",
  cmsType: "WORDPRESS",
  baseUrl: "https://s.example.com",
  createdAt: "",
  updatedAt: "",
  managedWordpress: false,
  sshConfigured: false,
  credentials: {},
  configuredSecretFields: [],
  adminPath: null,
  ...over,
});

const input = () => screen.getByLabelText(/管理画面パス/) as HTMLInputElement;

describe("SiteEditForm の管理画面パス欄(issue #1532)", () => {
  it("現在の値を初期値として表示する", () => {
    render(<SiteEditForm site={site({ adminPath: "secret-login" })} sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(input().value).toBe("secret-login");
    expect(input().name).toBe("adminPath");
  });

  it("未設定(null)のときは空で、プレースホルダにグローバル既定値を示す", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(input().value).toBe("");
    expect(input().placeholder).toContain("wp-admin");
    expect(screen.getByText(/空欄ならシステム設定の既定値/)).toBeInTheDocument();
  });

  it("グローバル既定値を取得できなかったときもプレースホルダは空欄の意味を保つ", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={[]} defaultAdminPath={null} />);
    expect(input().placeholder).not.toContain("null");
    expect(input().value).toBe("");
  });

  it("マネージドWordPressサイトでも欄が表示され、認証情報の欄は出ない", () => {
    render(
      <SiteEditForm site={site({ managedWordpress: true, adminPath: "m" })} sshKeyPairs={[]} defaultAdminPath="wp-admin" />
    );
    expect(input().value).toBe("m");
    expect(screen.queryByLabelText("SSHホスト")).toBeNull();
  });

  it("通常サイトでは認証情報の fieldset の外に欄がある", () => {
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(container.querySelector("fieldset")?.contains(input())).toBe(false);
  });
});
