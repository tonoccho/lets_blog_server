import { uploadAvatarAction } from "../actions";
import { requireSession, getViewerProfile } from "@/lib/session";
import { uploadAvatar } from "@/lib/apiClient";
import { revalidatePath } from "next/cache";

jest.mock("@/lib/session", () => ({
  requireSession: jest.fn(),
  getViewerProfile: jest.fn(),
}));

jest.mock("@/lib/apiClient", () => ({
  uploadAvatar: jest.fn(),
}));

jest.mock("next/cache", () => ({
  revalidatePath: jest.fn(),
}));

/**
 * issue #1241: プロフィール編集画面のアバターアップロードのサーバーアクション。
 * 認可判定(本人またはadmin)・ファイル未選択・アップロード成功/失敗の各分岐を検証する。
 */
describe("uploadAvatarAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  function formDataWithFile(file: File | null): FormData {
    const formData = new FormData();
    if (file) {
      formData.append("file", file);
    }
    return formData;
  }

  it("本人であればuploadAvatarを呼びsuccessを返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });
    (uploadAvatar as jest.Mock).mockResolvedValue({ id: 1 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ success: true });
    expect(uploadAvatar).toHaveBeenCalledWith(1, file);
    expect(revalidatePath).toHaveBeenCalledWith("/users/1/edit");
  });

  it("本人でなくadminでもなければ権限エラーを返しuploadAvatarを呼ばない", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 999 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "この操作を行う権限がありません。" });
    expect(uploadAvatar).not.toHaveBeenCalled();
  });

  it("本人でなくてもadminであればuploadAvatarを呼ぶ", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "admin" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 999 });
    (uploadAvatar as jest.Mock).mockResolvedValue({ id: 1 });

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ success: true });
    expect(uploadAvatar).toHaveBeenCalledWith(1, file);
  });

  it("ファイルが選択されていなければエラーを返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });

    const result = await uploadAvatarAction(1, {}, formDataWithFile(null));

    expect(result).toEqual({ error: "画像ファイルを選択してください。" });
    expect(uploadAvatar).not.toHaveBeenCalled();
  });

  it("空ファイル(サイズ0)もエラーを返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });

    const emptyFile = new File([], "empty.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(emptyFile));

    expect(result).toEqual({ error: "画像ファイルを選択してください。" });
  });

  it("uploadAvatarがErrorを投げた場合そのmessageを返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });
    (uploadAvatar as jest.Mock).mockRejectedValue(new Error("APIエラー (400): 対応していない画像形式です"));

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "APIエラー (400): 対応していない画像形式です" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("uploadAvatarがError以外を投げた場合String化して返す", async () => {
    (requireSession as jest.Mock).mockResolvedValue({ user: { role: "user" } });
    (getViewerProfile as jest.Mock).mockResolvedValue({ id: 1 });
    (uploadAvatar as jest.Mock).mockRejectedValue("network down");

    const file = new File([new ArrayBuffer(10)], "a.png", { type: "image/png" });
    const result = await uploadAvatarAction(1, {}, formDataWithFile(file));

    expect(result).toEqual({ error: "network down" });
  });
});
