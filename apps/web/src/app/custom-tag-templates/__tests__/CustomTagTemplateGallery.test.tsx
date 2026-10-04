import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CustomTagTemplate, Project } from "@/lib/apiClient";
import { CustomTagTemplateGallery } from "../CustomTagTemplateGallery";
import {
  applyCustomTagTemplateAction,
  cloneCustomTagTemplateAction,
  createCustomTagTemplateAction,
  deleteCustomTagTemplateAction,
  publishCustomTagTemplateAction,
  unpublishCustomTagTemplateAction,
  updateCustomTagTemplateAction,
} from "../actions";

const push = jest.fn();
const refresh = jest.fn();

jest.mock("next/navigation", () => ({
  useRouter: () => ({ push, refresh }),
  useSearchParams: () => new URLSearchParams(),
}));

jest.mock("../actions", () => ({
  applyCustomTagTemplateAction: jest.fn(),
  cloneCustomTagTemplateAction: jest.fn(),
  createCustomTagTemplateAction: jest.fn(),
  deleteCustomTagTemplateAction: jest.fn(),
  publishCustomTagTemplateAction: jest.fn(),
  unpublishCustomTagTemplateAction: jest.fn(),
  updateCustomTagTemplateAction: jest.fn(),
}));

const publishMock = publishCustomTagTemplateAction as jest.MockedFunction<typeof publishCustomTagTemplateAction>;
const unpublishMock = unpublishCustomTagTemplateAction as jest.MockedFunction<typeof unpublishCustomTagTemplateAction>;
const applyMock = applyCustomTagTemplateAction as jest.MockedFunction<typeof applyCustomTagTemplateAction>;
const createMock = createCustomTagTemplateAction as jest.MockedFunction<typeof createCustomTagTemplateAction>;
const updateMock = updateCustomTagTemplateAction as jest.MockedFunction<typeof updateCustomTagTemplateAction>;
const deleteMock = deleteCustomTagTemplateAction as jest.MockedFunction<typeof deleteCustomTagTemplateAction>;
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

describe("CustomTagTemplateGallery プロジェクトで使う(issue #1131)", () => {
  const project = (id: number, name: string): Project =>
    ({ id, name, slug: `p${id}` }) as unknown as Project;
  const twoProjects = [project(3, "プロジェクトA"), project(4, "プロジェクトB")];

  function openDetail(props: { currentProjectId?: number | null; templates?: CustomTagTemplate[] } = {}) {
    render(
      <CustomTagTemplateGallery
        templates={props.templates ?? [template({ id: 21 })]}
        projects={twoProjects}
        currentProjectId={props.currentProjectId ?? null}
        showAll={false}
        mine={false}
      />
    );
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
  }

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(window, "alert").mockImplementation(() => {});
  });

  it("複製を作成ボタンは残したまま、プロジェクトで使うボタンが出る。タグ名かプロジェクトが空の間は押せない", () => {
    openDetail();
    expect(screen.getByRole("button", { name: "複製を作成" })).toBeInTheDocument();
    const apply = screen.getByRole("button", { name: "プロジェクトで使う" });
    expect(apply).toBeDisabled();

    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    expect(apply).toBeDisabled();

    fireEvent.change(screen.getByLabelText("適用先プロジェクト"), { target: { value: "4" } });
    expect(apply).toBeEnabled();
  });

  it("表示中のプロジェクトを適用先の初期値にする", () => {
    openDetail({ currentProjectId: 3 });
    expect(screen.getByLabelText("適用先プロジェクト")).toHaveValue("3");
  });

  it("プロジェクトの無いスコープでは、テンプレート自身のプロジェクトを初期値にする", () => {
    openDetail({ templates: [template({ id: 22, projectId: 4 })] });
    expect(screen.getByLabelText("適用先プロジェクト")).toHaveValue("4");
  });

  it("適用するとタグ作成アクションを呼び、成功メッセージを出す(詳細は閉じない)", async () => {
    applyMock.mockResolvedValue({ data: { id: 1, tagName: "note" } as never });
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: " note " } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() => expect(applyMock).toHaveBeenCalledWith(21, { projectId: 3, tagName: "note" }));
    expect(await screen.findByText(/\[note\] をプロジェクトAのカスタムタグとして作成しました/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "プロジェクトで使う" })).toBeInTheDocument();
  });

  it("同名タグがある(409)などの失敗は理由を通知し、成功メッセージは出さない", async () => {
    applyMock.mockResolvedValue({ error: "タグ名 'note' は既に登録されています" });
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() =>
      expect(window.alert).toHaveBeenCalledWith("プロジェクトでの利用に失敗しました: タグ名 'note' は既に登録されています")
    );
    expect(screen.queryByText(/作成しました/)).not.toBeInTheDocument();
  });

  it("アクションが例外を投げても理由を通知する", async () => {
    applyMock.mockRejectedValue(new Error("network"));
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() =>
      expect(window.alert).toHaveBeenCalledWith("プロジェクトでの利用に失敗しました: Error: network")
    );
  });

  it("テンプレートのプロジェクトが一覧に無い場合は、Project #id 表記の成功メッセージを出す", async () => {
    applyMock.mockResolvedValue({ data: { id: 1, tagName: "note" } as never });
    openDetail({ templates: [template({ id: 23, projectId: 99 })] });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    expect(await screen.findByText(/\[note\] をProject #99のカスタムタグとして作成しました/)).toBeInTheDocument();
    expect(applyMock).toHaveBeenCalledWith(23, { projectId: 99, tagName: "note" });
  });
});

describe("CustomTagTemplateGallery 作成・編集・削除(issue #1550)", () => {
  const project = (id: number, name: string): Project =>
    ({ id, name, slug: `p${id}` }) as unknown as Project;

  function renderWith(opts: { templates?: CustomTagTemplate[]; currentProjectId?: number | null } = {}) {
    return render(
      <CustomTagTemplateGallery
        templates={opts.templates ?? [template({ id: 31, templateName: "既存", description: "説明", category: "装飾", cssContent: ".a{}" })]}
        projects={[project(3, "プロジェクトA")]}
        currentProjectId={opts.currentProjectId ?? null}
        showAll={false}
        mine={false}
      />
    );
  }

  const openCreate = () => fireEvent.click(screen.getByRole("button", { name: "新しいテンプレート" }));
  const openDetail = () => fireEvent.click(screen.getByRole("heading", { name: "既存", level: 3 }));
  const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(window, "alert").mockImplementation(() => {});
  });

  describe("作成", () => {
    it("全項目を入力して作成すると、入力どおりに作成アクションを呼び、閉じて再取得する", async () => {
      createMock.mockResolvedValue({ data: template({ id: 40 }) });
      renderWith();
      openCreate();
      fill("テンプレート名", "  新規  ");
      fill("説明", "d");
      fill("カテゴリー", "c");
      fill("HTMLテンプレート", "<p>{{content}}</p>");
      fill("CSS(任意)", "p{}");
      fireEvent.change(screen.getByLabelText("スコープ"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("button", { name: "作成" }));

      await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
      expect(createMock).toHaveBeenCalledWith({
        templateName: "新規",
        description: "d",
        category: "c",
        htmlTemplate: "<p>{{content}}</p>",
        cssContent: "p{}",
        projectId: 3,
      });
      expect(screen.queryByRole("button", { name: "作成" })).not.toBeInTheDocument();
    });

    it("スコープの既定は表示中のプロジェクト。グローバルを選ぶと projectId を送らない(null)", async () => {
      createMock.mockResolvedValue({ data: template({ id: 41 }) });
      renderWith({ currentProjectId: 3 });
      openCreate();
      expect(screen.getByLabelText("スコープ")).toHaveValue("3");
      fireEvent.change(screen.getByLabelText("スコープ"), { target: { value: "" } });
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      await waitFor(() => expect(createMock).toHaveBeenCalled());
      expect(createMock.mock.calls[0][0].projectId).toBeNull();
    });

    it("名前が空なら送信せず、必須の旨を表示し、入力は残る", () => {
      renderWith();
      openCreate();
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(createMock).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toHaveTextContent("必須");
      expect(screen.getByLabelText("HTMLテンプレート")).toHaveValue("<p/>");
    });

    it("HTMLが空白だけなら送信しない", () => {
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "   ");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(createMock).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });

    it("アクションがエラーを返したら理由を表示し、入力は残り、再取得しない", async () => {
      createMock.mockResolvedValue({ error: "boom" });
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("作成に失敗しました: boom");
      expect(screen.getByLabelText("テンプレート名")).toHaveValue("n");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      createMock.mockRejectedValue(new Error("network"));
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("作成に失敗しました: Error: network");
    });

    it("キャンセルでフォームを閉じ、何も呼ばない", () => {
      renderWith();
      openCreate();
      fireEvent.click(screen.getByRole("button", { name: "キャンセル" }));
      expect(screen.queryByLabelText("テンプレート名")).not.toBeInTheDocument();
      expect(createMock).not.toHaveBeenCalled();
    });
  });

  describe("編集", () => {
    it("詳細パネルに名前・説明・カテゴリー・HTML・CSSが入力欄として表示される", () => {
      renderWith();
      openDetail();
      expect(screen.getByLabelText("テンプレート名")).toHaveValue("既存");
      expect(screen.getByLabelText("説明")).toHaveValue("説明");
      expect(screen.getByLabelText("カテゴリー")).toHaveValue("装飾");
      expect(screen.getByLabelText("HTMLテンプレート")).toHaveValue("<div>{{content}}</div>");
      expect(screen.getByLabelText("CSS(任意)")).toHaveValue(".a{}");
    });

    it("説明・カテゴリー・CSSが無いテンプレートは空欄で表示される", () => {
      renderWith({ templates: [template({ id: 32, templateName: "既存" })] });
      openDetail();
      expect(screen.getByLabelText("説明")).toHaveValue("");
      expect(screen.getByLabelText("カテゴリー")).toHaveValue("");
      expect(screen.getByLabelText("CSS(任意)")).toHaveValue("");
    });

    it("変更して保存すると、変更後の内容と元のスコープで更新アクションを呼び、閉じて再取得する", async () => {
      updateMock.mockResolvedValue({ data: template({ id: 31 }) });
      renderWith({ templates: [template({ id: 31, templateName: "既存", projectId: 3 })] });
      openDetail();
      fill("テンプレート名", "変更後");
      fill("HTMLテンプレート", "<b>{{content}}</b>");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));

      await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
      expect(updateMock).toHaveBeenCalledWith(31, {
        templateName: "変更後",
        description: undefined,
        category: undefined,
        htmlTemplate: "<b>{{content}}</b>",
        cssContent: undefined,
        projectId: 3,
      });
      expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
    });

    it("名前かHTMLを空にすると保存せず、必須の旨を表示する", () => {
      renderWith();
      openDetail();
      fill("テンプレート名", " ");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(updateMock).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toHaveTextContent("必須");
      fill("テンプレート名", "x");
      fill("HTMLテンプレート", "");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(updateMock).not.toHaveBeenCalled();
    });

    it("アクションがエラーを返したら理由を表示し、変更した内容は残る", async () => {
      updateMock.mockResolvedValue({ error: "not found" });
      renderWith();
      openDetail();
      fill("テンプレート名", "変更後");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("保存に失敗しました: not found");
      expect(screen.getByLabelText("テンプレート名")).toHaveValue("変更後");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      updateMock.mockRejectedValue(new Error("network"));
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("保存に失敗しました: Error: network");
    });
  });

  describe("削除", () => {
    it("確認を承諾すると削除アクションを呼び、閉じて再取得する", async () => {
      deleteMock.mockResolvedValue({ success: true });
      const confirm = jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
      expect(confirm).toHaveBeenCalled();
      expect(deleteMock).toHaveBeenCalledWith(31);
    });

    it("確認をキャンセルすると何も呼ばず、パネルは開いたまま", () => {
      jest.spyOn(window, "confirm").mockReturnValue(false);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(deleteMock).not.toHaveBeenCalled();
      expect(refresh).not.toHaveBeenCalled();
      expect(screen.getByRole("button", { name: "削除" })).toBeInTheDocument();
    });

    it("アクションがエラーを返したら理由を表示し、再取得しない", async () => {
      deleteMock.mockResolvedValue({ error: "forbidden" });
      jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("削除に失敗しました: forbidden");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      deleteMock.mockRejectedValue(new Error("network"));
      jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("削除に失敗しました: Error: network");
    });
  });
});
