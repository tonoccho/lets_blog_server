import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ImageProviderPanel } from "../ImageProviderPanel";
import { fetchImageProviderAction, selectImageProviderAction } from "../actions";

jest.mock("../actions", () => ({
  fetchImageProviderAction: jest.fn(),
  selectImageProviderAction: jest.fn(),
}));

const fetchMock = fetchImageProviderAction as jest.MockedFunction<typeof fetchImageProviderAction>;
const selectMock = selectImageProviderAction as jest.MockedFunction<typeof selectImageProviderAction>;

const data = { availableProviders: ["COMFYUI", "CHATGPT"], selected: null };

describe("ImageProviderPanel", () => {
  beforeEach(() => {
    fetchMock.mockReset().mockResolvedValue({ data: { ...data, selected: "CHATGPT" } });
    selectMock.mockReset().mockResolvedValue({});
  });

  function change() {
    render(<ImageProviderPanel projectId={1} initialData={data} />);
    fireEvent.change(screen.getByLabelText("画像生成AI(このプロジェクトの既定)"), { target: { value: "CHATGPT" } });
  }

  it("保存に成功すると、再取得して「保存しました。」を表示する", async () => {
    change();

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(selectMock).toHaveBeenCalledWith(1, "CHATGPT");
    expect(fetchMock).toHaveBeenCalledWith(1);
  });

  it("保存に失敗すると、理由を表示し再取得しない", async () => {
    selectMock.mockResolvedValue({ error: "保存できません" });
    change();

    expect(await screen.findByText("保存できません")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("保存後の再取得に失敗すると、成功ではなく失敗の理由を表示する", async () => {
    fetchMock.mockResolvedValue({ error: "再取得に失敗(HTTP 502)" });
    change();

    expect(await screen.findByText(/HTTP 502/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("保存しました。")).not.toBeInTheDocument());
  });
});
