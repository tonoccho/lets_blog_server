import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LlmProviderPanel } from "../LlmProviderPanel";
import { fetchLlmProviderAction, selectLlmProviderAction } from "../actions";

/** issue #1644: プロバイダーの保存に成功したときだけ onChanged で親へ知らせる(モデル表示を切り替え先へ更新するため)。 */
jest.mock("../actions", () => ({
  fetchLlmProviderAction: jest.fn(),
  selectLlmProviderAction: jest.fn(),
}));

const fetchMock = fetchLlmProviderAction as jest.MockedFunction<typeof fetchLlmProviderAction>;
const selectMock = selectLlmProviderAction as jest.MockedFunction<typeof selectLlmProviderAction>;

const data = { availableProviders: ["OLLAMA", "OPENAI", "CLAUDE"], selected: null };

describe("LlmProviderPanel", () => {
  beforeEach(() => {
    fetchMock.mockReset().mockResolvedValue({ ...data, selected: "CLAUDE" });
    selectMock.mockReset();
  });

  it("保存に成功すると onChanged を呼ぶ", async () => {
    selectMock.mockResolvedValue({});
    const onChanged = jest.fn();
    render(<LlmProviderPanel projectId={1} initialData={data} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(selectMock).toHaveBeenCalledWith(1, "CLAUDE");
  });

  it("保存に失敗すると onChanged を呼ばずエラーを表示する", async () => {
    selectMock.mockResolvedValue({ error: "失敗" });
    const onChanged = jest.fn();
    render(<LlmProviderPanel projectId={1} initialData={data} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "OPENAI" } });

    await screen.findByText("失敗");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("onChanged が無くても保存できる", async () => {
    selectMock.mockResolvedValue({});
    render(<LlmProviderPanel projectId={1} initialData={data} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    await screen.findByText("保存しました。");
  });
});
