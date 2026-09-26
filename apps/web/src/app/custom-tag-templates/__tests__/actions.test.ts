import { applyCustomTagTemplateAction } from "../actions";
import { applyCustomTagTemplate } from "@/lib/apiClient";
import { revalidatePath } from "next/cache";
import { requireAdminSession } from "@/lib/session";

jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn() }));
jest.mock("@/lib/apiClient", () => ({ applyCustomTagTemplate: jest.fn() }));

const applyMock = applyCustomTagTemplate as jest.MockedFunction<typeof applyCustomTagTemplate>;

describe("applyCustomTagTemplateAction (issue #1131)", () => {
  beforeEach(() => jest.clearAllMocks());

  it("管理者セッションを確認し、apply APIを呼んで対象プロジェクトのタグ画面を再検証する", async () => {
    applyMock.mockResolvedValue({ id: 1, tagName: "note" } as never);

    const result = await applyCustomTagTemplateAction(5, { projectId: 3, tagName: "note" });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(applyMock).toHaveBeenCalledWith(5, { projectId: 3, tagName: "note" });
    expect(revalidatePath).toHaveBeenCalledWith("/projects/3/custom-tags");
    expect(result).toEqual({ data: { id: 1, tagName: "note" } });
  });

  it("Error が投げられたらそのメッセージを error に入れる(409 の理由など)", async () => {
    applyMock.mockRejectedValue(new Error("タグ名 'note' は既に登録されています"));

    const result = await applyCustomTagTemplateAction(5, { projectId: 3, tagName: "note" });

    expect(result).toEqual({ error: "タグ名 'note' は既に登録されています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error 以外が投げられても文字列にして error に入れる", async () => {
    applyMock.mockRejectedValue("boom");

    const result = await applyCustomTagTemplateAction(5, { projectId: 3, tagName: "note" });

    expect(result).toEqual({ error: "boom" });
  });
});
