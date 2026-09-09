import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { PluginThemeComparisonTable } from "../PluginThemeComparisonTable";
import {
  applyToEnvironmentAction,
  applyToAllEnvironmentsAction,
  reconcileStateAction,
  deleteSlugEverywhereAction,
  fetchStatusComparisonAction,
} from "../actions";
import type { StatusComparisonPage, StatusComparisonRow, StatusEnvironmentValue } from "@/lib/apiClient";

/**
 * issue #1051: 新規インストールフォームの<form>にmethod="post"を明示した(JS無効時の
 * ネイティブGETフォールバックでダウンロードURL等がURLへ漏れるのを防ぐ)。
 *
 * `scripts/check-changed-coverage.py` はファイル単位で分岐カバレッジを見るため、この変更に
 * よって「変更したファイル」として扱われる本コンポーネントの既存ロジック(環境間の反映・
 * 削除・ページング)も、この機会にあわせて可能な範囲で押さえる。
 */
jest.mock("../actions", () => ({
  applyToEnvironmentAction: jest.fn(),
  applyToAllEnvironmentsAction: jest.fn(),
  reconcileStateAction: jest.fn(),
  deleteSlugEverywhereAction: jest.fn(),
  fetchStatusComparisonAction: jest.fn(),
}));

const applyMock = applyToEnvironmentAction as jest.MockedFunction<typeof applyToEnvironmentAction>;
const applyAllMock = applyToAllEnvironmentsAction as jest.MockedFunction<typeof applyToAllEnvironmentsAction>;
const reconcileMock = reconcileStateAction as jest.MockedFunction<typeof reconcileStateAction>;
const deleteMock = deleteSlugEverywhereAction as jest.MockedFunction<typeof deleteSlugEverywhereAction>;
const fetchPageMock = fetchStatusComparisonAction as jest.MockedFunction<typeof fetchStatusComparisonAction>;

const managedEnvironments = [
  { value: "test" as const, label: "テスト" },
  { value: "production" as const, label: "本番" },
];

function envValue(overrides: Partial<StatusEnvironmentValue> = {}): StatusEnvironmentValue {
  return { available: true, error: false, errorMessage: null, status: "ACTIVE", ...overrides };
}

function row(overrides: Partial<StatusComparisonRow> = {}): StatusComparisonRow {
  return {
    slug: "akismet",
    local: envValue(),
    test: envValue(),
    production: envValue({ status: "NOT_INSTALLED" }),
    ...overrides,
  };
}

function page(overrides: Partial<StatusComparisonPage> = {}): StatusComparisonPage {
  return {
    items: [row()],
    page: 0,
    size: 20,
    totalCount: 1,
    masterEnvironment: "production",
    ...overrides,
  };
}

describe("PluginThemeComparisonTable", () => {
  beforeEach(() => {
    applyMock.mockReset();
    applyAllMock.mockReset();
    reconcileMock.mockReset();
    deleteMock.mockReset();
    fetchPageMock.mockReset();
    window.confirm = jest.fn();
  });

  function renderTable(overrides: Partial<StatusComparisonPage> = {}, kind: "plugin" | "theme" = "plugin") {
    return render(
      <PluginThemeComparisonTable
        projectId={1}
        kind={kind}
        initialPage={page(overrides)}
        managedEnvironments={managedEnvironments}
      />
    );
  }

  it("新規インストールフォームはmethod=\"post\"を持つ", () => {
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    expect(container.querySelector("form")?.getAttribute("method")).toBe("post");
  });

  it("項目が無ければその旨を表示する", () => {
    renderTable({ items: [] });

    expect(screen.getByText("プラグインはまだありません。")).toBeInTheDocument();
  });

  it("テーマの場合はラベルが切り替わる", () => {
    renderTable({}, "theme");

    expect(screen.queryByText("プラグインはまだありません。")).not.toBeInTheDocument();
    expect(screen.getAllByText("テーマ", { exact: false }).length).toBeGreaterThan(0);
  });

  it("取得エラーの環境は「エラー」セルを表示する", () => {
    renderTable({ items: [row({ local: envValue({ error: true, errorMessage: "取得失敗" }) })] });

    const errorCell = screen.getByText("エラー");
    expect(errorCell).toHaveAttribute("title", "取得失敗");
  });

  it("未対応環境は「対象外」セルを表示する", () => {
    renderTable({ items: [row({ local: envValue({ available: false, status: null }) })] });

    expect(screen.getByText("対象外")).toBeInTheDocument();
  });

  it("変更が無い状態で反映を押すとエラーを表示し、確認ダイアログを出さない", async () => {
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("変更されたセルがありません。")).toBeInTheDocument();
    });
    expect(window.confirm).not.toHaveBeenCalled();
    expect(reconcileMock).not.toHaveBeenCalled();
  });

  it("変更後に確認をキャンセルすると反映アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    const select = screen.getAllByRole("combobox")[2]; // production列(NOT_INSTALLED)
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    expect(reconcileMock).not.toHaveBeenCalled();
  });

  it("変更を反映すると成功メッセージを表示し、一覧を再取得する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    reconcileMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    const select = screen.getAllByRole("combobox")[2];
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("反映しました。")).toBeInTheDocument();
    });
    expect(fetchPageMock).toHaveBeenCalledWith(1, "plugin", 0);
  });

  it("反映に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    reconcileMock.mockResolvedValue({ error: "反映に失敗しました" });
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    const select = screen.getAllByRole("combobox")[2];
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("反映に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("削除に成功すると成功メッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除しました。")).toBeInTheDocument();
    });
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

  it("先頭ページでは「前へ」が無効、最終ページでは「次へ」が無効になる", () => {
    renderTable({ page: 0, size: 20, totalCount: 1 });

    expect(screen.getByRole("button", { name: "前へ" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
  });

  it("次のページがある場合は「次へ」で取得する", async () => {
    fetchPageMock.mockResolvedValue(page({ page: 1 }));
    renderTable({ page: 0, size: 1, totalCount: 2 });

    fireEvent.click(screen.getByRole("button", { name: "次へ" }));

    await waitFor(() => {
      expect(fetchPageMock).toHaveBeenCalledWith(1, "plugin", 1);
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

  it("新規インストールフォームで単一環境へ送信すると一覧を更新して閉じる", async () => {
    applyMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "test" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(applyMock).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.queryByLabelText("対象環境")).not.toBeInTheDocument();
    });
  });

  it("新規インストールフォームで「全ての環境」へ送信するとapplyToAllEnvironmentsActionを呼ぶ", async () => {
    applyAllMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "__all__" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(applyAllMock).toHaveBeenCalled();
    });
    expect(applyMock).not.toHaveBeenCalled();
  });

  it("新規インストールに失敗するとエラーを表示したままフォームを開いておく", async () => {
    applyMock.mockResolvedValue({ error: "インストールに失敗しました" });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "test" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("インストールに失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("対象環境")).toBeInTheDocument();
  });

  it("テーマがACTIVEの場合、無効の選択肢を無効化する", () => {
    renderTable({ items: [row({ local: envValue({ status: "ACTIVE" }) })] }, "theme");

    const select = screen.getAllByRole("combobox")[0] as HTMLSelectElement;
    const inactiveOption = within(select).getByRole("option", { name: "無効" }) as HTMLOptionElement;
    expect(inactiveOption.disabled).toBe(true);
  });
});
