import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ReviewStepSettingsPanel } from "../ReviewStepSettingsPanel";
import { updateReviewStepSettingAction } from "../actions";
import type { ReviewStepSettingsResponse } from "@/lib/apiClient";

/**
 * issue #1212: レビューステップ別(#1210)のLLM設定(#1211のAPI)を、プロジェクト詳細画面の
 * AIモデル管理カード(LLMタブ)から確認・変更するパネル。
 *
 * 受け入れ基準:
 * - 5つのレビューステップの行が、#1211のAPIが返す順序(initialData.steps の順序)で表示される
 * - 未設定のステップは「(プロジェクト既定を使用)」と表示される
 * - providerを選んで保存すると、表示が保存済みの値へ更新される
 * - modelを選んで保存すると、表示が保存済みの値へ更新される
 *
 * issue #1223(解除操作と保存失敗時のエラー表示)の受け入れ基準:
 * - 設定済みのstepのproviderを空へ戻して保存すると「(プロジェクト既定を使用)」表示になる
 * - 設定済みのstepのmodelを空へ戻して保存すると「(プロジェクト既定を使用)」表示になる
 * - 保存が失敗するとエラーメッセージが表示される
 * - 保存が失敗すると、その行の表示は保存前の値のままになる
 */
jest.mock("../actions", () => ({
  updateReviewStepSettingAction: jest.fn(),
}));

const updateMock = updateReviewStepSettingAction as jest.MockedFunction<typeof updateReviewStepSettingAction>;

function initialData(overrides: Partial<ReviewStepSettingsResponse> = {}): ReviewStepSettingsResponse {
  return {
    steps: [
      { stepKey: "JAPANESE", provider: null, model: null },
      { stepKey: "PROOFREADING", provider: null, model: null },
      { stepKey: "FACT_CHECK", provider: null, model: null },
      { stepKey: "READER_PERSPECTIVE", provider: null, model: null },
      { stepKey: "STYLE", provider: null, model: null },
    ],
    availableProviders: ["OLLAMA", "OPENAI", "CLAUDE"],
    availableModels: ["gpt-4o-mini", "gpt-4o"],
    ...overrides,
  };
}

function rowFor(label: string) {
  return screen.getByText(label).closest("tr") as HTMLTableRowElement;
}

describe("ReviewStepSettingsPanel", () => {
  beforeEach(() => {
    updateMock.mockReset();
  });

  it("5つのレビューステップの行が、#1211のAPIが返す順序(日本語チェック→校正チェック→校閲→読者視点でのチェック→文体チェック)で表示される", () => {
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const rows = screen.getAllByRole("row").slice(1); // 先頭は見出し行
    const labels = rows.map((row) => within(row).getAllByRole("cell")[0].textContent);
    expect(labels).toEqual(["日本語チェック", "校正チェック", "校閲", "読者視点でのチェック", "文体チェック"]);
  });

  it("未設定のステップは、provider・modelともに「(プロジェクト既定を使用)」が選択されている", () => {
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("日本語チェック");
    const providerSelect = within(row).getByLabelText("日本語チェックのプロバイダー") as HTMLSelectElement;
    const modelSelect = within(row).getByLabelText("日本語チェックのモデル") as HTMLSelectElement;
    expect(providerSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
    expect(modelSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
  });

  it("providerを選んで保存すると、表示が保存済みの値へ更新される", async () => {
    updateMock.mockResolvedValue({});
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("日本語チェック");
    fireEvent.change(within(row).getByLabelText("日本語チェックのプロバイダー"), {
      target: { value: "OPENAI" },
    });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(updateMock).toHaveBeenCalledWith(1, "JAPANESE", "OPENAI", "");
    });
    const providerSelect = within(row).getByLabelText("日本語チェックのプロバイダー") as HTMLSelectElement;
    expect(providerSelect.selectedOptions[0].textContent).toBe("OpenAI (ChatGPT)");
  });

  it("modelを選んで保存すると、表示が保存済みの値へ更新される", async () => {
    updateMock.mockResolvedValue({});
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("校正チェック");
    fireEvent.change(within(row).getByLabelText("校正チェックのモデル"), {
      target: { value: "gpt-4o" },
    });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(updateMock).toHaveBeenCalledWith(1, "PROOFREADING", "", "gpt-4o");
    });
    const modelSelect = within(row).getByLabelText("校正チェックのモデル") as HTMLSelectElement;
    expect(modelSelect.selectedOptions[0].textContent).toBe("gpt-4o");
  });

  it("設定済みのステップのproviderを空へ戻して保存すると、「(プロジェクト既定を使用)」表示になる", async () => {
    updateMock.mockResolvedValue({});
    render(
      <ReviewStepSettingsPanel
        projectId={1}
        initialData={initialData({
          steps: [{ stepKey: "JAPANESE", provider: "OPENAI", model: "gpt-4o" }],
        })}
      />
    );

    const row = rowFor("日本語チェック");
    fireEvent.change(within(row).getByLabelText("日本語チェックのプロバイダー"), { target: { value: "" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(updateMock).toHaveBeenCalledWith(1, "JAPANESE", "", "gpt-4o");
    });
    const providerSelect = within(row).getByLabelText("日本語チェックのプロバイダー") as HTMLSelectElement;
    expect(providerSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
    const modelSelect = within(row).getByLabelText("日本語チェックのモデル") as HTMLSelectElement;
    expect(modelSelect.selectedOptions[0].textContent).toBe("gpt-4o");
  });

  it("設定済みのステップのmodelを空へ戻して保存すると、「(プロジェクト既定を使用)」表示になる", async () => {
    updateMock.mockResolvedValue({});
    render(
      <ReviewStepSettingsPanel
        projectId={1}
        initialData={initialData({
          steps: [{ stepKey: "PROOFREADING", provider: "CLAUDE", model: "gpt-4o" }],
        })}
      />
    );

    const row = rowFor("校正チェック");
    fireEvent.change(within(row).getByLabelText("校正チェックのモデル"), { target: { value: "" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(updateMock).toHaveBeenCalledWith(1, "PROOFREADING", "CLAUDE", "");
    });
    const modelSelect = within(row).getByLabelText("校正チェックのモデル") as HTMLSelectElement;
    expect(modelSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
  });

  it("保存に失敗すると、エラーメッセージが表示される", async () => {
    updateMock.mockResolvedValue({ error: "保存に失敗しました" });
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("校閲");
    fireEvent.change(within(row).getByLabelText("校閲のプロバイダー"), { target: { value: "CLAUDE" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    const alert = await within(row).findByText("保存に失敗しました");
    expect(alert).toHaveClass("text-red-600");
  });

  it("保存に失敗すると、その行の表示は保存前の値のままになる", async () => {
    updateMock.mockResolvedValue({ error: "保存に失敗しました" });
    render(
      <ReviewStepSettingsPanel
        projectId={1}
        initialData={initialData({
          steps: [{ stepKey: "FACT_CHECK", provider: "OLLAMA", model: null }],
        })}
      />
    );

    const row = rowFor("校閲");
    fireEvent.change(within(row).getByLabelText("校閲のプロバイダー"), { target: { value: "CLAUDE" } });
    fireEvent.change(within(row).getByLabelText("校閲のモデル"), { target: { value: "gpt-4o" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await within(row).findByText("保存に失敗しました");
    const providerSelect = within(row).getByLabelText("校閲のプロバイダー") as HTMLSelectElement;
    const modelSelect = within(row).getByLabelText("校閲のモデル") as HTMLSelectElement;
    expect(providerSelect.selectedOptions[0].textContent).toBe("Ollama");
    expect(modelSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
  });

  it("保存の通信自体が例外で失敗しても、エラーメッセージを表示して保存前の値へ戻す", async () => {
    updateMock.mockRejectedValue(new Error("network"));
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("文体チェック");
    fireEvent.change(within(row).getByLabelText("文体チェックのプロバイダー"), { target: { value: "OPENAI" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));

    await within(row).findByText("保存に失敗しました");
    const providerSelect = within(row).getByLabelText("文体チェックのプロバイダー") as HTMLSelectElement;
    expect(providerSelect.selectedOptions[0].textContent).toBe("(プロジェクト既定を使用)");
    expect(within(row).getByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("失敗後に再度保存して成功すると、エラーメッセージが消える", async () => {
    updateMock.mockResolvedValueOnce({ error: "保存に失敗しました" });
    updateMock.mockResolvedValueOnce({});
    render(<ReviewStepSettingsPanel projectId={1} initialData={initialData()} />);

    const row = rowFor("日本語チェック");
    fireEvent.change(within(row).getByLabelText("日本語チェックのプロバイダー"), { target: { value: "OPENAI" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));
    await within(row).findByText("保存に失敗しました");

    fireEvent.change(within(row).getByLabelText("日本語チェックのプロバイダー"), { target: { value: "OPENAI" } });
    fireEvent.click(within(row).getByRole("button", { name: "保存" }));
    await waitFor(() => {
      expect(within(row).queryByText("保存に失敗しました")).not.toBeInTheDocument();
    });
  });

  it("未知のプロバイダーは表示名の対応表に無ければ値そのものを表示する", () => {
    render(
      <ReviewStepSettingsPanel
        projectId={1}
        initialData={initialData({ availableProviders: ["OLLAMA", "OPENAI", "CLAUDE", "FUTURE_PROVIDER"] })}
      />
    );

    const row = rowFor("日本語チェック");
    expect(within(row).getByRole("option", { name: "FUTURE_PROVIDER" })).toBeInTheDocument();
  });
});
