import { StrictMode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { ProjectAiModelsPanel } from "../ProjectAiModelsPanel";
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  selectLlmProviderAction,
  selectLlmModelAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
  fetchAiConnectionsAction,
  fetchProjectConnectionsAction,
} from "../actions";
import type {
  LlmModelListResponse,
  LlmProviderListResponse,
  ReviewStepSettingsResponse,
  ImageProviderListResponse,
  ComfyUiCheckpointListResponse,
} from "@/lib/apiClient";

/**
 * issue #1212: LLMタブに、レビューステップ別設定(ReviewStepSettingsPanel、#1211のAPI)を
 * 追加で読み込み・表示する。既存の LlmProviderPanel / LlmModelPanel の表示・操作は
 * 変えないため、このテストはその2つが引き続き描画されることも確認する。
 */
jest.mock("../actions", () => ({
  fetchLlmModelsAction: jest.fn(),
  selectLlmModelAction: jest.fn(),
  fetchLlmProviderAction: jest.fn(),
  selectLlmProviderAction: jest.fn(),
  fetchReviewStepSettingsAction: jest.fn(),
  updateReviewStepSettingAction: jest.fn(),
  fetchImageProviderAction: jest.fn(),
  selectImageProviderAction: jest.fn(),
  fetchComfyUiCheckpointsAction: jest.fn(),
  fetchAiConnectionsAction: jest.fn(),
  fetchProjectConnectionsAction: jest.fn(),
  updateProjectConnectionAction: jest.fn(),
}));

const fetchLlmModelsMock = fetchLlmModelsAction as jest.MockedFunction<typeof fetchLlmModelsAction>;
const fetchLlmProviderMock = fetchLlmProviderAction as jest.MockedFunction<typeof fetchLlmProviderAction>;
const fetchReviewStepSettingsMock = fetchReviewStepSettingsAction as jest.MockedFunction<
  typeof fetchReviewStepSettingsAction
>;
const fetchImageProviderMock = fetchImageProviderAction as jest.MockedFunction<typeof fetchImageProviderAction>;
const fetchComfyUiCheckpointsMock = fetchComfyUiCheckpointsAction as jest.MockedFunction<
  typeof fetchComfyUiCheckpointsAction
>;

const fetchAiConnectionsMock = fetchAiConnectionsAction as jest.MockedFunction<typeof fetchAiConnectionsAction>;
const fetchProjectConnectionsMock = fetchProjectConnectionsAction as jest.MockedFunction<
  typeof fetchProjectConnectionsAction
>;

function llmModelData(): LlmModelListResponse {
  return { selected: "gpt-4o-mini", availableModels: ["gpt-4o-mini", "gpt-4o"] };
}

function llmProviderData(): LlmProviderListResponse {
  return { availableProviders: ["OPENAI", "CLAUDE"], selected: null };
}

function reviewStepData(): ReviewStepSettingsResponse {
  return {
    steps: [
      { stepKey: "JAPANESE", provider: null, model: null },
      { stepKey: "PROOFREADING", provider: null, model: null },
      { stepKey: "FACT_CHECK", provider: null, model: null },
      { stepKey: "READER_PERSPECTIVE", provider: null, model: null },
      { stepKey: "STYLE", provider: null, model: null },
    ],
    availableProviders: ["OPENAI", "CLAUDE"],
    availableModels: ["gpt-4o-mini", "gpt-4o"],
    availableModelsByProvider: {
      OPENAI: ["gpt-4o-mini", "gpt-4o"],
      CLAUDE: ["claude-sonnet"],
    },
  };
}

function imageProviderData(): ImageProviderListResponse {
  return { availableProviders: ["COMFYUI"], selected: null };
}

function comfyuiData(): ComfyUiCheckpointListResponse {
  return { checkpoints: [], selected: "" };
}

describe("ProjectAiModelsPanel のLLMタブ(issue #1212)", () => {
  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue(reviewStepData());
    fetchImageProviderMock.mockReset().mockResolvedValue(imageProviderData());
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue(comfyuiData());
    fetchAiConnectionsMock.mockReset().mockResolvedValue([]);
    fetchProjectConnectionsMock.mockReset().mockResolvedValue({
      ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
      comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    });
  });

  it("初回表示のLLMタブに、既存パネルと並んでレビューステップ別設定が表示される", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);

    await waitFor(() => {
      expect(screen.getByText("選択中のモデル:")).toBeInTheDocument();
    });
    expect(fetchReviewStepSettingsMock).toHaveBeenCalledWith(1);
    expect(screen.getByText("レビューステップ別のAIモデル設定")).toBeInTheDocument();
    expect(screen.getByText("日本語チェック")).toBeInTheDocument();
    expect(screen.getByText("文体チェック")).toBeInTheDocument();
    // 既存パネルの表示は変わらない(AIプロバイダーのselect、モデル名の表示)
    expect(screen.getByText("AIプロバイダー(このプロジェクトの既定)")).toBeInTheDocument();
  });

  it("画像生成タブを開くとデータ取得までは案内文を表示し、初期表示のLLMタブでは読み込み中と表示する", async () => {
    fetchLlmModelsMock.mockReturnValue(new Promise(() => {})); // 解決させない
    fetchLlmProviderMock.mockReturnValue(new Promise(() => {}));
    fetchReviewStepSettingsMock.mockReturnValue(new Promise(() => {}));

    render(<ProjectAiModelsPanel projectId={1} />);

    // LLMタブは初回マウント時のuseEffectで取得中のため「読み込み中…」ではなく
    // 案内文(handleTabChangeを経由していないのでloadingTabはnullのまま)になる。
    expect(screen.getByText("このタブを開くとデータを取得します。")).toBeInTheDocument();

    const { fireEvent } = await import("@testing-library/react");
    // LLMタブを明示的に開き直すと(llmDataがまだnullのため)handleTabChangeのLLM分岐に入り、
    // 「読み込み中…」に切り替わる。
    fireEvent.click(screen.getByRole("button", { name: "LLM" }));
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  });

  it("画像生成タブへ切り替えると、そのタブのデータを取得して表示する(LLMタブの表示には影響しない)", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByText("選択中のモデル:")).toBeInTheDocument());

    const { fireEvent } = await import("@testing-library/react");
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));

    await waitFor(() => {
      expect(fetchImageProviderMock).toHaveBeenCalledWith(1);
      expect(fetchComfyUiCheckpointsMock).toHaveBeenCalledWith(1);
    });
    expect(screen.queryByText("レビューステップ別のAIモデル設定")).not.toBeInTheDocument();
  });

  /**
   * issue #1310: 開発サーバー(React Strict Mode)ではマウント時のuseEffectが2回発火し、
   * fetchReviewStepSettingsAction等が同じprojectIdに対して2回ずつ並行して呼ばれる。
   * この2重リクエストが引き金となり、リロード直後に保存済みの値が読めないことがある
   * (受け入れテストの間欠的な失敗として観測。#1310のリードエビデンス参照)。
   * 同じprojectIdに対する2回目の発火では取得を行わないことを固定する。
   */
  it("Strict Modeでマウント時のuseEffectが2回発火しても、同じprojectIdへの初回取得は1回だけ行う", async () => {
    render(
      <StrictMode>
        <ProjectAiModelsPanel projectId={1} />
      </StrictMode>
    );

    await waitFor(() => {
      expect(screen.getByText("選択中のモデル:")).toBeInTheDocument();
    });

    expect(fetchLlmModelsMock).toHaveBeenCalledTimes(1);
    expect(fetchLlmProviderMock).toHaveBeenCalledTimes(1);
    expect(fetchReviewStepSettingsMock).toHaveBeenCalledTimes(1);
  });
});

describe("ProjectAiModelsPanel の接続情報セクション(issue #1504)", () => {
  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue(reviewStepData());
    fetchImageProviderMock.mockReset().mockResolvedValue(imageProviderData());
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue(comfyuiData());
    fetchAiConnectionsMock.mockReset().mockResolvedValue([]);
    fetchProjectConnectionsMock.mockReset().mockResolvedValue({
      ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
      comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    });
  });

  it("LLMタブにOllamaの接続情報が表示され、ComfyUIの接続情報は表示されない", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText("Ollamaの接続情報")).toBeInTheDocument();
    expect(await screen.findByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(screen.queryByText("ComfyUIの接続情報")).not.toBeInTheDocument();
  });

  it("LLMタブにChatGPTの接続情報が表示され、画像生成タブには表示されない(issue #1506)", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText("ChatGPTの接続情報")).toBeInTheDocument();

    const { fireEvent } = await import("@testing-library/react");
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));
    await screen.findByText("ComfyUIの接続情報");
    expect(screen.queryByText("ChatGPTの接続情報")).not.toBeInTheDocument();
  });

  it("LLMタブにClaudeの接続情報が表示され、画像生成タブには表示されない(issue #1507)", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText("Claudeの接続情報")).toBeInTheDocument();

    const { fireEvent } = await import("@testing-library/react");
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));
    await screen.findByText("ComfyUIの接続情報");
    expect(screen.queryByText("Claudeの接続情報")).not.toBeInTheDocument();
  });

  it("画像生成タブにComfyUIの接続情報が表示される", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findByText("Ollamaの接続情報");

    const { fireEvent } = await import("@testing-library/react");
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));

    expect(await screen.findByText("ComfyUIの接続情報")).toBeInTheDocument();
    expect(await screen.findByText("http://comfy.default:8188")).toBeInTheDocument();
    expect(screen.queryByText("Ollamaの接続情報")).not.toBeInTheDocument();
  });

  it("接続情報の取得は、タブのデータが表示されるまで始まらない(初期表示を待たせない)", async () => {
    fetchLlmModelsMock.mockReturnValue(new Promise(() => {}));
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(screen.queryByText("Ollamaの接続情報")).not.toBeInTheDocument();
    expect(fetchAiConnectionsMock).not.toHaveBeenCalled();
  });
});

/** issue #1644: プロバイダーを切り替えると、再読み込みなしで切り替え先のモデル(選択中・入力欄・候補)へ変わる。 */
describe("ProjectAiModelsPanel のプロバイダー切り替え(issue #1644)", () => {
  const selectProviderMock = selectLlmProviderAction as jest.MockedFunction<typeof selectLlmProviderAction>;

  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    selectProviderMock.mockReset().mockResolvedValue({});
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue(reviewStepData());
    fetchAiConnectionsMock.mockReset().mockResolvedValue([]);
    fetchProjectConnectionsMock.mockReset().mockResolvedValue({
      ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
      comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    });
  });

  it("プロバイダーを切り替えると、切り替え先のモデルが選択中・入力欄・候補チップに表示される", async () => {
    const { fireEvent } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o-mini"));

    fetchLlmModelsMock.mockResolvedValue({
      selected: "claude-3-5-haiku-20241022",
      availableModels: ["claude-3-5-haiku-20241022", "claude-opus"],
    });
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), {
      target: { value: "CLAUDE" },
    });

    await waitFor(() => expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("claude-3-5-haiku-20241022"));
    expect(screen.getByText("選択中のモデル:").parentElement).toHaveTextContent("claude-3-5-haiku-20241022");
    expect(screen.getByRole("button", { name: "claude-opus" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "gpt-4o" })).not.toBeInTheDocument();
  });

  it("プロバイダーの保存に失敗したときはモデルを再取得しない", async () => {
    const { fireEvent } = await import("@testing-library/react");
    selectProviderMock.mockResolvedValue({ error: "失敗" });
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o-mini"));
    fetchLlmModelsMock.mockClear();

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), {
      target: { value: "CLAUDE" },
    });

    await screen.findByText("失敗");
    expect(fetchLlmModelsMock).not.toHaveBeenCalled();
  });

  it("プロバイダー切り替え後のモデル再取得の最中に保存したモデルは、遅れて届いた古い取得結果で上書きされない", async () => {
    const { fireEvent, act } = await import("@testing-library/react");
    const selectModelMock = selectLlmModelAction as jest.MockedFunction<typeof selectLlmModelAction>;
    selectModelMock.mockReset().mockResolvedValue({});
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o-mini"));

    let resolveStale!: (v: LlmModelListResponse) => void;
    fetchLlmModelsMock.mockReset();
    fetchLlmModelsMock.mockImplementationOnce(() => new Promise((r) => (resolveStale = r)));
    fetchLlmModelsMock.mockResolvedValue({ selected: "gpt-4o", availableModels: ["gpt-4o-mini", "gpt-4o"] });

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });
    await waitFor(() => expect(resolveStale).toBeDefined());

    fireEvent.change(screen.getByPlaceholderText("gpt-4o-mini"), { target: { value: "gpt-4o" } });
    fireEvent.submit(screen.getByPlaceholderText("gpt-4o-mini").closest("form")!);
    await waitFor(() => expect(selectModelMock).toHaveBeenCalledWith(1, "gpt-4o"));
    await waitFor(() => expect(screen.getByText("選択中のモデル:").parentElement).toHaveTextContent(/gpt-4o$/));

    await act(async () => {
      resolveStale({ selected: "gpt-4o-mini", availableModels: ["gpt-4o-mini", "gpt-4o"] });
    });

    expect(screen.getByText("選択中のモデル:").parentElement).toHaveTextContent(/gpt-4o$/);
    expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o");
  });

  it("プロバイダーの「保存しました。」は、モデルの再取得が完了してから表示する(ATが古い画面で先へ進まないように)", async () => {
    const { fireEvent, act } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByPlaceholderText("gpt-4o-mini")).toHaveValue("gpt-4o-mini"));

    let resolveRefetch!: (v: LlmModelListResponse) => void;
    fetchLlmModelsMock.mockReset();
    fetchLlmModelsMock.mockImplementationOnce(() => new Promise((r) => (resolveRefetch = r)));
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });
    await waitFor(() => expect(resolveRefetch).toBeDefined());
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();

    await act(async () => {
      resolveRefetch(llmModelData());
    });
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });
});
