import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { OllamaModelPullForm } from "../OllamaModelPullForm";
import { fetchGenerationJobAction, pullOllamaModelAction } from "../actions";
import { MAX_CONSECUTIVE_POLL_FAILURES } from "../useGenerationJobPolling";
import type { GenerationJobDetail } from "@/lib/apiClient";

/**
 * issue #1675: Ollama の接続セクションに置くモデルのインストール(pull)フォーム。
 * 開始はジョブを作ってすぐ返り、進捗はジョブのポーリング(useGenerationJobPolling / jobProgress)で表示する。
 * 完了(done)も失敗(failed。理由つき)も画面に出し、実行中のモデルの二重開始は新しく始めず実行中として表示する。
 */
jest.mock("../actions", () => ({
  pullOllamaModelAction: jest.fn(),
  fetchGenerationJobAction: jest.fn(),
}));

const pullMock = pullOllamaModelAction as jest.MockedFunction<typeof pullOllamaModelAction>;
const fetchJobMock = fetchGenerationJobAction as jest.MockedFunction<typeof fetchGenerationJobAction>;

const INPUT = "インストールするOllamaモデル名";

function job(overrides: Partial<GenerationJobDetail> = {}): GenerationJobDetail {
  return {
    id: 31,
    type: "ollama_model_pull",
    status: "done",
    requestPayload: null,
    resultPayload: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function submit(model: string) {
  fireEvent.change(screen.getByLabelText(INPUT), { target: { value: model } });
  fireEvent.click(screen.getByRole("button", { name: "インストール" }));
}

beforeEach(() => {
  pullMock.mockReset();
  fetchJobMock.mockReset();
});

describe("OllamaModelPullForm", () => {
  it("フォームはJS無効時のGETフォールバックでモデル名がURLへ漏れないよう method=post を持つ", () => {
    const { container } = render(<OllamaModelPullForm projectId={3} />);

    expect(container.querySelector("form")).toHaveAttribute("method", "post");
  });

  it("モデル名を入れてインストールを押すと、トリムしたモデル名で開始し、進捗を表示して、完了を表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
    fetchJobMock
      .mockResolvedValueOnce(
        job({ status: "running", resultPayload: JSON.stringify({ phase: "downloading", percent: 40 }) })
      )
      .mockResolvedValueOnce(job({ status: "done", resultPayload: JSON.stringify({ success: "true" }) }));
    render(<OllamaModelPullForm projectId={3} />);

    submit("  qwen2.5:7b-instruct ");

    expect(pullMock).toHaveBeenCalledWith(3, "qwen2.5:7b-instruct");
    expect(await screen.findByText(/downloading 40%/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /インストール中/ })).toBeDisabled();
    expect(await screen.findByText("「qwen2.5:7b-instruct」のインストールが完了しました。", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
    expect(screen.getByLabelText(INPUT)).toHaveValue("");
  });

  it("開始直後でまだ進捗が無い間は開始中と表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
    fetchJobMock.mockReturnValue(new Promise(() => {}));
    render(<OllamaModelPullForm projectId={3} />);

    submit("llama3");

    expect(await screen.findByText("開始しています…")).toBeInTheDocument();
  });

  it("ジョブが失敗したら、失敗の理由を表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
    fetchJobMock.mockResolvedValue(
      job({ status: "failed", resultPayload: JSON.stringify({ error: "pull model manifest: file does not exist" }) })
    );
    render(<OllamaModelPullForm projectId={3} />);

    submit("no-such-model");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "インストールに失敗しました: pull model manifest: file does not exist"
    );
    expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
  });

  it("失敗の理由が読めないときは理由不明として失敗を表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
    fetchJobMock.mockResolvedValueOnce(job({ status: "failed", resultPayload: "not json" }));
    render(<OllamaModelPullForm projectId={3} />);

    submit("llama3");
    expect(await screen.findByRole("alert")).toHaveTextContent("インストールに失敗しました(理由は不明です)。");
  });

  it("失敗の結果に理由が無い・結果が空のときも、理由不明として失敗を表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
    fetchJobMock.mockResolvedValueOnce(job({ status: "failed", resultPayload: null }));
    const { unmount } = render(<OllamaModelPullForm projectId={3} />);
    submit("llama3");
    expect(await screen.findByRole("alert")).toHaveTextContent("インストールに失敗しました(理由は不明です)。");
    unmount();

    fetchJobMock.mockResolvedValueOnce(job({ status: "failed", resultPayload: JSON.stringify({ other: 1 }) }));
    render(<OllamaModelPullForm projectId={3} />);
    submit("llama3");
    expect(await screen.findByRole("alert")).toHaveTextContent("インストールに失敗しました(理由は不明です)。");
  });

  it("開始の要求が失敗したらエラーを表示し、ポーリングしない", async () => {
    pullMock.mockResolvedValue({ error: "APIエラー (403): この操作にはadmin権限が必要です" });
    render(<OllamaModelPullForm projectId={3} />);

    submit("llama3");

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (403): この操作にはadmin権限が必要です");
    expect(fetchJobMock).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
  });

  it("同じモデルが実行中のときは、新しく始めず実行中であることを表示して、そのジョブの進捗を表示する", async () => {
    pullMock.mockResolvedValue({ jobId: 29, alreadyRunning: true });
    fetchJobMock.mockResolvedValueOnce(
      job({ id: 29, status: "running", resultPayload: JSON.stringify({ phase: "downloading", percent: 10 }) })
    );
    render(<OllamaModelPullForm projectId={3} />);

    submit("llama3");

    expect(await screen.findByText("同じモデルのインストールがすでに実行中です。その進捗を表示します。")).toBeInTheDocument();
    expect(await screen.findByText(/downloading 10%/)).toBeInTheDocument();
    expect(fetchJobMock).toHaveBeenCalledWith(29);
  });

  it("空や不正な文字を含むモデル名は送らずにエラーを表示する", () => {
    render(<OllamaModelPullForm projectId={3} />);

    submit("   ");
    expect(screen.getByRole("alert")).toHaveTextContent("モデル名を入力してください。");

    submit("bad name;rm");
    expect(screen.getByRole("alert")).toHaveTextContent("モデル名の形式が不正です");
    expect(pullMock).not.toHaveBeenCalled();
  });

  describe("進捗の取得失敗(#1716)", () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    async function start() {
      pullMock.mockResolvedValue({ jobId: 31, alreadyRunning: false });
      render(<OllamaModelPullForm projectId={3} />);
      submit("llama3");
      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });
    }

    it("取得が1回失敗しても続けて、done になれば完了を表示する", async () => {
      fetchJobMock.mockRejectedValueOnce(new Error("503")).mockResolvedValue(job({ status: "done" }));
      await start();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000);
      });

      expect(screen.getByText("「llama3」のインストールが完了しました。")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
    });

    it("取得が続けて失敗したら、入力欄とボタンを使える状態に戻し、確認できなかった理由を表示する", async () => {
      fetchJobMock.mockRejectedValue(new Error("503 Service Unavailable"));
      await start();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000 * MAX_CONSECUTIVE_POLL_FAILURES);
      });

      expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
      expect(screen.getByLabelText(INPUT)).toBeEnabled();
      expect(screen.getByRole("alert")).toHaveTextContent(/進捗を確認できませんでした.*503 Service Unavailable/);
    });
  });
});
