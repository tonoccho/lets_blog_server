import { render, screen, waitFor } from "@testing-library/react";
import { ProjectAiModelsPanel } from "../ProjectAiModelsPanel";
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
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
});
