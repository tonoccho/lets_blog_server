import { render, screen } from "@testing-library/react";

// 自前の Server Action(../actions)は差し替えない。差し替えるのは Next.js の実行基盤だけ(#1834)。
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
import { SiteForm } from "../SiteForm";

const input = () => screen.getByLabelText(/管理画面パス/) as HTMLInputElement;

describe("SiteForm の管理画面パス欄(issue #1533)", () => {
  it("任意入力(required でない)で、空の初期値、name は adminPath", () => {
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(input().name).toBe("adminPath");
    expect(input().required).toBe(false);
    expect(input().value).toBe("");
  });

  it("プレースホルダにグローバル既定値を示し、空欄の意味を説明する", () => {
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(input().placeholder).toContain("wp-admin");
    expect(screen.getByText(/空欄ならシステム設定の既定値/)).toBeInTheDocument();
  });

  it("既定値が取得できないときもプレースホルダは空欄の意味を保つ", () => {
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath={null} />);
    expect(input().placeholder).not.toContain("null");
    expect(input().placeholder).toContain("既定値");
  });
});
