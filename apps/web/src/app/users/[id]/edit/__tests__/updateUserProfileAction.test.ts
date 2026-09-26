import { updateUserProfileAction } from "../actions";
import { requireSession, getViewerProfile } from "@/lib/session";
import { updateUserProfile } from "@/lib/apiClient";
import { revalidatePath } from "next/cache";

jest.mock("@/lib/session", () => ({
  requireSession: jest.fn(),
  getViewerProfile: jest.fn(),
}));

jest.mock("@/lib/apiClient", () => ({
  updateUserProfile: jest.fn(),
  updateMyPreferences: jest.fn(),
  uploadAvatar: jest.fn(),
}));

jest.mock("next/cache", () => ({
  revalidatePath: jest.fn(),
}));

/** issue #1192: メールアドレスは管理者のときだけAPIへ送る。 */
describe("updateUserProfileAction のメールアドレス", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (updateUserProfile as jest.Mock).mockResolvedValue({ id: 1 });
  });

  function form(email: string | null): FormData {
    const formData = new FormData();
    if (email !== null) {
      formData.append("email", email);
    }
    return formData;
  }

  it("adminはemailを送信でき、一覧側も再検証される", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "admin" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 999 });

    const result = await updateUserProfileAction(1, {}, form(" new@example.com "));

    expect(result).toEqual({ success: true });
    expect((updateUserProfile as jest.Mock).mock.calls[0][1].email).toBe("new@example.com");
    expect(revalidatePath).toHaveBeenCalledWith("/users");
  });

  it("adminでも欄が空ならemailはnull(変更なし)で送る", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "admin" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 999 });

    await updateUserProfileAction(1, {}, form(""));

    expect((updateUserProfile as jest.Mock).mock.calls[0][1].email).toBeNull();
  });

  it("本人(admin以外)はフォームにemailがあってもnullで送る", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });

    await updateUserProfileAction(1, {}, form("hacker@example.com"));

    expect((updateUserProfile as jest.Mock).mock.calls[0][1].email).toBeNull();
  });

  it("重複などでAPIが失敗したらそのメッセージを返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "admin" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 999 });
    (updateUserProfile as jest.Mock).mockRejectedValue(new Error("APIエラー (409): 既に登録されています"));

    const result = await updateUserProfileAction(1, {}, form("dup@example.com"));

    expect(result).toEqual({ error: "APIエラー (409): 既に登録されています" });
  });
});
