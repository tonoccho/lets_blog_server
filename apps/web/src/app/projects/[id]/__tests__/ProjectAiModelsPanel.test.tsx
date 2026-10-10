import { StrictMode } from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import { ProjectAiModelsPanel } from "../ProjectAiModelsPanel";
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  selectLlmProviderAction,
  selectLlmModelAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  selectImageProviderAction,
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
    fetchLlmModelsMock.mockReset().mockResolvedValue({ data: llmModelData() });
    fetchLlmProviderMock.mockReset().mockResolvedValue({ data: llmProviderData() });
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue({ data: reviewStepData() });
    fetchImageProviderMock.mockReset().mockResolvedValue({ data: imageProviderData() });
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue({ data: comfyuiData() });
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

describe("ProjectAiModelsPanel には接続情報セクションを置かない(issue #1669: 設定タブへ移した)", () => {
  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue({ data: llmModelData() });
    fetchLlmProviderMock.mockReset().mockResolvedValue({ data: llmProviderData() });
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue({ data: reviewStepData() });
    fetchImageProviderMock.mockReset().mockResolvedValue({ data: imageProviderData() });
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue({ data: comfyuiData() });
    fetchAiConnectionsMock.mockReset().mockResolvedValue([]);
    fetchProjectConnectionsMock.mockReset().mockResolvedValue({
      ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
      comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    });
  });

  it("LLMタブにも画像生成タブにも、どの接続情報も表示せず接続情報の取得も行わない", async () => {
    const { fireEvent } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findByText("レビューステップ別のAIモデル設定");
    for (const title of ["Ollamaの接続情報", "ChatGPTの接続情報", "Claudeの接続情報"]) {
      expect(screen.queryByText(title)).not.toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));
    await screen.findByText("選択中のチェックポイント:", { exact: false });
    expect(screen.queryByText("ComfyUIの接続情報")).not.toBeInTheDocument();
    expect(fetchAiConnectionsMock).not.toHaveBeenCalled();
    expect(fetchProjectConnectionsMock).not.toHaveBeenCalled();
  });
});

/** issue #1644: プロバイダーを切り替えると、再読み込みなしで切り替え先のモデル(選択中・入力欄・候補)へ変わる。 */
describe("ProjectAiModelsPanel のプロバイダー切り替え(issue #1644)", () => {
  const selectProviderMock = selectLlmProviderAction as jest.MockedFunction<typeof selectLlmProviderAction>;

  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue({ data: llmModelData() });
    fetchLlmProviderMock.mockReset().mockResolvedValue({ data: llmProviderData() });
    selectProviderMock.mockReset().mockResolvedValue({});
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue({ data: reviewStepData() });
    fetchAiConnectionsMock.mockReset().mockResolvedValue([]);
    fetchProjectConnectionsMock.mockReset().mockResolvedValue({
      ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
      comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    });
  });

  it("プロバイダーを切り替えると、切り替え先のモデルが選択中・ドロップダウン・選択肢に表示される", async () => {
    const { fireEvent } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    fetchLlmModelsMock.mockResolvedValue({
      data: {
        selected: "claude-3-5-haiku-20241022",
        availableModels: ["claude-3-5-haiku-20241022", "claude-opus"],
      },
    });
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), {
      target: { value: "CLAUDE" },
    });

    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("claude-3-5-haiku-20241022"));
    expect(screen.getByText("選択中のモデル:").parentElement).toHaveTextContent("claude-3-5-haiku-20241022");
    const llmModelSelect = screen.getByLabelText("LLMのモデル");
    expect(within(llmModelSelect).getByRole("option", { name: "claude-opus" })).toBeInTheDocument();
    expect(within(llmModelSelect).queryByRole("option", { name: "gpt-4o" })).not.toBeInTheDocument();
  });

  it("プロバイダーの保存に失敗したときはモデルを再取得しない", async () => {
    const { fireEvent } = await import("@testing-library/react");
    selectProviderMock.mockResolvedValue({ error: "失敗" });
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));
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
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    let resolveStale!: (v: { data: LlmModelListResponse }) => void;
    fetchLlmModelsMock.mockReset();
    fetchLlmModelsMock.mockImplementationOnce(() => new Promise((r) => (resolveStale = r)));
    fetchLlmModelsMock.mockResolvedValue({ data: { selected: "gpt-4o", availableModels: ["gpt-4o-mini", "gpt-4o"] } });

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });
    await waitFor(() => expect(resolveStale).toBeDefined());

    fireEvent.change(screen.getByLabelText("LLMのモデル"), { target: { value: "gpt-4o" } });
    fireEvent.submit(screen.getByLabelText("LLMのモデル").closest("form")!);
    await waitFor(() => expect(selectModelMock).toHaveBeenCalledWith(1, "gpt-4o"));
    await waitFor(() => expect(screen.getByText("選択中のモデル:")).toHaveTextContent(/選択中のモデル: gpt-4o$/));

    await act(async () => {
      resolveStale({ data: { selected: "gpt-4o-mini", availableModels: ["gpt-4o-mini", "gpt-4o"] } });
    });

    expect(screen.getByText("選択中のモデル:")).toHaveTextContent(/選択中のモデル: gpt-4o$/);
    expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o");
  });

  it("プロバイダーの「保存しました。」は、モデルの再取得が完了してから表示する(ATが古い画面で先へ進まないように)", async () => {
    const { fireEvent, act } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    let resolveRefetch!: (v: { data: LlmModelListResponse }) => void;
    fetchLlmModelsMock.mockReset();
    fetchLlmModelsMock.mockImplementationOnce(() => new Promise((r) => (resolveRefetch = r)));
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });
    await waitFor(() => expect(resolveRefetch).toBeDefined());
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();

    await act(async () => {
      resolveRefetch({ data: llmModelData() });
    });
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });
});

/** issue #1715: タブのデータ取得が失敗したら「読み込み中…」のまま止めず、失敗の理由を表示する。 */
describe("ProjectAiModelsPanel の取得失敗(issue #1715)", () => {
  const selectImageProviderMock = selectImageProviderAction as jest.MockedFunction<typeof selectImageProviderAction>;

  beforeEach(() => {
    fetchLlmModelsMock.mockReset().mockResolvedValue({ data: llmModelData() });
    fetchLlmProviderMock.mockReset().mockResolvedValue({ data: llmProviderData() });
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue({ data: reviewStepData() });
    fetchImageProviderMock.mockReset().mockResolvedValue({ data: { availableProviders: ["COMFYUI", "CHATGPT"], selected: null } });
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue({ data: comfyuiData() });
    selectImageProviderMock.mockReset().mockResolvedValue({});
  });

  async function openImageTab() {
    const { fireEvent } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByText("選択中のモデル:")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));
    return fireEvent;
  }

  it("チェックポイント一覧の取得に失敗すると、読み込み中を消して失敗の理由を表示する", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValue({ error: "ComfyUIに接続できません(connection refused)" });
    await openImageTab();

    expect(await screen.findByText(/connection refused/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("チェックポイント一覧の取得に失敗しても、画像生成AIの切り替え欄は表示され、ChatGPTへの切り替えを保存できる", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValue({ error: "タイムアウト" });
    const fireEvent = await openImageTab();

    const select = await screen.findByLabelText("画像生成AI(このプロジェクトの既定)");
    fetchImageProviderMock.mockResolvedValue({ data: { availableProviders: ["COMFYUI", "CHATGPT"], selected: "CHATGPT" } });
    fireEvent.change(select, { target: { value: "CHATGPT" } });

    await waitFor(() => expect(selectImageProviderMock).toHaveBeenCalledWith(1, "CHATGPT"));
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });

  it("画像生成AIの取得に失敗しても、チェックポイント一覧は表示され、失敗の理由も表示される", async () => {
    fetchImageProviderMock.mockResolvedValue({ error: "画像生成AIの取得に失敗" });
    await openImageTab();

    expect(await screen.findByText("選択中のチェックポイント:", { exact: false })).toBeInTheDocument();
    expect(screen.getByText(/画像生成AIの取得に失敗/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("取得に失敗した後で画像生成タブを押し直すと再取得し、成功すれば通常の表示になる", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValueOnce({ error: "一時的な失敗" });
    const fireEvent = await openImageTab();
    await screen.findByText(/一時的な失敗/);

    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));

    expect(await screen.findByText("選択中のチェックポイント:", { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(/一時的な失敗/)).not.toBeInTheDocument();
    expect(fetchComfyUiCheckpointsMock).toHaveBeenCalledTimes(2);
  });

  it("両方の取得が成功済みのとき、画像生成タブを押し直しても再取得しない", async () => {
    const fireEvent = await openImageTab();
    await screen.findByText("選択中のチェックポイント:", { exact: false });

    fireEvent.click(screen.getByRole("button", { name: "LLM" }));
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));

    expect(fetchComfyUiCheckpointsMock).toHaveBeenCalledTimes(1);
    expect(fetchImageProviderMock).toHaveBeenCalledTimes(1);
  });

  it("LLMタブの初回取得に失敗すると、読み込み中を出さず失敗の理由を表示する", async () => {
    fetchLlmModelsMock.mockResolvedValue({ error: "LLMモデル一覧の取得に失敗(HTTP 503)" });
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText(/HTTP 503/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("LLMタブの一部だけ取得に失敗しても、取得できたパネルは表示される", async () => {
    fetchReviewStepSettingsMock.mockResolvedValue({ error: "レビュー設定の取得に失敗" });
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText(/レビュー設定の取得に失敗/)).toBeInTheDocument();
    expect(screen.getByText("選択中のモデル:")).toBeInTheDocument();
    expect(screen.queryByText("レビューステップ別のAIモデル設定")).not.toBeInTheDocument();
  });

  it("LLMタブのタブ切り替え時の取得に失敗すると失敗の理由を表示し、押し直すと再取得して通常の表示になる", async () => {
    const { fireEvent } = await import("@testing-library/react");
    fetchLlmModelsMock.mockResolvedValue({ error: "初回の失敗" });
    fetchLlmProviderMock.mockResolvedValue({ error: "初回の失敗" });
    fetchReviewStepSettingsMock.mockResolvedValue({ error: "初回の失敗" });
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findAllByText(/初回の失敗/);

    fetchLlmModelsMock.mockResolvedValue({ error: "切り替え時の失敗" });
    fireEvent.click(screen.getByRole("button", { name: "LLM" }));
    expect(await screen.findByText(/切り替え時の失敗/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();

    fetchLlmModelsMock.mockResolvedValue({ data: llmModelData() });
    fetchLlmProviderMock.mockResolvedValue({ data: llmProviderData() });
    fetchReviewStepSettingsMock.mockResolvedValue({ data: reviewStepData() });
    fireEvent.click(screen.getByRole("button", { name: "LLM" }));

    expect(await screen.findByText("選択中のモデル:")).toBeInTheDocument();
    expect(screen.queryByText(/切り替え時の失敗/)).not.toBeInTheDocument();
  });

  it("プロバイダー切り替え後のモデル再取得に失敗しても、古い結果を捨てずエラーを表示する", async () => {
    const { fireEvent } = await import("@testing-library/react");
    (selectLlmProviderAction as jest.MockedFunction<typeof selectLlmProviderAction>).mockReset().mockResolvedValue({});
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    fetchLlmModelsMock.mockResolvedValue({ error: "再取得の失敗" });
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    expect(await screen.findByText(/再取得の失敗/)).toBeInTheDocument();
    expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini");
  });
});
