import { render, screen, fireEvent } from "@testing-library/react";

/**
 * issue #1259: `/users/[id]/edit` は、`profile.timezone`が`null`(未設定)のとき
 * "Asia/Tokyo"へフォールバックせず、そのまま個人設定タブへ渡さなければならない
 * (従来は`page.tsx:77`の`profile.timezone ?? "Asia/Tokyo"`が未設定を隠していた)。
 */
jest.mock("server-only", () => ({}));

const getUserProfile = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  getUserProfile: (...args: unknown[]) => getUserProfile(...args),
}));

const getViewerProfile = jest.fn();
jest.mock("@/lib/session", () => ({
  requireSession: jest.fn(async () => ({ user: { role: "user" } })),
  getViewerProfile: (...args: unknown[]) => getViewerProfile(...args),
}));

jest.mock("../UserProfileForm", () => ({ UserProfileForm: () => null }));

jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

import UserProfileEditPage from "../page";

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

describe("/users/[id]/edit の個人設定タブ(issue #1259)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("timezoneが未設定(null)なら「ブラウザに従う(未設定)」が選択されている", async () => {
    getViewerProfile.mockResolvedValue({ id: 1 });
    getUserProfile.mockResolvedValue(baseProfile({ timezone: null }));

    const element = await UserProfileEditPage({ params: Promise.resolve({ id: "1" }) });
    render(element);

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("");
  });

  it("timezoneに値があればその値が選択されている(Asia/Tokyoへのフォールバックはしない)", async () => {
    getViewerProfile.mockResolvedValue({ id: 1 });
    getUserProfile.mockResolvedValue(baseProfile({ timezone: "America/New_York" }));

    const element = await UserProfileEditPage({ params: Promise.resolve({ id: "1" }) });
    render(element);

    fireEvent.click(screen.getByRole("button", { name: "個人設定" }));

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("America/New_York");
  });
});
