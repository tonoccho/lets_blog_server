import { render, screen, fireEvent } from "@testing-library/react";

/**
 * issue #1259: `/users/[id]/edit` は、`profile.timezone`が`null`(未設定)のとき
 * "Asia/Tokyo"へフォールバックせず、そのまま個人設定タブへ渡さなければならない
 * (従来は`page.tsx:77`の`profile.timezone ?? "Asia/Tokyo"`が未設定を隠していた)。
 *
 * 自前の @/lib/apiClient・@/lib/session・UserProfileForm・Breadcrumb・Tabs は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
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
  usePathname: () => "/users/1/edit",
}));

import UserProfileEditPage from "../page";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

function baseProfile(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    email: "user@example.com",
    role: "user",
    roleNames: ["user"],
    firstName: null,
    lastName: null,
    displayName: null,
    nickname: null,
    websiteUrl: null,
    bio: null,
    locale: null,
    timezone: null,
    avatarUrl: null,
    department: null,
    position: null,
    socialLinks: null,
    customLinks: null,
    githubTokenConfigured: false,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-01-01T00:00:00",
    ...overrides,
  };
}

/** identity-service の代役。viewer は GET /api/identity/me、編集対象は GET /api/users/:id。 */
function installApi(viewer: unknown, target: () => Response) {
  return installFetch({
    "GET /api/identity/me": () => jsonResponse(viewer),
    "GET /api/users/:id": target,
  });
}

function page(id = "1") {
  return UserProfileEditPage({ params: Promise.resolve({ id }) });
}

describe("/users/[id]/edit の個人設定タブ(issue #1259)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(USER_SESSION);
  });

  it("timezoneが未設定(null)なら「ブラウザに従う(未設定)」が選択されている", async () => {
    installApi({ id: 1 }, () => jsonResponse(baseProfile({ timezone: null })));

    render(await page());

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("");
  });

  it("timezoneに値があればその値が選択されている(Asia/Tokyoへのフォールバックはしない)", async () => {
    installApi({ id: 1 }, () => jsonResponse(baseProfile({ timezone: "America/New_York" })));

    render(await page());

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("America/New_York");
  });

  it("言語が未設定なら日本語が選択されている", async () => {
    installApi({ id: 1 }, () => jsonResponse(baseProfile({ locale: null })));

    render(await page());

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    expect((screen.getByLabelText("言語") as HTMLSelectElement).value).toBe("ja_JP");
  });

  it("言語が設定済みならその値が選択されている", async () => {
    installApi({ id: 1 }, () => jsonResponse(baseProfile({ locale: "en_US" })));

    render(await page());

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    expect((screen.getByLabelText("言語") as HTMLSelectElement).value).toBe("en_US");
  });
});

describe("/users/[id]/edit の表示とアクセス制御", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(USER_SESSION);
  });

  it("本人以外の非adminはトップへ送り、プロフィールを取得しない", async () => {
    const fetchMock = installApi({ id: 999 }, () => jsonResponse(baseProfile()));

    await expect(page("1")).rejects.toThrow("NEXT_REDIRECT:/");
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/api/users/1"))).toBe(false);
  });

  it("本人かどうかを確定できない(自分のプロフィールを取得できない)非adminはトップへ送る", async () => {
    installApi(null, () => jsonResponse(baseProfile()));

    await expect(page("1")).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it("本人のプロフィールが取得できなければトップへ送る", async () => {
    installApi({ id: 1 }, () => textResponse("not found", 404));

    await expect(page("1")).rejects.toThrow("NEXT_REDIRECT:/");
  });

  it("adminが開いたプロフィールが取得できなければユーザー一覧へ送る", async () => {
    resetServerRuntime(ADMIN_SESSION);
    installApi({ id: 999 }, () => textResponse("not found", 404));

    await expect(page("1")).rejects.toThrow("NEXT_REDIRECT:/users");
  });

  it("adminは他人のプロフィールを開け、メール欄を編集でき、個人設定タブは出ない。パンくずは一覧へ戻れる", async () => {
    resetServerRuntime(ADMIN_SESSION);
    installApi({ id: 999 }, () => jsonResponse(baseProfile({ displayName: "山田" })));

    render(await page("1"));

    expect(screen.getByRole("heading", { name: "ユーザープロフィール編集" })).toBeInTheDocument();
    expect(screen.getAllByText("山田").length).toBeGreaterThan(0);
    expect((screen.getByLabelText("メールアドレス") as HTMLInputElement).disabled).toBe(false);
    expect(screen.queryByRole("button", { name: "個人設定" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ユーザー" })).toHaveAttribute("href", "/users");
  });

  it("非adminの本人は、パンくずのユーザーが一覧へのリンクにならず、メール欄は編集できない", async () => {
    installApi({ id: 1 }, () => jsonResponse(baseProfile()));

    render(await page("1"));

    expect(screen.queryByRole("link", { name: "ユーザー" })).not.toBeInTheDocument();
    expect(screen.getByDisplayValue("user@example.com")).toBeDisabled();
    // 表示名が無いときはパンくずの末尾にメールアドレスを出す。
    expect(screen.getAllByText("user@example.com").length).toBeGreaterThan(0);
  });
});

describe("/users/[id]/edit のタイムゾーンの選択肢", () => {
  const original = Object.getOwnPropertyDescriptor(Intl, "supportedValuesOf");

  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(USER_SESSION);
    installApi({ id: 1 }, () => jsonResponse(baseProfile()));
  });

  afterEach(() => {
    if (original) Object.defineProperty(Intl, "supportedValuesOf", original);
    else delete (Intl as unknown as Record<string, unknown>).supportedValuesOf;
  });

  async function timezoneOptions(): Promise<string[]> {
    render(await page());
    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));
    return Array.from((screen.getByTestId("timezone-select") as HTMLSelectElement).options).map((o) => o.value);
  }

  it("実行環境が対応していれば、その一覧を出す", async () => {
    Object.defineProperty(Intl, "supportedValuesOf", { value: () => ["Asia/Tokyo", "Pacific/Auckland"], configurable: true });

    expect(await timezoneOptions()).toEqual(["", "Asia/Tokyo", "Pacific/Auckland"]);
  });

  it("実行環境が Intl.supportedValuesOf に対応していなければ、固定の一覧にする", async () => {
    Object.defineProperty(Intl, "supportedValuesOf", { value: undefined, configurable: true });

    const options = await timezoneOptions();

    expect(options).toContain("Asia/Tokyo");
    expect(options).toContain("UTC");
    expect(options).toHaveLength(13);
  });

  it("Intl.supportedValuesOf が例外を投げても、固定の一覧にする", async () => {
    Object.defineProperty(Intl, "supportedValuesOf", {
      value: () => {
        throw new RangeError("unsupported");
      },
      configurable: true,
    });

    const options = await timezoneOptions();

    expect(options).toHaveLength(13);
    expect(options).toContain("Asia/Tokyo");
  });
});
