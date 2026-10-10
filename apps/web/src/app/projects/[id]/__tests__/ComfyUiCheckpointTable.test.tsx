import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ComfyUiCheckpointTable } from "../ComfyUiCheckpointTable";
import {
  fetchComfyUiCheckpointsAction,
  selectComfyUiCheckpointAction,
  installComfyUiCheckpointAction,
  deleteComfyUiCheckpointAction,
  fetchGenerationJobAction,
} from "../actions";
import { MAX_CONSECUTIVE_POLL_FAILURES } from "../useGenerationJobPolling";
import type { ComfyUiCheckpointListResponse, GenerationJobDetail } from "@/lib/apiClient";

/**
 * issue #1051: 新規インストールフォームの<form>にmethod="post"を明示した(JS無効時の
 * ネイティブGETフォールバックでダウンロードURL等がURLへ漏れるのを防ぐ)。
 *
 * `scripts/check-changed-coverage.py` はファイル単位で分岐カバレッジを見るため、この変更に
 * よって「変更したファイル」として扱われる本コンポーネントの既存ロジック(選択・削除・
 * インストールの非同期ジョブポーリング)も、この機会にあわせて可能な範囲で押さえる。
 */
jest.mock("../actions", () => ({
  fetchComfyUiCheckpointsAction: jest.fn(),
  selectComfyUiCheckpointAction: jest.fn(),
  installComfyUiCheckpointAction: jest.fn(),
  deleteComfyUiCheckpointAction: jest.fn(),
  fetchGenerationJobAction: jest.fn(),
}));

const fetchListMock = fetchComfyUiCheckpointsAction as jest.MockedFunction<typeof fetchComfyUiCheckpointsAction>;
const selectMock = selectComfyUiCheckpointAction as jest.MockedFunction<typeof selectComfyUiCheckpointAction>;
const installMock = installComfyUiCheckpointAction as jest.MockedFunction<typeof installComfyUiCheckpointAction>;
const deleteMock = deleteComfyUiCheckpointAction as jest.MockedFunction<typeof deleteComfyUiCheckpointAction>;
const fetchJobMock = fetchGenerationJobAction as jest.MockedFunction<typeof fetchGenerationJobAction>;

function initialData(overrides: Partial<ComfyUiCheckpointListResponse> = {}): ComfyUiCheckpointListResponse {
  return {
    checkpoints: ["sd_xl_base_1.0.safetensors", "other.safetensors"],
    selected: "sd_xl_base_1.0.safetensors",
    ...overrides,
  };
}

function job(overrides: Partial<GenerationJobDetail> = {}): GenerationJobDetail {
  return {
    id: 1,
    type: "comfyui_checkpoint_install",
    status: "done",
    requestPayload: null,
    resultPayload: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("ComfyUiCheckpointTable", () => {
  beforeEach(() => {
    fetchListMock.mockReset();
    selectMock.mockReset();
    installMock.mockReset();
    deleteMock.mockReset();
    fetchJobMock.mockReset();
    window.confirm = jest.fn();
  });

  it("新規インストールフォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    const form = container.querySelector("form");
    expect(form).not.toBeNull();
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("チェックポイントが無ければその旨を表示する", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData({ checkpoints: [] })} />);

    expect(screen.getByText("インストール済みのチェックポイントはまだありません。")).toBeInTheDocument();
  });

  it("選択中のチェックポイントにはバッジが付き、選択・削除ボタンが無効化される", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const selectedRow = screen.getByRole("cell", { name: /sd_xl_base_1\.0\.safetensors/ }).closest("tr") as HTMLElement;
    expect(selectedRow).toContainElement(screen.getByText("選択中"));
    const buttons = Array.from(selectedRow.querySelectorAll("button"));
    expect(buttons.every((b) => b.hasAttribute("disabled"))).toBe(true);
    expect(buttons.find((b) => b.textContent === "削除")?.getAttribute("title")).toBe(
      "選択中のチェックポイントは削除できません"
    );
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

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("削除に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "削除に失敗しました" });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除ジョブが失敗すると、resultPayloadのエラー内容を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ jobId: 42 });
    fetchJobMock.mockResolvedValue(
      job({ status: "failed", resultPayload: JSON.stringify({ error: "ファイル削除に失敗しました" }) })
    );
    fetchListMock.mockResolvedValue({ data: initialData() });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("ファイル削除に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除ジョブが不正なJSONで失敗すると、既定のエラー文言を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ jobId: 43 });
    fetchJobMock.mockResolvedValue(job({ status: "failed", resultPayload: "not-json" }));
    fetchListMock.mockResolvedValue({ data: initialData() });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);

    const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
    fireEvent.click(within(otherRow).getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("処理に失敗しました。")).toBeInTheDocument();
    });
  });

  it("入力が空のままインストールを送信しても何も起きない", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    fireEvent.click(screen.getByRole("button", { name: "インストール" }));

    expect(installMock).not.toHaveBeenCalled();
  });

  it("不正なファイル名だとエラーを表示し、インストールアクションを呼ばない", () => {
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    fireEvent.change(screen.getByPlaceholderText("https://huggingface.co/.../model.safetensors"), {
      target: { value: "https://example.com/model.safetensors" },
    });
    fireEvent.change(screen.getByPlaceholderText("my-model.safetensors"), {
      target: { value: "invalid name!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "インストール" }));

    expect(screen.getByText(/ファイル名は英数字/)).toBeInTheDocument();
    expect(installMock).not.toHaveBeenCalled();
  });

  it("インストールに失敗するとエラーメッセージを表示する", async () => {
    installMock.mockResolvedValue({ error: "インストールに失敗しました" });
    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    fireEvent.change(screen.getByPlaceholderText("https://huggingface.co/.../model.safetensors"), {
      target: { value: "https://example.com/model.safetensors" },
    });
    fireEvent.change(screen.getByPlaceholderText("my-model.safetensors"), {
      target: { value: "model.safetensors" },
    });
    fireEvent.click(screen.getByRole("button", { name: "インストール" }));

    await waitFor(() => {
      expect(screen.getByText("インストールに失敗しました")).toBeInTheDocument();
    });
  });

  it("インストールが進行中は進捗を表示し、完了するとフォームを閉じて成功を表示する", async () => {
    installMock.mockResolvedValue({ jobId: 99 });
    fetchJobMock
      .mockResolvedValueOnce(
        job({ status: "running", resultPayload: JSON.stringify({ phase: "downloading", percent: 40 }) })
      )
      .mockResolvedValueOnce(job({ status: "done", resultPayload: null }));
    fetchListMock.mockResolvedValue({ data: initialData() });

    render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    fireEvent.change(screen.getByPlaceholderText("https://huggingface.co/.../model.safetensors"), {
      target: { value: "https://example.com/model.safetensors" },
    });
    fireEvent.change(screen.getByPlaceholderText("my-model.safetensors"), {
      target: { value: "model.safetensors" },
    });
    fireEvent.click(screen.getByRole("button", { name: "インストール" }));

    await waitFor(() => {
      expect(screen.getByText(/downloading 40%/)).toBeInTheDocument();
    });

    await waitFor(
      () => {
        expect(screen.getByText("完了しました。")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
    expect(screen.queryByPlaceholderText("my-model.safetensors")).not.toBeInTheDocument();
  });

  describe("進捗の取得失敗(#1716)", () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    async function startDelete() {
      (window.confirm as jest.Mock).mockReturnValue(true);
      deleteMock.mockResolvedValue({ jobId: 42 });
      fetchListMock.mockResolvedValue({ data: initialData() });
      render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
      const otherRow = screen.getByText("other.safetensors").closest("tr") as HTMLElement;
      fireEvent.click(within(otherRow).getByRole("button", { name: "削除" }));
      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });
      return otherRow;
    }

    it("取得が1回失敗しても続けて、done になれば完了を表示する", async () => {
      fetchJobMock.mockRejectedValueOnce(new Error("503")).mockResolvedValue(job({ status: "done" }));
      await startDelete();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000);
      });

      expect(screen.getByText("完了しました。")).toBeInTheDocument();
    });

    it("取得が続けて失敗したら、実行中の表示(削除中…)を解除し、確認できなかった理由を表示する", async () => {
      fetchJobMock.mockRejectedValue(new Error("503 Service Unavailable"));
      const row = await startDelete();
      expect(within(row).getByRole("button", { name: "削除中…" })).toBeDisabled();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000 * MAX_CONSECUTIVE_POLL_FAILURES);
      });

      expect(within(row).getByRole("button", { name: "削除" })).toBeEnabled();
      expect(screen.getByText(/進捗を確認できませんでした.*503 Service Unavailable/)).toBeInTheDocument();
    });

    it("インストールの進捗が確認できなくなったときも、インストール中の表示を解除する", async () => {
      installMock.mockResolvedValue({ jobId: 7 });
      fetchJobMock.mockRejectedValue(new Error("boom"));
      fetchListMock.mockResolvedValue({ data: initialData() });
      render(<ComfyUiCheckpointTable projectId={1} initialData={initialData()} />);
      fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
      fireEvent.change(screen.getByLabelText("ダウンロードURL"), { target: { value: "https://example.com/m.safetensors" } });
      fireEvent.change(screen.getByLabelText(/保存ファイル名/), { target: { value: "m.safetensors" } });
      fireEvent.click(screen.getByRole("button", { name: "インストール" }));
      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000 * MAX_CONSECUTIVE_POLL_FAILURES);
      });

      expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
      expect(screen.getByText(/進捗を確認できませんでした.*boom/)).toBeInTheDocument();
    });
  });
});
