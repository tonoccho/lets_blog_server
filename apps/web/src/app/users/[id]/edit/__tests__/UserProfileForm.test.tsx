import { fireEvent, render, screen } from "@testing-library/react";
import { UserProfileForm } from "../UserProfileForm";
import type { UserProfile } from "@/lib/apiClient";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

// 自前の ../actions・../AvatarUploadField・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
// Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
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

const UPDATE = "PUT /api/users/1";
let fetchMock: jest.Mock;
const sentBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/users/1") && init?.method === "PUT")
    .map(([, init]) => JSON.parse(String(init.body)));

beforeEach(() => {
  resetServerRuntime(USER_SESSION);
  fetchMock = installFetch({
    "GET /api/identity/me": () => jsonResponse({ id: 1 }),
    [UPDATE]: () => jsonResponse({ id: 1 }),
  });
});

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
    // アバターのアップロード欄(AvatarUploadField)は、URL 入力欄と並んで置かれる。
    expect(screen.getByTestId("avatar-file-input")).toBeInTheDocument();
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

describe("UserProfileForm の保存", () => {
  it("入力した内容を本人の更新として送り、保存できたら成功を表示する", async () => {
    render(<UserProfileForm profile={baseProfile({ lastName: "山田", nickname: "taro", email: "taro@example.com" })} />);

    fireEvent.change(screen.getByLabelText("名"), { target: { value: "太郎" } });
    fireEvent.change(screen.getByLabelText("ウェブサイト"), { target: { value: "https://example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(sentBodies()).toHaveLength(1);
    expect(sentBodies()[0]).toMatchObject({
      lastName: "山田",
      firstName: "太郎",
      nickname: "taro",
      websiteUrl: "https://example.com",
      email: null,
    });
  });

  it("姓・名・ニックネームの入力が、表示名の候補にすぐ反映される", () => {
    render(<UserProfileForm profile={baseProfile()} />);

    fireEvent.change(screen.getByLabelText("姓"), { target: { value: "山田" } });
    fireEvent.change(screen.getByLabelText("名"), { target: { value: "太郎" } });
    fireEvent.change(screen.getByLabelText("ニックネーム"), { target: { value: "taro" } });

    const options = Array.from((screen.getByLabelText("表示名") as HTMLSelectElement).options).map((o) => o.value);
    expect(options).toEqual(["", "taro", "太郎", "山田", "太郎 山田", "山田 太郎", "user"]);
  });

  it("保存に失敗したら理由を表示し、成功は出さない", async () => {
    installFetch({
      "GET /api/identity/me": () => jsonResponse({ id: 1 }),
      [UPDATE]: () => textResponse("不正な値です", 400),
    });
    render(<UserProfileForm profile={baseProfile()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("APIエラー (400): 不正な値です")).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();
  });

  it("保存中はボタンを無効にして「保存中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({
      "GET /api/identity/me": () => jsonResponse({ id: 1 }),
      [UPDATE]: () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response,
    });
    render(<UserProfileForm profile={baseProfile()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    const pending = await screen.findByRole("button", { name: "保存中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse({ id: 1 }));
    await screen.findByText("保存しました。");
  });

  it("既存のプロフィールの値(SNS・カスタムリンク・姓名)を入力欄に出す", () => {
    render(
      <UserProfileForm
        profile={baseProfile({
          firstName: "太郎",
          lastName: "山田",
          nickname: "taro",
          socialLinks: { github: "https://github.com/taro" } as UserProfile["socialLinks"],
          customLinks: [{ label: "blog", url: "https://blog.example.com" }],
        })}
      />
    );

    expect((screen.getByLabelText("GitHub") as HTMLInputElement).value).toBe("https://github.com/taro");
    expect((screen.getByLabelText("Facebook") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("姓") as HTMLInputElement).value).toBe("山田");
    expect((screen.getByPlaceholderText("ラベル(例: 自分のブログ)") as HTMLInputElement).value).toBe("blog");
  });
});
