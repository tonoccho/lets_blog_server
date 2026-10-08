/**
 * issue #1383: deleteUserAction は失敗を例外ではなく { error } で返す(SshKeyPairsPanel の
 * deleteSshKeyPairAction と同じ形)。投げっぱなしだと失敗の理由が画面に出ない。
 * 自己削除防止(viewer 判定)の意味は変えない。
 */
const requireAdminSession = jest.fn();
const getViewerProfile = jest.fn();
jest.mock("@/lib/session", () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerProfile: (...a: unknown[]) => getViewerProfile(...a),
}));
const deleteUser = jest.fn();
jest.mock("@/lib/apiClient", () => ({
  createUser: jest.fn(),
  deleteUser: (...a: unknown[]) => deleteUser(...a),
}));
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));

import { deleteUserAction } from "../actions";

describe("deleteUserAction (issue #1383)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: "admin" } });
    getViewerProfile.mockResolvedValue({ id: 1 });
    deleteUser.mockResolvedValue(undefined);
  });

  it("成功すると削除して一覧を再検証し、errorを返さない", async () => {
    const result = await deleteUserAction(2);
    expect(deleteUser).toHaveBeenCalledWith(2);
    expect(revalidatePath).toHaveBeenCalledWith("/users");
    expect(result.error).toBeUndefined();
  });

  it("ログイン中ユーザーを取得できないときは削除せずerrorを返す(フェイルクローズ)", async () => {
    getViewerProfile.mockResolvedValue(null);
    const result = await deleteUserAction(2);
    expect(deleteUser).not.toHaveBeenCalled();
    expect(result.error).toContain("ログイン中のユーザー情報を取得できなかった");
  });

  it("自分自身の削除は拒否してerrorを返す", async () => {
    const result = await deleteUserAction(1);
    expect(deleteUser).not.toHaveBeenCalled();
    expect(result.error).toBe("自分自身のアカウントは削除できません。");
  });

  it("APIエラーはerrorとして返す(Errorでない値も文字列化する)", async () => {
    deleteUser.mockRejectedValueOnce(new Error("API 500"));
    expect((await deleteUserAction(2)).error).toBe("API 500");
    deleteUser.mockRejectedValueOnce("boom");
    expect((await deleteUserAction(2)).error).toBe("boom");
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("管理者でなければrequireAdminSessionの例外はそのまま伝わる", async () => {
    requireAdminSession.mockRejectedValue(new Error("forbidden"));
    await expect(deleteUserAction(2)).rejects.toThrow("forbidden");
  });
});
