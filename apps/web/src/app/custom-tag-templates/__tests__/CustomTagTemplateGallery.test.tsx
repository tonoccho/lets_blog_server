import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CustomTagTemplate, Project } from "@/lib/apiClient";
import { CustomTagTemplateGallery } from "../CustomTagTemplateGallery";
import {
  cloneCustomTagTemplateAction,
  publishCustomTagTemplateAction,
  unpublishCustomTagTemplateAction,
} from "../actions";

const push = jest.fn();
const refresh = jest.fn();

jest.mock("next/navigation", () => ({
  useRouter: () => ({ push, refresh }),
  useSearchParams: () => new URLSearchParams(),
}));

jest.mock("../actions", () => ({
  cloneCustomTagTemplateAction: jest.fn(),
  publishCustomTagTemplateAction: jest.fn(),
  unpublishCustomTagTemplateAction: jest.fn(),
}));

const publishMock = publishCustomTagTemplateAction as jest.MockedFunction<typeof publishCustomTagTemplateAction>;
const unpublishMock = unpublishCustomTagTemplateAction as jest.MockedFunction<typeof unpublishCustomTagTemplateAction>;
const cloneMock = cloneCustomTagTemplateAction as jest.MockedFunction<typeof cloneCustomTagTemplateAction>;

function template(overrides: Partial<CustomTagTemplate>): CustomTagTemplate {
  return {
    id: 1,
    templateName: "未公開テンプレート",
    description: null,
    category: null,
    htmlTemplate: "<div>{{content}}</div>",
    cssContent: null,
    version: 1,
    isPublished: false,
    originalTagId: null,
    projectId: null,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

const projects: Project[] = [];

function renderGallery(props: { templates: CustomTagTemplate[]; mine?: boolean; showAll?: boolean; currentProjectId?: number | null }) {
  return render(
    <CustomTagTemplateGallery
      templates={props.templates}
      projects={projects}
      currentProjectId={props.currentProjectId ?? null}
      showAll={props.showAll ?? false}
      mine={props.mine ?? false}
    />
  );
}

describe("CustomTagTemplateGallery 公開/非公開の切り替え", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(window, "alert").mockImplementation(() => {});
  });

  it("未公開のテンプレートの詳細には「公開する」だけが出る", () => {
    renderGallery({ templates: [template({ isPublished: false })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));

    expect(screen.getByRole("button", { name: "公開する" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "非公開に戻す" })).not.toBeInTheDocument();
  });

  it("公開済みのテンプレートの詳細には「非公開に戻す」だけが出る", () => {
    renderGallery({ templates: [template({ isPublished: true })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));

    expect(screen.getByRole("button", { name: "非公開に戻す" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "公開する" })).not.toBeInTheDocument();
  });

  it("「公開する」で公開アクションを呼び、詳細を閉じて一覧を再取得する", async () => {
    publishMock.mockResolvedValue({ data: template({ isPublished: true }) });
    renderGallery({ templates: [template({ id: 7 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(publishMock).toHaveBeenCalledWith(7);
    expect(unpublishMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "公開する" })).not.toBeInTheDocument();
  });

  it("「非公開に戻す」で非公開アクションを呼び、一覧を再取得する", async () => {
    unpublishMock.mockResolvedValue({ data: template({ isPublished: false }) });
    renderGallery({ templates: [template({ id: 8, isPublished: true })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "非公開に戻す" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(unpublishMock).toHaveBeenCalledWith(8);
    expect(publishMock).not.toHaveBeenCalled();
  });

  it("アクションがエラーを返したら理由を通知し、詳細は開いたままにする", async () => {
    publishMock.mockResolvedValue({ error: "forbidden" });
    renderGallery({ templates: [template({ id: 9 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("公開状態の変更に失敗しました: forbidden"));
    expect(refresh).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "公開する" })).toBeInTheDocument();
  });

  it("アクションが例外を投げても理由を通知する", async () => {
    publishMock.mockRejectedValue(new Error("network"));
    renderGallery({ templates: [template({ id: 10 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("公開状態の変更に失敗しました: Error: network"));
    expect(refresh).not.toHaveBeenCalled();
  });

  it("複製の既存動作は変わらない(複製後に詳細を閉じて再取得する)", async () => {
    cloneMock.mockResolvedValue({ data: template({ id: 11 }) });
    jest.spyOn(window, "confirm").mockReturnValue(true);
    renderGallery({ templates: [template({ id: 6 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "複製" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
  });
});

describe("CustomTagTemplateGallery 自分のテンプレートの絞り込み", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("絞り込みが未選択なら、チェックボックスは外れている", () => {
    renderGallery({ templates: [] });
    expect(screen.getByRole("checkbox", { name: "自分が作ったものだけ" })).not.toBeChecked();
  });

  it("絞り込みが選択済みなら、チェックされている", () => {
    renderGallery({ templates: [], mine: true });
    expect(screen.getByRole("checkbox", { name: "自分が作ったものだけ" })).toBeChecked();
  });

  it("チェックすると mine=true 付きの一覧へ移動する", () => {
    renderGallery({ templates: [], currentProjectId: 3 });
    fireEvent.click(screen.getByRole("checkbox", { name: "自分が作ったものだけ" }));

    expect(push).toHaveBeenCalledTimes(1);
    const url = new URL(push.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.get("mine")).toBe("true");
    expect(url.searchParams.get("projectId")).toBe("3");
  });

  it("チェックを外すと mine を付けない一覧へ移動する", () => {
    renderGallery({ templates: [], mine: true });
    fireEvent.click(screen.getByRole("checkbox", { name: "自分が作ったものだけ" }));

    const url = new URL(push.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.has("mine")).toBe(false);
  });

  it("絞り込み中の検索とカテゴリー変更でも mine を保つ", () => {
    renderGallery({ templates: [template({ category: "装飾" })], mine: true });
    fireEvent.click(screen.getByRole("button", { name: "装飾" }));

    const url = new URL(push.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.get("mine")).toBe("true");
    expect(url.searchParams.get("category")).toBe("装飾");
  });

  it("未公開を含めるを切り替えても mine を保つ", () => {
    renderGallery({ templates: [], mine: true });
    fireEvent.click(screen.getByRole("checkbox", { name: "未公開を含める" }));

    const url = new URL(push.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.get("mine")).toBe("true");
    expect(url.searchParams.get("showAll")).toBe("true");
  });

  it("表示スコープを切り替えると mine を外したスコープ一覧へ移動する", () => {
    renderGallery({ templates: [] });
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "" } });
    expect(push).toHaveBeenCalledWith("/custom-tag-templates");
  });
});
