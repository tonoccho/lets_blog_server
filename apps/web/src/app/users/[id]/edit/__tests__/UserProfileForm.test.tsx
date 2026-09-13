import { render, screen } from "@testing-library/react";
import { UserProfileForm } from "../UserProfileForm";
import type { UserProfile } from "@/lib/apiClient";

jest.mock("../actions", () => ({
  updateUserProfileAction: jest.fn(),
}));

jest.mock("../AvatarUploadField", () => ({
  AvatarUploadField: ({ userId }: { userId: number }) => (
    <div data-testid="avatar-upload-field-stub">avatar-upload-field:{userId}</div>
  ),
}));

function baseProfile(overrides: Partial<UserProfile> = {}): UserProfile {
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

/**
 * issue #1241: 既存の「アバターURL」テキスト入力欄が、アップロードUI(AvatarUploadField)と
 * 併存してそのまま残っていること(要件1・AC2)の回帰テスト。
 */
describe("UserProfileForm のアバター欄", () => {
  it("avatarUrlが未設定でもアバターURL入力欄が空文字で表示される", () => {
    render(<UserProfileForm profile={baseProfile({ avatarUrl: null })} />);

    const input = screen.getByLabelText("アバターURL(Gravatar等)") as HTMLInputElement;
    expect(input.value).toBe("");
    expect(screen.getByTestId("avatar-upload-field-stub")).toHaveTextContent("avatar-upload-field:1");
  });

  it("avatarUrlが設定されていればアバターURL入力欄にその値が表示される", () => {
    render(<UserProfileForm profile={baseProfile({ avatarUrl: "https://example.com/avatar.png" })} />);

    const input = screen.getByLabelText("アバターURL(Gravatar等)") as HTMLInputElement;
    expect(input.value).toBe("https://example.com/avatar.png");
  });
});
