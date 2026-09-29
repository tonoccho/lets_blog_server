import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TermComparisonTable } from "../TermComparisonTable";
import {
  applyToEnvironmentAction,
  syncTermToMasterAction,
  deleteTermEverywhereAction,
  fetchTermComparisonAction,
  editTermAndSyncAction,
  syncAllTermsToMasterAction,
} from "../actions";
import type { TermComparisonPage, TermComparisonRow, TermEnvironmentValue } from "@/lib/apiClient";

/**
 * issue #1051: 新規追加・編集フォームの<form>にmethod="post"を明示した(JS無効時のネイティブ
 * GETフォールバックで名前・スラッグ等がURLへ漏れるのを防ぐ)。
 *
 * `scripts/check-changed-coverage.py` はファイル単位で分岐カバレッジを見るため、この変更に
 * よって「変更したファイル」として扱われる本コンポーネントの既存ロジック(環境間の同期・
 * 編集・削除・一括同期・ページング)も、この機会にあわせて可能な範囲で押さえる。
 */
jest.mock("../actions", () => ({
  applyToEnvironmentAction: jest.fn(),
  syncTermToMasterAction: jest.fn(),
  deleteTermEverywhereAction: jest.fn(),
  fetchTermComparisonAction: jest.fn(),
  editTermAndSyncAction: jest.fn(),
  syncAllTermsToMasterAction: jest.fn(),
}));

const applyMock = applyToEnvironmentAction as jest.MockedFunction<typeof applyToEnvironmentAction>;
const syncMock = syncTermToMasterAction as jest.MockedFunction<typeof syncTermToMasterAction>;
const deleteMock = deleteTermEverywhereAction as jest.MockedFunction<typeof deleteTermEverywhereAction>;
const fetchPageMock = fetchTermComparisonAction as jest.MockedFunction<typeof fetchTermComparisonAction>;
const editMock = editTermAndSyncAction as jest.MockedFunction<typeof editTermAndSyncAction>;
const syncAllMock = syncAllTermsToMasterAction as jest.MockedFunction<typeof syncAllTermsToMasterAction>;

function envValue(overrides: Partial<TermEnvironmentValue> = {}): TermEnvironmentValue {
  return {
    available: true,
    error: false,
    errorMessage: null,
    slug: "akismet",
    parentSlug: null,
    description: null,
    ...overrides,
  };
}

function row(overrides: Partial<TermComparisonRow> = {}): TermComparisonRow {
  return {
    name: "お知らせ",
    slug: "news",
    local: envValue(),
    test: envValue(),
    production: envValue(),
    ...overrides,
  };
}

function page(overrides: Partial<TermComparisonPage> = {}): TermComparisonPage {
  return {
    items: [row()],
    page: 0,
    size: 20,
    totalCount: 1,
    masterEnvironment: "production",
    ...overrides,
  };
}

describe("TermComparisonTable", () => {
  beforeEach(() => {
    applyMock.mockReset();
    syncMock.mockReset();
    deleteMock.mockReset();
    fetchPageMock.mockReset();
    editMock.mockReset();
    syncAllMock.mockReset();
    window.confirm = jest.fn();
  });

  function renderTable(overrides: Partial<TermComparisonPage> = {}, kind: "category" | "tag" = "category") {
    return render(<TermComparisonTable projectId={1} kind={kind} initialPage={page(overrides)} />);
  }

  it("新規追加フォームはmethod=\"post\"を持つ", () => {
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));

    expect(container.querySelector("form")?.getAttribute("method")).toBe("post");
  });

  it("項目が無ければその旨を表示する", () => {
    renderTable({ items: [] });

    expect(screen.getByText("カテゴリはまだありません。")).toBeInTheDocument();
  });

  it("タグの場合はラベルが切り替わり、親カテゴリ欄が無い", () => {
    renderTable({}, "tag");

    expect(screen.getAllByText("タグ", { exact: false }).length).toBeGreaterThan(0);
    expect(screen.queryByText("親カテゴリ")).not.toBeInTheDocument();
  });

  it("取得エラーの環境は「エラー」を表示する", () => {
    renderTable({ items: [row({ local: envValue({ error: true, errorMessage: "取得失敗" }) })] });

    const errorTexts = screen.getAllByText("エラー");
    expect(errorTexts.length).toBeGreaterThan(0);
    expect(errorTexts[0]).toHaveAttribute("title", "取得失敗");
  });

  it("未対応環境は「対象外」を表示する", () => {
    renderTable({ items: [row({ local: envValue({ available: false, slug: null }) })] });

    expect(screen.getAllByText("対象外").length).toBeGreaterThan(0);
  });

  it("マスター環境に値がある行では同期ボタンを表示し、無い行では表示しない", () => {
    renderTable({
      items: [
        row({ slug: "with-master", production: envValue({ slug: "with-master" }) }),
        row({ slug: "no-master", production: envValue({ available: false, slug: null }) }),
      ],
    });

    const syncButtons = screen.getAllByRole("button", { name: "同期" });
    expect(syncButtons).toHaveLength(1);
  });

  it("同期確認をキャンセルすると同期アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    expect(syncMock).not.toHaveBeenCalled();
  });

  it("同期に成功すると成功メッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    syncMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    await waitFor(() => {
      expect(screen.getByText("同期しました。")).toBeInTheDocument();
    });
  });

  it("同期に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    syncMock.mockResolvedValue({ error: "同期に失敗しました" });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    await waitFor(() => {
      expect(screen.getByText("同期に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("削除すると成功メッセージを表示し、編集中の行を閉じる", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除しました。")).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "保存(全環境に反映)" })).not.toBeInTheDocument();
  });

  it("削除に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "削除に失敗しました" });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除に失敗しました")).toBeInTheDocument();
    });
  });

  it("編集ボタンを再度押すと編集フォームを閉じる", () => {
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.queryByRole("button", { name: "保存(全環境に反映)" })).not.toBeInTheDocument();
  });

  it("マスターに値がある行の編集を確認キャンセルすると保存アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    expect(editMock).not.toHaveBeenCalled();
  });

  it("マスターに値が無い行の編集は新規作成の確認文言になり、保存できる", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    editMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    renderTable({
      items: [row({ production: envValue({ available: false, slug: null }) })],
    });

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    await waitFor(() => {
      expect(editMock).toHaveBeenCalled();
    });
    expect((window.confirm as jest.Mock).mock.calls[0][0]).toContain("新規作成");
  });

  it("編集の保存に失敗するとエラーを表示したままにする", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    editMock.mockResolvedValue({ error: "保存に失敗しました" });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("保存に失敗しました")).toBeInTheDocument();
    });
  });

  it("一括同期確認をキャンセルするとアクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    expect(syncAllMock).not.toHaveBeenCalled();
  });

  it("一括同期で差分が無い場合は専用メッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    syncAllMock.mockResolvedValue({ results: [] });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("マスターとの差分はありませんでした。")).toBeInTheDocument();
    });
  });

  it("一括同期で操作が発生した場合は件数を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    syncAllMock.mockResolvedValue({ results: [{ slug: "a" }, { slug: "b" }] as never });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("2件の操作でマスターに揃えました。")).toBeInTheDocument();
    });
  });

  it("一括同期に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    syncAllMock.mockResolvedValue({ error: "一括同期に失敗しました" });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("一括同期に失敗しました")).toBeInTheDocument();
    });
  });

  it("環境更新ボタンで一覧を再取得する", async () => {
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getAllByRole("button", { name: /を更新$/ })[0]);

    await waitFor(() => {
      expect(fetchPageMock).toHaveBeenCalled();
    });
  });

  it("先頭かつ最終ページでは前へ・次へが無効になる", () => {
    renderTable({ page: 0, size: 20, totalCount: 1 });

    expect(screen.getByRole("button", { name: "前へ" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
  });

  it("次のページがある場合は「次へ」で取得する", async () => {
    fetchPageMock.mockResolvedValue(page({ page: 1 }));
    renderTable({ page: 0, size: 1, totalCount: 2 });

    fireEvent.click(screen.getByRole("button", { name: "次へ" }));

    await waitFor(() => {
      expect(fetchPageMock).toHaveBeenCalledWith(1, "category", 1);
    });
  });

  it("新規追加フォームを送信すると一覧を更新して閉じる", async () => {
    applyMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    fireEvent.change(screen.getAllByLabelText("名前")[0], { target: { value: "イベント" } });
    fireEvent.change(screen.getByLabelText("スラッグ"), { target: { value: "event" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(applyMock).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.queryByLabelText("スラッグ")).not.toBeInTheDocument();
    });
  });

  it("新規追加に失敗するとエラーを表示したままフォームを開いておく", async () => {
    applyMock.mockResolvedValue({ error: "追加に失敗しました" });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    fireEvent.change(screen.getAllByLabelText("名前")[0], { target: { value: "イベント" } });
    fireEvent.change(screen.getByLabelText("スラッグ"), { target: { value: "event" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("追加に失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("スラッグ")).toBeInTheDocument();
  });

  it("マスターと異なる値のセルは赤字で強調表示する", () => {
    renderTable({
      items: [
        row({
          production: envValue({ description: "マスターの説明" }),
          local: envValue({ description: "ローカルだけの説明" }),
        }),
      ],
    });

    const localDescriptionCell = screen.getByText("ローカルだけの説明");
    expect(localDescriptionCell).toHaveClass("text-red-600");
  });

  it("値が無いセルはマスター/非マスターで表示文言を変える", () => {
    renderTable({
      items: [row({ production: envValue({ description: null }), local: envValue({ description: null }) })],
    });

    expect(screen.getAllByText("(未設定)").length).toBeGreaterThan(0);
    expect(screen.getAllByText("(未登録)").length).toBeGreaterThan(0);
  });
});

/** issue #1414(#1413の横展開): マウント前は押せない(TermComparisonTable.mountGate.test.tsx)。マウント後は押せる。 */
describe("TermComparisonTable(マウント後、issue #1414)", () => {
  it("マウント後は新規追加フォームの送信ボタンが有効", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={page()} />);

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));

    expect(screen.getByRole("button", { name: "マスター環境に追加" })).toBeEnabled();
  });

  it("マウント後は編集フォームの送信ボタンが有効", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={page()} />);

    fireEvent.click(screen.getByRole("button", { name: "編集" }));

    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeEnabled();
  });
});
