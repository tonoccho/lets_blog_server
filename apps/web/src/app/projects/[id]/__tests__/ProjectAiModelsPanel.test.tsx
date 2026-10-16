import { StrictMode } from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ProjectAiModelsPanel } from "../ProjectAiModelsPanel";
import type {
  LlmModelListResponse,
  LlmProviderListResponse,
  ReviewStepSettingsResponse,
  ImageProviderListResponse,
  ComfyUiCheckpointListResponse,
} from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend } from "./projectCoreRuntime";

/**
 * issue #1212: LLMタブに、レビューステップ別設定(ReviewStepSettingsPanel、#1211のAPI)を
 * 追加で読み込み・表示する。既存の LlmProviderPanel / LlmModelPanel の表示・操作は
 * 変えないため、このテストはその2つが引き続き描画されることも確認する。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

const BASE = "/api/projects/1/ai-models";
const ROUTE = {
  llmModels: `GET ${BASE}/llm/models`,
  llmModelSelect: `PUT ${BASE}/llm/models/selection`,
  llmProvider: `GET ${BASE}/llm/provider`,
  llmProviderSelect: `PUT ${BASE}/llm/provider/selection`,
  reviewSteps: `GET ${BASE}/llm/review-steps`,
  imageProvider: `GET ${BASE}/image/provider`,
  imageProviderSelect: `PUT ${BASE}/image/provider/selection`,
  checkpoints: `GET ${BASE}/comfyui/checkpoints`,
  aiConnections: "GET /api/projects/1/ai-connections",
  connections: `GET ${BASE}/connections`,
  pluginRegistrations: "GET /api/projects/1/plugin-registrations",
};

// gateway の向こうのバックエンドの代役(#1837)。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
// 自前の `../actions`・`../projectPluginRegistrationActions`・`@/lib/apiClient` は差し替えず、外部との通信(`fetch`)だけを代替する。
const fetchLlmModelsMock = jest.fn();
const selectLlmModelMock = jest.fn();
const fetchLlmProviderMock = jest.fn();
const selectLlmProviderMock = jest.fn();
const fetchReviewStepSettingsMock = jest.fn();
const fetchImageProviderMock = jest.fn();
const selectImageProviderMock = jest.fn();
const fetchComfyUiCheckpointsMock = jest.fn();
const fetchAiConnectionsMock = jest.fn();
const fetchProjectConnectionsMock = jest.fn();

let fetchSpy: jest.Mock;

/** 各テストの `beforeEach` から呼ぶ。実際の HTTP の経路ごとに、上の代役を割り当てる。 */
function installAiModelsBackend() {
  resetServerRuntime(ADMIN_SESSION);
  fetchSpy = installBackend({
    [ROUTE.llmModels]: answer(() => fetchLlmModelsMock(1)),
    [ROUTE.llmModelSelect]: answer(() => selectLlmModelMock(1)),
    [ROUTE.llmProvider]: answer(() => fetchLlmProviderMock(1)),
    [ROUTE.llmProviderSelect]: answer(() => selectLlmProviderMock(1)),
    [ROUTE.reviewSteps]: answer(() => fetchReviewStepSettingsMock(1)),
    [ROUTE.imageProvider]: answer(() => fetchImageProviderMock(1)),
    [ROUTE.imageProviderSelect]: answer(() => selectImageProviderMock(1)),
    [ROUTE.checkpoints]: answer(() => fetchComfyUiCheckpointsMock(1)),
    [ROUTE.aiConnections]: answer(() => fetchAiConnectionsMock(1)),
    [ROUTE.connections]: answer(() => fetchProjectConnectionsMock(1)),
    [ROUTE.pluginRegistrations]: answer(() => []),
  });
}

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
    installAiModelsBackend();
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

describe("ProjectAiModelsPanel には接続情報セクションを置かない(issue #1669: 設定タブへ移した)", () => {
  beforeEach(() => {
    installAiModelsBackend();
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
    expect(calledRequests(fetchSpy).filter((r) => r === ROUTE.aiConnections || r === ROUTE.connections)).toEqual([]);
  });
});

/** issue #1644: プロバイダーを切り替えると、再読み込みなしで切り替え先のモデル(選択中・入力欄・候補)へ変わる。 */
describe("ProjectAiModelsPanel のプロバイダー切り替え(issue #1644)", () => {
  const selectProviderMock = selectLlmProviderMock;

  beforeEach(() => {
    installAiModelsBackend();
    fetchLlmModelsMock.mockReset().mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    selectProviderMock.mockReset().mockResolvedValue(llmProviderData());
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue(reviewStepData());
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
      selected: "claude-3-5-haiku-20241022",
      availableModels: ["claude-3-5-haiku-20241022", "claude-opus"],
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
    selectProviderMock.mockResolvedValue(failWith(500, "失敗")());
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));
    fetchLlmModelsMock.mockClear();

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), {
      target: { value: "CLAUDE" },
    });

    await screen.findByText("APIエラー (500): 失敗");
    expect(fetchLlmModelsMock).not.toHaveBeenCalled();
  });

  it("プロバイダー切り替え後のモデル再取得の最中に保存したモデルは、遅れて届いた古い取得結果で上書きされない", async () => {
    const { fireEvent, act } = await import("@testing-library/react");
    const selectModelMock = selectLlmModelMock;
    selectModelMock.mockReset().mockResolvedValue(llmModelData());
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    let resolveStale!: (v: LlmModelListResponse) => void;
    fetchLlmModelsMock.mockReset();
    fetchLlmModelsMock.mockImplementationOnce(() => new Promise((r) => (resolveStale = r)));
    fetchLlmModelsMock.mockResolvedValue({ selected: "gpt-4o", availableModels: ["gpt-4o-mini", "gpt-4o"] });

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });
    await waitFor(() => expect(resolveStale).toBeDefined());

    fireEvent.change(screen.getByLabelText("LLMのモデル"), { target: { value: "gpt-4o" } });
    fireEvent.submit(screen.getByLabelText("LLMのモデル").closest("form")!);
    await waitFor(() => expect(bodies(fetchSpy, ROUTE.llmModelSelect)).toEqual([{ modelName: "gpt-4o" }]));
    await waitFor(() => expect(screen.getByText("選択中のモデル:")).toHaveTextContent(/選択中のモデル: gpt-4o$/));

    await act(async () => {
      resolveStale({ selected: "gpt-4o-mini", availableModels: ["gpt-4o-mini", "gpt-4o"] });
    });

    expect(screen.getByText("選択中のモデル:")).toHaveTextContent(/選択中のモデル: gpt-4o$/);
    expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o");
  });

  it("プロバイダーの「保存しました。」は、モデルの再取得が完了してから表示する(ATが古い画面で先へ進まないように)", async () => {
    const { fireEvent, act } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

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

/** issue #1715: タブのデータ取得が失敗したら「読み込み中…」のまま止めず、失敗の理由を表示する。 */
describe("ProjectAiModelsPanel の取得失敗(issue #1715)", () => {
    beforeEach(() => {
    installAiModelsBackend();
    fetchLlmModelsMock.mockReset().mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    fetchReviewStepSettingsMock.mockReset().mockResolvedValue(reviewStepData());
    fetchImageProviderMock.mockReset().mockResolvedValue({ availableProviders: ["COMFYUI", "CHATGPT"], selected: null });
    fetchComfyUiCheckpointsMock.mockReset().mockResolvedValue(comfyuiData());
    selectImageProviderMock.mockReset().mockResolvedValue({ availableProviders: ["COMFYUI", "CHATGPT"], selected: "CHATGPT" });
  });

  async function openImageTab() {
    const { fireEvent } = await import("@testing-library/react");
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByText("選択中のモデル:")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));
    return fireEvent;
  }

  it("チェックポイント一覧の取得に失敗すると、読み込み中を消して失敗の理由を表示する", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValue(failWith(500, "ComfyUIに接続できません(connection refused)")());
    await openImageTab();

    expect(await screen.findByText(/connection refused/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("チェックポイント一覧の取得に失敗しても、画像生成AIの切り替え欄は表示され、ChatGPTへの切り替えを保存できる", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValue(failWith(500, "タイムアウト")());
    const fireEvent = await openImageTab();

    const select = await screen.findByLabelText("画像生成AI(このプロジェクトの既定)");
    fetchImageProviderMock.mockResolvedValue({ availableProviders: ["COMFYUI", "CHATGPT"], selected: "CHATGPT" });
    fireEvent.change(select, { target: { value: "CHATGPT" } });

    await waitFor(() => expect(bodies(fetchSpy, ROUTE.imageProviderSelect)).toEqual([{ provider: "CHATGPT" }]));
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });

  it("画像生成AIの取得に失敗しても、チェックポイント一覧は表示され、失敗の理由も表示される", async () => {
    fetchImageProviderMock.mockResolvedValue(failWith(500, "画像生成AIの取得に失敗")());
    await openImageTab();

    expect(await screen.findByText("選択中のチェックポイント:", { exact: false })).toBeInTheDocument();
    expect(screen.getByText(/画像生成AIの取得に失敗/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("取得に失敗した後で画像生成タブを押し直すと再取得し、成功すれば通常の表示になる", async () => {
    fetchComfyUiCheckpointsMock.mockResolvedValueOnce(failWith(500, "一時的な失敗")());
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
    fetchLlmModelsMock.mockResolvedValue(failWith(500, "LLMモデル一覧の取得に失敗(HTTP 503)")());
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText(/HTTP 503/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("LLMタブの一部だけ取得に失敗しても、取得できたパネルは表示される", async () => {
    fetchReviewStepSettingsMock.mockResolvedValue(failWith(500, "レビュー設定の取得に失敗")());
    render(<ProjectAiModelsPanel projectId={1} />);

    expect(await screen.findByText(/レビュー設定の取得に失敗/)).toBeInTheDocument();
    expect(screen.getByText("選択中のモデル:")).toBeInTheDocument();
    expect(screen.queryByText("レビューステップ別のAIモデル設定")).not.toBeInTheDocument();
  });

  it("LLMタブのタブ切り替え時の取得に失敗すると失敗の理由を表示し、押し直すと再取得して通常の表示になる", async () => {
    const { fireEvent } = await import("@testing-library/react");
    fetchLlmModelsMock.mockResolvedValue(failWith(500, "初回の失敗")());
    fetchLlmProviderMock.mockResolvedValue(failWith(500, "初回の失敗")());
    fetchReviewStepSettingsMock.mockResolvedValue(failWith(500, "初回の失敗")());
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findAllByText(/初回の失敗/);

    fetchLlmModelsMock.mockResolvedValue(failWith(500, "切り替え時の失敗")());
    fireEvent.click(screen.getByRole("button", { name: "LLM" }));
    expect(await screen.findByText(/切り替え時の失敗/)).toBeInTheDocument();
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();

    fetchLlmModelsMock.mockResolvedValue(llmModelData());
    fetchLlmProviderMock.mockResolvedValue(llmProviderData());
    fetchReviewStepSettingsMock.mockResolvedValue(reviewStepData());
    fireEvent.click(screen.getByRole("button", { name: "LLM" }));

    expect(await screen.findByText("選択中のモデル:")).toBeInTheDocument();
    expect(screen.queryByText(/切り替え時の失敗/)).not.toBeInTheDocument();
  });

  it("プロバイダー切り替え後のモデル再取得に失敗しても、古い結果を捨てずエラーを表示する", async () => {
    const { fireEvent } = await import("@testing-library/react");
    selectLlmProviderMock.mockReset().mockResolvedValue(llmProviderData());
    render(<ProjectAiModelsPanel projectId={1} />);
    await waitFor(() => expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini"));

    fetchLlmModelsMock.mockResolvedValue(failWith(500, "再取得の失敗")());
    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    expect(await screen.findByText(/再取得の失敗/)).toBeInTheDocument();
    expect(screen.getByLabelText("LLMのモデル")).toHaveValue("gpt-4o-mini");
  });

  it("LLMタブの一部だけ取得に失敗した後で押し直すと、取得できていない分だけ再取得する", async () => {
    fetchLlmModelsMock.mockResolvedValueOnce(failWith(500, "モデル一覧だけ失敗")());
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findByText(/モデル一覧だけ失敗/);
    expect(await screen.findByText("レビューステップ別のAIモデル設定")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "LLM" }));

    expect(await screen.findByText("選択中のモデル:")).toBeInTheDocument();
    expect(screen.queryByText(/モデル一覧だけ失敗/)).not.toBeInTheDocument();
    expect(fetchLlmModelsMock).toHaveBeenCalledTimes(2);
    expect(fetchLlmProviderMock).toHaveBeenCalledTimes(1);
    expect(fetchReviewStepSettingsMock).toHaveBeenCalledTimes(1);
  });

  it("LLMのモデルは取得できたがプロバイダーの取得に失敗した後で押し直すと、プロバイダーだけ再取得する", async () => {
    fetchLlmProviderMock.mockResolvedValueOnce(failWith(500, "プロバイダーだけ失敗")());
    render(<ProjectAiModelsPanel projectId={1} />);
    await screen.findByText(/プロバイダーだけ失敗/);
    expect(await screen.findByText("選択中のモデル:")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "LLM" }));

    expect(await screen.findByLabelText("AIプロバイダー(このプロジェクトの既定)")).toBeInTheDocument();
    expect(screen.queryByText(/プロバイダーだけ失敗/)).not.toBeInTheDocument();
    expect(fetchLlmModelsMock).toHaveBeenCalledTimes(1);
    expect(fetchLlmProviderMock).toHaveBeenCalledTimes(2);
  });

  it("画像生成タブの一部だけ取得に失敗した後で押し直すと、取得できていない分だけ再取得する", async () => {
    fetchImageProviderMock.mockResolvedValueOnce(failWith(500, "画像生成AIだけ失敗")());
    const fireEvent = await openImageTab();
    await screen.findByText(/画像生成AIだけ失敗/);
    expect(await screen.findByText("選択中のチェックポイント:", { exact: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "画像生成" }));

    expect(await screen.findByLabelText("画像生成AI(このプロジェクトの既定)")).toBeInTheDocument();
    expect(screen.queryByText(/画像生成AIだけ失敗/)).not.toBeInTheDocument();
    expect(fetchImageProviderMock).toHaveBeenCalledTimes(2);
    expect(fetchComfyUiCheckpointsMock).toHaveBeenCalledTimes(1);
  });

  it("「接続先の上書き」タブ(issue #1808)を開くと、プロジェクト単位の上書きの欄が出る", async () => {
    render(<ProjectAiModelsPanel projectId={1} />);

    fireEvent.click(screen.getByRole("button", { name: "接続先の上書き" }));

    expect(await screen.findByRole("region", { name: "LLM の上書き" })).toBeInTheDocument();
  });
});
