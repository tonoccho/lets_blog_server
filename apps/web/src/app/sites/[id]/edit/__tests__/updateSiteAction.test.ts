/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({ requireAdminSession: () => requireAdminSession() }));
const updateSite = jest.fn();
jest.mock("@/lib/apiClient", () => ({ updateSite: (...a: unknown[]) => updateSite(...a) }));
import { updateSiteAction } from "../actions";

function form(entries: Record<string, string>): FormData {
  const fd = new FormData();
  for (const [k, v] of Object.entries(entries)) fd.set(k, v);
  return fd;
}

describe("updateSiteAction の管理画面パス(issue #1532)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    updateSite.mockResolvedValue({ connectionCheckStatus: null });
  });

  it("adminPath の値はそのまま送り、credentials には含めない", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "secret-login" }));
    expect(updateSite).toHaveBeenCalledWith(1, { name: "N", credentials: undefined, adminPath: "secret-login" });
  });

  it("空文字の adminPath は「上書き解除」として空文字のまま送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "" }));
    expect(updateSite.mock.calls[0][1].adminPath).toBe("");
  });

  it("前後の空白は取り除いて送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", adminPath: "  x  " }));
    expect(updateSite.mock.calls[0][1].adminPath).toBe("x");
  });

  it("adminPath のフィールドが無いときは送らない(変更しない)", async () => {
    await updateSiteAction(1, {}, form({ name: "N" }));
    expect(updateSite.mock.calls[0][1].adminPath).toBeUndefined();
  });

  it("credentials の空文字は従来どおり落とし、入力があるものだけ送る", async () => {
    await updateSiteAction(1, {}, form({ name: "N", baseUrl: "", username: "u", adminPath: "" }));
    expect(updateSite.mock.calls[0][1].credentials).toEqual({ username: "u" });
  });

  it("バックエンドの 400 メッセージを error として返す", async () => {
    updateSite.mockRejectedValue(new Error("APIエラー (400): 相対パスを指定してください"));
    const result = await updateSiteAction(1, {}, form({ name: "N", adminPath: "//evil" }));
    expect(result).toEqual({ error: "APIエラー (400): 相対パスを指定してください" });
  });

  it("Error 以外が投げられても文字列化して返す", async () => {
    updateSite.mockRejectedValue("boom");
    expect(await updateSiteAction(1, {}, form({ name: "N" }))).toEqual({ error: "boom" });
  });

  it("成功時は success と疎通確認の状態を返す", async () => {
    updateSite.mockResolvedValue({ connectionCheckStatus: "SUCCESS" });
    expect(await updateSiteAction(1, {}, form({ name: "N" }))).toEqual({
      success: true,
      connectionCheckStatus: "SUCCESS",
    });
  });
});
