/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { renderToStaticMarkup } from "react-dom/server";
import AdminBackupPage from "../page";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

describe("AdminBackupPage", () => {
  it("管理者には見出しとバックアップ操作を出す", async () => {
    resetServerRuntime(ADMIN_SESSION);

    const html = renderToStaticMarkup(await AdminBackupPage());

    expect(html).toContain("データバックアップ");
    expect(html).toContain("バックアップのダウンロード");
    expect(html).toContain("リストアを実行");
  });

  it("管理者でなければトップへ送る", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(AdminBackupPage()).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it("未ログインならログイン画面へ送る", async () => {
    resetServerRuntime(null);

    await expect(AdminBackupPage()).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
