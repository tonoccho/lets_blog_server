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

/** issue #1192: 管理者はメールアドレスを編集できる。管理者以外には変更経路を出さない。 */
describe("UserProfileForm のメールアドレス欄", () => {
  it("canEditEmailなら編集可能でname=emailを持ち、リンク切れの文言は出ない", () => {
    render(<UserProfileForm profile={baseProfile()} canEditEmail />);

    const input = screen.getByLabelText("メールアドレス") as HTMLInputElement;
    expect(input.disabled).toBe(false);
    expect(input.name).toBe("email");
    expect(input.defaultValue).toBe("user@example.com");
    expect(screen.queryByText("(変更は設定から行えます)")).toBeNull();
  });

  it("canEditEmailでなければ表示専用で、管理者のみ変更できる旨を示す", () => {
    render(<UserProfileForm profile={baseProfile()} />);

    const input = screen.getByDisplayValue("user@example.com") as HTMLInputElement;
    expect(input.disabled).toBe(true);
    expect(screen.queryByText("(変更は設定から行えます)")).toBeNull();
    expect(screen.getByText("(メールアドレスの変更は管理者のみ可能です)")).toBeInTheDocument();
  });
});

describe("UserProfileForm のメールアドレス欄(メール未設定)", () => {
  it.each([true, false])("emailがnullでも空欄で描画される(canEditEmail=%s)", (canEditEmail) => {
    const { container } = render(
      <UserProfileForm profile={baseProfile({ email: null as unknown as string })} canEditEmail={canEditEmail} />
    );

    const input = container.querySelector('input[type="email"]') as HTMLInputElement;
    expect(input.value).toBe("");
  });
});
