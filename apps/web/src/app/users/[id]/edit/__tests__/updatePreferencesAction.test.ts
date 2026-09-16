import { updatePreferencesAction } from "../actions";
import { requireSession } from "@/lib/session";
import { updateMyPreferences } from "@/lib/apiClient";
import { revalidatePath } from "next/cache";

jest.mock("@/lib/session", () => ({
  requireSession: jest.fn(),
}));

jest.mock("@/lib/apiClient", () => ({
  updateMyPreferences: jest.fn(),
}));

jest.mock("next/cache", () => ({
  revalidatePath: jest.fn(),
}));

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。「ブラウザに従う(未設定)」を
 * 選んで保存した場合、timezoneはnullとして送られなければならない(従来は空文字を
 * 「未選択」とみなしてエラーにしていた)。
 */
describe("updatePreferencesAction", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (requireSession as jest.Mock).mockResolvedValue({ user: { id: "sub-1" } });
  });

  function formData(locale: string, timezone: string): FormData {
    const data = new FormData();
    data.append("locale", locale);
    data.append("timezone", timezone);
    return data;
  }

  it("タイムゾーンを空(ブラウザに従う)で保存するとnullとして送られる", async () => {
    (updateMyPreferences as jest.Mock).mockResolvedValue({ id: 7 });

    const result = await updatePreferencesAction({}, formData("ja_JP", ""));

    expect(result).toEqual({ success: true });
    expect(updateMyPreferences).toHaveBeenCalledWith({ locale: "ja_JP", timezone: null });
    expect(revalidatePath).toHaveBeenCalledWith("/users/7/edit");
  });

  it("言語が空ならエラーを返しAPIを呼ばない", async () => {
    const result = await updatePreferencesAction({}, formData("", "Asia/Tokyo"));

    expect(result).toEqual({ error: "言語を選択してください。" });
    expect(updateMyPreferences).not.toHaveBeenCalled();
  });

  it("timezoneフィールド自体が送られなくてもnullとして扱う", async () => {
    (updateMyPreferences as jest.Mock).mockResolvedValue({ id: 7 });
    const data = new FormData();
    data.append("locale", "ja_JP");

    const result = await updatePreferencesAction({}, data);

    expect(result).toEqual({ success: true });
    expect(updateMyPreferences).toHaveBeenCalledWith({ locale: "ja_JP", timezone: null });
  });

  it("値のあるタイムゾーンを保存できる", async () => {
    (updateMyPreferences as jest.Mock).mockResolvedValue({ id: 7 });

    const result = await updatePreferencesAction({}, formData("ja_JP", "Pacific/Auckland"));

    expect(result).toEqual({ success: true });
    expect(updateMyPreferences).toHaveBeenCalledWith({ locale: "ja_JP", timezone: "Pacific/Auckland" });
  });

  it("updateMyPreferencesがErrorを投げた場合そのmessageを返す", async () => {
    (updateMyPreferences as jest.Mock).mockRejectedValue(new Error("APIエラー (400): 不正なタイムゾーンです"));

    const result = await updatePreferencesAction({}, formData("ja_JP", "Not/AZone"));

    expect(result).toEqual({ error: "APIエラー (400): 不正なタイムゾーンです" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
