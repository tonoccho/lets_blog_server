import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CustomTagTemplate, Project } from "@/lib/apiClient";
import { CustomTagTemplateGallery } from "../CustomTagTemplateGallery";

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
 * Next.js の実行基盤(next/navigation のルーター・next/cache・next/headers・next-auth など)、ブラウザの
 * ダイアログ(window.alert / window.confirm)、外部との通信(fetch)だけ(#1834)。
 * バックエンドの応答は fetch の層で模し、アクションの失敗はその HTTP の失敗応答として渡す。
 */
const push = jest.fn();
const refresh = jest.fn();

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
  useRouter: () => ({ push, refresh }),
  useSearchParams: () => new URLSearchParams(),
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

type Backend = (params: string[], body: Record<string, unknown> | null) => unknown;

/** project-service(カスタムタグテンプレート)の代役(gateway の向こう側)。呼び出し引数と応答を各テストが決める。 */
const publish = jest.fn<unknown, Parameters<Backend>>();
const unpublish = jest.fn<unknown, Parameters<Backend>>();
const apply = jest.fn<unknown, Parameters<Backend>>();
const create = jest.fn<unknown, Parameters<Backend>>();
const update = jest.fn<unknown, Parameters<Backend>>();
const remove = jest.fn<unknown, Parameters<Backend>>();
const clone = jest.fn<unknown, Parameters<Backend>>();

/** バックエンドの代役を fetch の経路に載せる。解決値は 200 の JSON、拒否は理由つきの 403 応答になる。 */
function route(backend: jest.Mock<unknown, Parameters<Backend>>) {
  return async (_url: string, init: RequestInit, params: string[]) => {
    try {
      const body = init.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null;
      return jsonResponse((await backend(params, body)) ?? null);
    } catch (err) {
      return textResponse(err instanceof Error ? err.message : String(err), 403);
    }
  };
}

function installApi() {
  installFetch({
    "POST /api/custom-tag-templates/:id/publish": route(publish),
    "POST /api/custom-tag-templates/:id/unpublish": route(unpublish),
    "POST /api/custom-tag-templates/:id/apply": route(apply),
    "POST /api/custom-tag-templates/:id/clone": route(clone),
    "POST /api/custom-tag-templates": route(create),
    "PUT /api/custom-tag-templates/:id": route(update),
    "DELETE /api/custom-tag-templates/:id": route(remove),
  });
}

beforeEach(() => {
  push.mockClear();
  refresh.mockClear();
  for (const backend of [publish, unpublish, apply, create, update, remove, clone]) backend.mockReset();
  resetServerRuntime(ADMIN_SESSION);
  installApi();
});

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
    publish.mockResolvedValue(template({ isPublished: true }));
    renderGallery({ templates: [template({ id: 7 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(publish).toHaveBeenCalledWith(["7"], null);
    expect(unpublish).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "公開する" })).not.toBeInTheDocument();
  });

  it("「非公開に戻す」で非公開アクションを呼び、一覧を再取得する", async () => {
    unpublish.mockResolvedValue(template({ isPublished: false }));
    renderGallery({ templates: [template({ id: 8, isPublished: true })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "非公開に戻す" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(unpublish).toHaveBeenCalledWith(["8"], null);
    expect(publish).not.toHaveBeenCalled();
  });

  it("アクションがエラーを返したら理由を通知し、詳細は開いたままにする", async () => {
    publish.mockRejectedValue(new Error("forbidden"));
    renderGallery({ templates: [template({ id: 9 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("公開状態の変更に失敗しました: APIエラー (403): forbidden"));
    expect(refresh).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "公開する" })).toBeInTheDocument();
  });

  it("アクションが例外を投げても理由を通知する", async () => {
    // 管理者でなくなった(セッション切れ)とき、アクションはリダイレクトの例外を投げる。
    resetServerRuntime(null);
    renderGallery({ templates: [template({ id: 10 })] });
    fireEvent.click(screen.getByRole("heading", { name: "未公開テンプレート", level: 3 }));
    fireEvent.click(screen.getByRole("button", { name: "公開する" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("公開状態の変更に失敗しました: Error: NEXT_REDIRECT:/login"));
    expect(refresh).not.toHaveBeenCalled();
  });

  it("複製の既存動作は変わらない(複製後に詳細を閉じて再取得する)", async () => {
    clone.mockResolvedValue(template({ id: 11 }));
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
    apply.mockResolvedValue({ id: 1, tagName: "note" });
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: " note " } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() => expect(apply).toHaveBeenCalledWith(["21"], { projectId: 3, tagName: "note" }));
    expect(await screen.findByText(/\[note\] をプロジェクトAのカスタムタグとして作成しました/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "プロジェクトで使う" })).toBeInTheDocument();
  });

  it("同名タグがある(409)などの失敗は理由を通知し、成功メッセージは出さない", async () => {
    apply.mockRejectedValue(new Error("タグ名 'note' は既に登録されています"));
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() =>
      expect(window.alert).toHaveBeenCalledWith("プロジェクトでの利用に失敗しました: APIエラー (403): タグ名 'note' は既に登録されています")
    );
    expect(screen.queryByText(/作成しました/)).not.toBeInTheDocument();
  });

  it("アクションが例外を投げても理由を通知する", async () => {
    resetServerRuntime(null);
    openDetail({ currentProjectId: 3 });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    await waitFor(() =>
      expect(window.alert).toHaveBeenCalledWith("プロジェクトでの利用に失敗しました: Error: NEXT_REDIRECT:/login")
    );
  });

  it("テンプレートのプロジェクトが一覧に無い場合は、Project #id 表記の成功メッセージを出す", async () => {
    apply.mockResolvedValue({ id: 1, tagName: "note" });
    openDetail({ templates: [template({ id: 23, projectId: 99 })] });
    fireEvent.change(screen.getByPlaceholderText("タグ名(例: note)"), { target: { value: "note" } });
    fireEvent.click(screen.getByRole("button", { name: "プロジェクトで使う" }));

    expect(await screen.findByText(/\[note\] をProject #99のカスタムタグとして作成しました/)).toBeInTheDocument();
    expect(apply).toHaveBeenCalledWith(["23"], { projectId: 99, tagName: "note" });
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
      create.mockResolvedValue(template({ id: 40 }));
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
      expect(create).toHaveBeenCalledWith([], {
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
      create.mockResolvedValue(template({ id: 41 }));
      renderWith({ currentProjectId: 3 });
      openCreate();
      expect(screen.getByLabelText("スコープ")).toHaveValue("3");
      fireEvent.change(screen.getByLabelText("スコープ"), { target: { value: "" } });
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      await waitFor(() => expect(create).toHaveBeenCalled());
      expect(create.mock.calls[0][1]?.projectId).toBeNull();
    });

    it("名前が空なら送信せず、必須の旨を表示し、入力は残る", () => {
      renderWith();
      openCreate();
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(create).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toHaveTextContent("必須");
      expect(screen.getByLabelText("HTMLテンプレート")).toHaveValue("<p/>");
    });

    it("HTMLが空白だけなら送信しない", () => {
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "   ");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(create).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });

    it("アクションがエラーを返したら理由を表示し、入力は残り、再取得しない", async () => {
      create.mockRejectedValue(new Error("boom"));
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("作成に失敗しました: APIエラー (403): boom");
      expect(screen.getByLabelText("テンプレート名")).toHaveValue("n");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      resetServerRuntime(null);
      renderWith();
      openCreate();
      fill("テンプレート名", "n");
      fill("HTMLテンプレート", "<p/>");
      fireEvent.click(screen.getByRole("button", { name: "作成" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("作成に失敗しました: Error: NEXT_REDIRECT:/login");
    });

    it("キャンセルでフォームを閉じ、何も呼ばない", () => {
      renderWith();
      openCreate();
      fireEvent.click(screen.getByRole("button", { name: "キャンセル" }));
      expect(screen.queryByLabelText("テンプレート名")).not.toBeInTheDocument();
      expect(create).not.toHaveBeenCalled();
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
      update.mockResolvedValue(template({ id: 31 }));
      renderWith({ templates: [template({ id: 31, templateName: "既存", projectId: 3 })] });
      openDetail();
      fill("テンプレート名", "変更後");
      fill("HTMLテンプレート", "<b>{{content}}</b>");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));

      await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
      expect(update).toHaveBeenCalledWith(["31"], {
        templateName: "変更後",
        htmlTemplate: "<b>{{content}}</b>",
        projectId: 3,
      });
      expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
    });

    it("名前かHTMLを空にすると保存せず、必須の旨を表示する", () => {
      renderWith();
      openDetail();
      fill("テンプレート名", " ");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(update).not.toHaveBeenCalled();
      expect(screen.getByRole("alert")).toHaveTextContent("必須");
      fill("テンプレート名", "x");
      fill("HTMLテンプレート", "");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(update).not.toHaveBeenCalled();
    });

    it("アクションがエラーを返したら理由を表示し、変更した内容は残る", async () => {
      update.mockRejectedValue(new Error("not found"));
      renderWith();
      openDetail();
      fill("テンプレート名", "変更後");
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("保存に失敗しました: APIエラー (403): not found");
      expect(screen.getByLabelText("テンプレート名")).toHaveValue("変更後");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      resetServerRuntime(null);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "保存" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("保存に失敗しました: Error: NEXT_REDIRECT:/login");
    });
  });

  describe("削除", () => {
    it("確認を承諾すると削除アクションを呼び、閉じて再取得する", async () => {
      remove.mockResolvedValue(null);
      const confirm = jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
      expect(confirm).toHaveBeenCalled();
      expect(remove).toHaveBeenCalledWith(["31"], null);
    });

    it("確認をキャンセルすると何も呼ばず、パネルは開いたまま", () => {
      jest.spyOn(window, "confirm").mockReturnValue(false);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(remove).not.toHaveBeenCalled();
      expect(refresh).not.toHaveBeenCalled();
      expect(screen.getByRole("button", { name: "削除" })).toBeInTheDocument();
    });

    it("アクションがエラーを返したら理由を表示し、再取得しない", async () => {
      remove.mockRejectedValue(new Error("forbidden"));
      jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("削除に失敗しました: APIエラー (403): forbidden");
      expect(refresh).not.toHaveBeenCalled();
    });

    it("アクションが例外を投げても理由を表示する", async () => {
      resetServerRuntime(null);
      jest.spyOn(window, "confirm").mockReturnValue(true);
      renderWith();
      openDetail();
      fireEvent.click(screen.getByRole("button", { name: "削除" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("削除に失敗しました: Error: NEXT_REDIRECT:/login");
    });
  });
});

describe("CustomTagTemplateGallery 複製(詳細パネル)", () => {
  function openClone() {
    render(
      <CustomTagTemplateGallery
        templates={[template({ id: 51, templateName: "元", description: "説明", category: "装飾", projectId: 3 })]}
        projects={[]}
        currentProjectId={null}
        showAll={false}
        mine={false}
      />
    );
    fireEvent.click(screen.getByRole("heading", { name: "元", level: 3 }));
  }

  beforeEach(() => {
    jest.spyOn(window, "alert").mockImplementation(() => {});
  });

  it("名前が空の間は「複製を作成」を押せず、何も送らない", () => {
    openClone();
    const button = screen.getByRole("button", { name: "複製を作成" });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "   " } });
    expect(button).toBeDisabled();
    fireEvent.click(button);

    expect(clone).not.toHaveBeenCalled();
    expect(window.alert).not.toHaveBeenCalled();
  });

  it("確認で承諾すると、元の説明・カテゴリー・スコープを引き継いで複製を要求し、詳細を閉じて再取得する", async () => {
    clone.mockResolvedValue(template({ id: 52 }));
    const confirm = jest.spyOn(window, "confirm").mockReturnValue(true);
    openClone();
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "複製後" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(confirm).toHaveBeenCalledWith("複製後として複製しますか?");
    expect(clone).toHaveBeenCalledWith(["51"], {
      newTemplateName: "複製後",
      description: "説明",
      category: "装飾",
      projectId: 3,
    });
  });

  it("元の説明・カテゴリー・スコープが無ければ、それらを送らない", async () => {
    clone.mockResolvedValue(template({ id: 53 }));
    jest.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <CustomTagTemplateGallery
        templates={[template({ id: 54, templateName: "素" })]}
        projects={[]}
        currentProjectId={null}
        showAll={false}
        mine={false}
      />
    );
    fireEvent.click(screen.getByRole("heading", { name: "素", level: 3 }));
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "n" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    await waitFor(() => expect(clone).toHaveBeenCalled());
    expect(clone).toHaveBeenCalledWith(["54"], { newTemplateName: "n" });
  });

  it("確認をキャンセルすると複製を要求しない", () => {
    jest.spyOn(window, "confirm").mockReturnValue(false);
    openClone();
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "複製後" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    expect(clone).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("バックエンドが拒否したら理由を通知し、再取得しない", async () => {
    clone.mockRejectedValue(new Error("同名があります"));
    jest.spyOn(window, "confirm").mockReturnValue(true);
    openClone();
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "複製後" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("複製に失敗しました: APIエラー (403): 同名があります"));
    expect(refresh).not.toHaveBeenCalled();
  });

  it("セッション切れ(アクションが例外を投げる)のときも理由を通知する", async () => {
    resetServerRuntime(null);
    jest.spyOn(window, "confirm").mockReturnValue(true);
    openClone();
    fireEvent.change(screen.getByPlaceholderText("新しいテンプレート名"), { target: { value: "複製後" } });
    fireEvent.click(screen.getByRole("button", { name: "複製を作成" }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith("複製に失敗しました: Error: NEXT_REDIRECT:/login"));
  });
});

describe("CustomTagTemplateGallery 検索とプレビュー", () => {
  function renderSearch(props: { search?: string; category?: string; currentProjectId?: number | null } = {}) {
    return render(
      <CustomTagTemplateGallery
        templates={[template({ id: 61, category: "装飾" }), template({ id: 62, templateName: "別", category: "その他" })]}
        projects={[]}
        currentProjectId={props.currentProjectId ?? null}
        currentSearch={props.search}
        currentCategory={props.category}
        showAll={false}
        mine={false}
      />
    );
  }

  it("検索語を入れて「検索」を押すと、検索語・カテゴリー・スコープを付けた一覧へ移動する", () => {
    renderSearch({ category: "装飾", currentProjectId: 2 });
    fireEvent.change(screen.getByPlaceholderText("テンプレート名で検索"), { target: { value: "ボタン" } });
    fireEvent.click(screen.getByRole("button", { name: "検索" }));

    const url = new URL(push.mock.calls[0][0], "http://localhost");
    expect(url.pathname).toBe("/custom-tag-templates");
    expect(url.searchParams.get("search")).toBe("ボタン");
    expect(url.searchParams.get("category")).toBe("装飾");
    expect(url.searchParams.get("projectId")).toBe("2");
    expect(url.searchParams.has("showAll")).toBe(false);
  });

  it("検索欄で Enter を押しても検索し、他のキーでは検索しない", () => {
    renderSearch({ search: "初期" });
    const box = screen.getByPlaceholderText("テンプレート名で検索");
    expect(box).toHaveValue("初期");

    fireEvent.keyDown(box, { key: "a" });
    expect(push).not.toHaveBeenCalled();

    fireEvent.keyDown(box, { key: "Enter" });
    expect(new URL(push.mock.calls[0][0], "http://localhost").searchParams.get("search")).toBe("初期");
  });

  it("カテゴリーのボタンはカテゴリー名の昇順で並び、「すべて」でカテゴリーを外す", () => {
    renderSearch({ category: "装飾" });
    const buttons = screen.getAllByRole("button").map((b) => b.textContent);
    expect(buttons.slice(buttons.indexOf("すべて"), buttons.indexOf("すべて") + 3)).toEqual(["すべて", "その他", "装飾"]);

    fireEvent.click(screen.getByRole("button", { name: "すべて" }));
    expect(new URL(push.mock.calls[0][0], "http://localhost").searchParams.has("category")).toBe(false);
  });

  it("表示スコープでプロジェクトを選ぶと、そのプロジェクトの一覧へ移動する", () => {
    render(
      <CustomTagTemplateGallery
        templates={[]}
        projects={[{ id: 8, name: "P8" } as Project]}
        currentProjectId={null}
        showAll={false}
        mine={false}
      />
    );
    fireEvent.change(screen.getByLabelText("表示スコープ"), { target: { value: "8" } });

    expect(push).toHaveBeenCalledWith("/custom-tag-templates?projectId=8");
  });

  it("入力を止めて 300ms 後に、プレビューの iframe を {{content}} と {{attr:xxx}} のサンプルで更新する", () => {
    jest.useFakeTimers();
    try {
      render(
        <CustomTagTemplateGallery templates={[]} projects={[]} currentProjectId={null} showAll={false} mine={false} />
      );
      fireEvent.click(screen.getByRole("button", { name: "新しいテンプレート" }));
      const frame = screen.getByTitle("テンプレートプレビュー") as HTMLIFrameElement;
      expect(frame.getAttribute("srcdoc")).toContain("<body></body>");

      fireEvent.change(screen.getByLabelText("HTMLテンプレート"), { target: { value: "<p class='{{attr:tone}}'>{{content}}</p>" } });
      fireEvent.change(screen.getByLabelText("CSS(任意)"), { target: { value: "p{color:red}" } });
      expect(frame.getAttribute("srcdoc")).toContain("<body></body>");

      act(() => {
        jest.advanceTimersByTime(300);
      });

      const srcdoc = frame.getAttribute("srcdoc") ?? "";
      expect(srcdoc).toContain("<style>p{color:red}</style>");
      expect(srcdoc).toContain("<p class='サンプルtone'>サンプルテキストです。ここに本文が入ります。</p>");
    } finally {
      jest.useRealTimers();
    }
  });
});
