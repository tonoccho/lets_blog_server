import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AiConnectionSection } from "../AiConnectionSection";
import {
  fetchAiConnectionsAction,
  fetchProjectConnectionsAction,
  updateProjectConnectionAction,
} from "../actions";
import type { AiConnection, ProjectConnectionsResponse } from "@/lib/apiClient";

/**
 * issue #1504: AI・アセットタブの Ollama / ComfyUI 接続情報セクション。
 * 接続先URL・設定の出所・利用可否(不可なら理由)を表示し、プロジェクト単位の接続先を保存できる。
 * 取得は表示時にクライアント側で行い(疎通確認は遅いので接続先URLとは別に取得する)、失敗は握り潰さない。
 */
jest.mock("../actions", () => ({
  fetchAiConnectionsAction: jest.fn(),
  fetchProjectConnectionsAction: jest.fn(),
  updateProjectConnectionAction: jest.fn(),
}));

const fetchConnectionsMock = fetchAiConnectionsAction as jest.MockedFunction<typeof fetchAiConnectionsAction>;
const fetchProjectMock = fetchProjectConnectionsAction as jest.MockedFunction<typeof fetchProjectConnectionsAction>;
const updateMock = updateProjectConnectionAction as jest.MockedFunction<typeof updateProjectConnectionAction>;

function project(overrides: Partial<ProjectConnectionsResponse> = {}): ProjectConnectionsResponse {
  return {
    ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "ENVIRONMENT" },
    comfyui: { overrideBaseUrl: null, baseUrl: "http://comfy.default:8188", source: "DATABASE" },
    ...overrides,
  };
}

function connections(overrides: Partial<AiConnection>[] = []): AiConnection[] {
  const base: AiConnection[] = [
    {
      provider: "OLLAMA",
      displayName: "Ollama",
      targetUrl: "http://ollama.default:11434/v1/models",
      source: "ENVIRONMENT",
      status: "NORMAL",
      detail: null,
      configured: true,
    },
    {
      provider: "COMFYUI",
      displayName: "ComfyUI",
      targetUrl: "http://comfy.default:8188/system_stats",
      source: "DATABASE",
      status: "NORMAL",
      detail: null,
      configured: true,
    },
  ];
  return base.map((c, i) => ({ ...c, ...(overrides[i] ?? {}) }));
}

beforeEach(() => {
  fetchConnectionsMock.mockReset().mockResolvedValue(connections());
  fetchProjectMock.mockReset().mockResolvedValue(project());
  updateMock.mockReset();
});

describe("AiConnectionSection の表示", () => {
  it("Ollamaの接続先URL・設定の出所(環境変数既定)・利用可否(利用可能)を表示する", async () => {
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(screen.getByText("Ollamaの接続情報")).toBeInTheDocument();
    expect(screen.getByText("環境変数既定")).toBeInTheDocument();
    expect(await screen.findByText("利用可能")).toBeInTheDocument();
    expect(fetchProjectMock).toHaveBeenCalledWith(3);
    expect(fetchConnectionsMock).toHaveBeenCalledWith(3);
  });

  it("ComfyUIの接続先URL・設定の出所(システム設定)・利用可否を表示する", async () => {
    render(<AiConnectionSection projectId={3} provider="COMFYUI" />);

    expect(await screen.findByText("http://comfy.default:8188")).toBeInTheDocument();
    expect(screen.getByText("ComfyUIの接続情報")).toBeInTheDocument();
    expect(screen.getByText("システム設定")).toBeInTheDocument();
    expect(await screen.findByText("利用可能")).toBeInTheDocument();
  });

  it("プロジェクト設定で上書きしているときは出所がプロジェクト設定で、入力欄に上書き値が入る", async () => {
    fetchProjectMock.mockResolvedValue(
      project({ ollama: { overrideBaseUrl: "http://mine:1/v1", baseUrl: "http://mine:1/v1", source: "PROJECT" } })
    );
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)")).toHaveValue("http://mine:1/v1");
  });

  it("接続先の取得が終わる前に入力を始めても、取得結果で入力中の値を上書きしない", async () => {
    let resolveProject: (v: ProjectConnectionsResponse) => void = () => {};
    fetchProjectMock.mockReturnValue(new Promise((r) => (resolveProject = r)));
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = screen.getByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "http://typing:1" } });
    resolveProject(
      project({ ollama: { overrideBaseUrl: "http://saved:1", baseUrl: "http://saved:1", source: "PROJECT" } })
    );

    expect(await screen.findByText("http://saved:1", { selector: "dd" })).toBeInTheDocument();
    expect(input).toHaveValue("http://typing:1");
  });

  it("接続先が解決できないときは未設定と出所の未設定を表示する", async () => {
    fetchProjectMock.mockResolvedValue(
      project({ ollama: { overrideBaseUrl: null, baseUrl: null, source: "NONE" } })
    );
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findAllByText("未設定")).toHaveLength(2);
  });

  it("疎通確認が終わるまでは接続先を先に表示し、利用可否は確認中と表示する(初期表示を待たせない)", async () => {
    fetchConnectionsMock.mockReturnValue(new Promise(() => {}));
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(screen.getByText("確認中…")).toBeInTheDocument();
  });

  it("疎通できないときは利用不可であることと理由(detail)を表示する", async () => {
    fetchConnectionsMock.mockResolvedValue(
      connections([{ status: "ERROR", detail: "I/O error on GET request: Connection refused", configured: true }])
    );
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("利用不可")).toBeInTheDocument();
    expect(screen.getByText("理由: I/O error on GET request: Connection refused")).toBeInTheDocument();
  });

  it("サーバーが5xxを返すなどの警告は警告と理由を表示する", async () => {
    fetchConnectionsMock.mockResolvedValue(connections([{}, { status: "WARNING", detail: "503 Service Unavailable" }]));
    render(<AiConnectionSection projectId={3} provider="COMFYUI" />);

    expect(await screen.findByText("警告")).toBeInTheDocument();
    expect(screen.getByText("理由: 503 Service Unavailable")).toBeInTheDocument();
  });

  it("利用可能なときは理由を表示しない", async () => {
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    await screen.findByText("利用可能");
    expect(screen.queryByText(/^理由:/)).not.toBeInTheDocument();
  });

  it("利用可否の応答に該当プロバイダーが無いときは、確認できなかったことを表示する", async () => {
    fetchConnectionsMock.mockResolvedValue([]);
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("利用可否を確認できませんでした。")).toBeInTheDocument();
  });

  it("接続先の取得に失敗したときは空表示にせずエラーを表示する", async () => {
    fetchProjectMock.mockRejectedValue(new Error("APIエラー (500): boom"));
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByRole("alert")).toHaveTextContent("接続先の取得に失敗しました: APIエラー (500): boom");
  });

  it("利用可否の取得に失敗したときは握り潰さずエラーを表示する(文字列の例外でも)", async () => {
    fetchConnectionsMock.mockRejectedValue("timeout");
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("利用可否の確認に失敗しました: timeout")).toBeInTheDocument();
    expect(screen.getByText("http://ollama.default:11434/v1")).toBeInTheDocument();
  });
});

describe("AiConnectionSection の保存", () => {
  it("URLを入力して保存すると、そのプロバイダーの値だけを保存し、出所がプロジェクト設定になり利用可否を再確認する", async () => {
    const saved = project({
      ollama: { overrideBaseUrl: "http://new:1/v1", baseUrl: "http://new:1/v1", source: "PROJECT" },
    });
    updateMock.mockResolvedValue({ data: saved });
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await screen.findByText("利用可能");

    fireEvent.change(input, { target: { value: "  http://new:1/v1  " } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(updateMock).toHaveBeenCalledWith(3, "OLLAMA", "http://new:1/v1");
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByText("http://new:1/v1", { selector: "dd" })).toBeInTheDocument();
    expect(input).toHaveValue("http://new:1/v1");
    expect(fetchConnectionsMock).toHaveBeenCalledTimes(2);
  });

  it("ComfyUIの保存はCOMFYUIとして送る", async () => {
    updateMock.mockResolvedValue({ data: project() });
    render(<AiConnectionSection projectId={4} provider="COMFYUI" />);
    const input = await screen.findByLabelText("ComfyUIの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "http://c:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledWith(4, "COMFYUI", "http://c:1"));
  });

  it("空で保存すると上書きが解除され、表示が既定値の解決結果と出所に戻る", async () => {
    fetchProjectMock.mockResolvedValue(
      project({ ollama: { overrideBaseUrl: "http://mine:1/v1", baseUrl: "http://mine:1/v1", source: "PROJECT" } })
    );
    updateMock.mockResolvedValue({ data: project() });
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await waitFor(() => expect(input).toHaveValue("http://mine:1/v1"));

    fireEvent.change(input, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledWith(3, "OLLAMA", ""));
    expect(await screen.findByText("環境変数既定")).toBeInTheDocument();
    expect(screen.getByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(input).toHaveValue("");
  });

  it("保存に失敗したときはエラー内容を表示し、入力したURLを入力欄に残す", async () => {
    updateMock.mockResolvedValue({ error: "APIエラー (400): 接続先URLの形式が不正です" });
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "ftp://bad" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (400): 接続先URLの形式が不正です");
    expect(input).toHaveValue("ftp://bad");
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();
  });

  it("保存は成功したが利用可否の再確認に失敗したときも、失敗を表示する", async () => {
    updateMock.mockResolvedValue({ data: project() });
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await screen.findByText("利用可能");
    fetchConnectionsMock.mockRejectedValue(new Error("down"));

    fireEvent.change(input, { target: { value: "http://x:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("利用可否の確認に失敗しました: down")).toBeInTheDocument();
  });

  it("保存中はボタンを無効にして二重送信を防ぐ", async () => {
    let resolve: (v: { data: ProjectConnectionsResponse }) => void = () => {};
    updateMock.mockReturnValue(new Promise((r) => (resolve = r)));
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    resolve({ data: project() });
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("保存の応答に該当プロバイダーの値が無いときは入力欄を空にする", async () => {
    updateMock.mockResolvedValue({ data: {} });
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "http://x:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await screen.findByText("保存しました。");
    expect(input).toHaveValue("");
  });
});
