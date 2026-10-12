import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ComfyUiCheckpointTable } from "../ComfyUiCheckpointTable";
import { fetchComfyUiCheckpointsAction, selectComfyUiCheckpointAction } from "../actions";
import type { ComfyUiCheckpointListResponse } from "@/lib/apiClient";

/**
 * issue #1746: ComfyUI は GPU PC で動く外部サーバーへ URL で接続する構成になったため、
 * チェックポイントの導入・削除の操作は無い(GPU PC 側で models/ に置く)。一覧と選択だけが残る。
 */
jest.mock("../actions", () => ({
  fetchComfyUiCheckpointsAction: jest.fn(),
  selectComfyUiCheckpointAction: jest.fn(),
}));

const fetchListMock = fetchComfyUiCheckpointsAction as jest.MockedFunction<typeof fetchComfyUiCheckpointsAction>;
const selectMock = selectComfyUiCheckpointAction as jest.MockedFunction<typeof selectComfyUiCheckpointAction>;

function initialData(overrides: Partial<ComfyUiCheckpointListResponse> = {}): ComfyUiCheckpointListResponse {
  return {
    checkpoints: ["sd_xl_base_1.0.safetensors", "other.safetensors"],
    selected: "sd_xl_base_1.0.safetensors",
    ...overrides,
  };
}

describe("ComfyUiCheckpointTable", () => {
  beforeEach(() => {
    fetchListMock.mockReset();
    selectMock.mockReset();
  });

  it("導入・削除の操作は表示されない(一覧と選択だけ)", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    expect(screen.queryByRole("button", { name: /新規インストール/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "削除" })).not.toBeInTheDocument();
    expect(screen.queryByText("ダウンロードURL")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "選択" })).toHaveLength(2);
    expect(screen.getByText("other.safetensors")).toBeInTheDocument();
  });

  it("チェックポイントが無ければ、GPU PC 側で置くことを案内する", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData({ checkpoints: [] })} />);

    expect(screen.getByText(/選択できるチェックポイントがありません/)).toBeInTheDocument();
    expect(screen.getByText(/ComfyUI を動かしている PC/)).toBeInTheDocument();
  });

  it("選択中のチェックポイントにはバッジが付き、選択ボタンが無効化される", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const selectedRow = screen.getByRole("cell", { name: /sd_xl_base_1\.0\.safetensors/ }).closest("tr") as HTMLElement;
    expect(selectedRow).toContainElement(screen.getByText("選択中"));
    const buttons = Array.from(selectedRow.querySelectorAll("button"));
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toBeDisabled();
  });

  it("未選択のチェックポイントを選択すると成功メッセージを表示し、一覧を再取得する", async () => {
    selectMock.mockResolvedValue({});
    fetchListMock.mockResolvedValue({ data: initialData({ selected: "other.safetensors" }) });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "選択" }));

    await waitFor(() => {
      expect(screen.getByText("切り替えました。")).toBeInTheDocument();
    });
    expect(fetchListMock).toHaveBeenCalledWith(1);
  });

  it("選択後の一覧の再取得に失敗すると、失敗の理由を表示する(issue #1715)", async () => {
    selectMock.mockResolvedValue({});
    fetchListMock.mockResolvedValue({ error: "ComfyUIに接続できません" });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "選択" }));

    expect(await screen.findByText(/ComfyUIに接続できません/)).toBeInTheDocument();
  });

  it("選択に失敗するとエラーメッセージを表示する", async () => {
    selectMock.mockResolvedValue({ error: "切替に失敗しました" });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "選択" }));

    await waitFor(() => {
      expect(screen.getByText("切替に失敗しました")).toBeInTheDocument();
    });
  });

  it("選択の処理中は、その行のボタンが「切替中…」になり無効化される", async () => {
    let resolveSelect: (v: { error?: string }) => void = () => {};
    selectMock.mockReturnValue(new Promise((resolve) => { resolveSelect = resolve; }));
    fetchListMock.mockResolvedValue({ data: initialData({ selected: "other.safetensors" }) });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "選択" }));

    expect(within(otherRow).getByRole("button", { name: "切替中…" })).toBeDisabled();
    resolveSelect({});
    await waitFor(() => expect(screen.getByText("切り替えました。")).toBeInTheDocument());
  });
});
