import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LlmModelPanel } from "../LlmModelPanel";
import { fetchLlmModelsAction, selectLlmModelAction } from "../actions";
import type { LlmModelListResponse } from "@/lib/apiClient";

/**
 * issue #1051: フォームの<form>にmethod="post"を明示した(JS無効時のネイティブGET
 * フォールバックでモデル名がURLへ漏れるのを防ぐ)。それ以外の挙動は変えていないため、
 * このテストは変更点(method="post")の確認と、保存成功/失敗の主要な分岐を最小限で押さえる。
 */
jest.mock("../actions", () => ({
  fetchLlmModelsAction: jest.fn(),
  selectLlmModelAction: jest.fn(),
}));

const fetchMock = fetchLlmModelsAction as jest.MockedFunction<typeof fetchLlmModelsAction>;
const selectMock = selectLlmModelAction as jest.MockedFunction<typeof selectLlmModelAction>;

function initialData(overrides: Partial<LlmModelListResponse> = {}): LlmModelListResponse {
  return {
    selected: "gpt-4o-mini",
    availableModels: ["gpt-4o-mini", "gpt-4o"],
    ...overrides,
  };
}

describe("LlmModelPanel", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    selectMock.mockReset();
  });

  it("フォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    const form = container.querySelector("form");
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("空欄のまま送信すると保存アクションを呼ばない", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData({ selected: "" })} />);

    fireEvent.change(screen.getByPlaceholderText("gpt-4o-mini"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(selectMock).not.toHaveBeenCalled();
  });

  it("保存に失敗するとエラーを表示する", async () => {
    selectMock.mockResolvedValue({ error: "モデル名が不正です" });
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(screen.getByText("モデル名が不正です")).toBeInTheDocument();
    });
  });

  it("保存に成功すると成功メッセージを表示し、一覧を再取得する", async () => {
    selectMock.mockResolvedValue({});
    fetchMock.mockResolvedValue(initialData({ selected: "gpt-4o" }));
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(screen.getByText("保存しました。")).toBeInTheDocument();
    });
    expect(fetchMock).toHaveBeenCalledWith(1);
  });

  it("選択可能なモデルのボタンを押すと入力欄へ反映する", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "gpt-4o" }));

    expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o");
  });
});
