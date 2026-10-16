import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AiConnectionSection } from "../AiConnectionSection";
import type { AiConnection, ProjectConnectionsResponse } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend } from "./projectCoreRuntime";

/**
 * issue #1504: AI・アセットタブの Ollama / ComfyUI 接続情報セクション。
 * 接続先URL・設定の出所・利用可否(不可なら理由)を表示し、プロジェクト単位の接続先を保存できる。
 * 取得は表示時にクライアント側で行い(疎通確認は遅いので接続先URLとは別に取得する)、失敗は握り潰さない。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
 * `fetchConnectionsMock` などの `jest.fn()` は gateway の向こうのバックエンドの代役で、自前モジュールの差し替えではない。
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

const CONNECTIONS = "GET /api/projects/3/ai-connections";
const PROJECT_CONNECTIONS = "GET /api/projects/3/ai-models/connections";
const UPDATE_CONNECTIONS = "PUT /api/projects/3/ai-models/connections";

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const fetchConnectionsMock = jest.fn();
const fetchProjectMock = jest.fn();
const updateMock = jest.fn();

let fetchMock: jest.Mock;

function project(overrides: Partial<ProjectConnectionsResponse> = {}): ProjectConnectionsResponse {
  return {
    ollama: { overrideBaseUrl: null, baseUrl: "http://ollama.default:11434/v1", source: "DATABASE" },
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
      source: "DATABASE",
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
  resetServerRuntime(ADMIN_SESSION);
  fetchConnectionsMock.mockReset().mockResolvedValue(connections());
  fetchProjectMock.mockReset().mockResolvedValue(project());
  updateMock.mockReset();
  fetchMock = installBackend({
    [CONNECTIONS]: answer(() => fetchConnectionsMock()),
    [PROJECT_CONNECTIONS]: answer(() => fetchProjectMock()),
    [UPDATE_CONNECTIONS]: answer(() => updateMock()),
    "GET /api/projects/4/ai-connections": answer(() => fetchConnectionsMock()),
    "GET /api/projects/4/ai-models/connections": answer(() => fetchProjectMock()),
    "PUT /api/projects/4/ai-models/connections": answer(() => updateMock()),
  });
});

const connectionRequests = () => calledRequests(fetchMock).filter((r) => r === CONNECTIONS);

describe("AiConnectionSection の表示", () => {
  it("接続先の説明に環境変数は出てこない(接続先はプロジェクト設定かシステム設定の2つだけで決まる、issue #1567)", async () => {
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    await screen.findByText("http://ollama.default:11434/v1");

    expect(screen.getByText(/空で保存すると上書きを解除し、システム設定の値に戻ります/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("環境変数");
  });

  it("出所はプロジェクト設定・システム設定・未設定のどれかで表示する(環境変数既定は表示しない)", async () => {
    const cases: Array<["PROJECT" | "DATABASE" | "NONE", string]> = [
      ["PROJECT", "プロジェクト設定"],
      ["DATABASE", "システム設定"],
      ["NONE", "未設定"],
    ];
    for (const [source, text] of cases) {
      fetchProjectMock.mockResolvedValue(
        project({ comfyui: { overrideBaseUrl: null, baseUrl: source === "NONE" ? null : "http://c:8188", source } })
      );
      const { unmount } = render(<AiConnectionSection projectId={3} provider="COMFYUI" />);

      const origin = (await screen.findByText("設定の出所")).nextElementSibling;
      await waitFor(() => expect(origin).toHaveTextContent(new RegExp(`^${text}$`)));
      expect(document.body.textContent).not.toContain("環境変数既定");
      unmount();
    }
  });

  it("Ollamaの接続先URL・設定の出所(システム設定)・利用可否(利用可能)を表示する", async () => {
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(screen.getByText("Ollamaの接続情報")).toBeInTheDocument();
    expect(screen.getByText("システム設定")).toBeInTheDocument();
    expect(screen.queryByText("環境変数既定")).not.toBeInTheDocument();
    expect(await screen.findByText("利用可能")).toBeInTheDocument();
    expect(calledRequests(fetchMock)).toEqual(expect.arrayContaining([PROJECT_CONNECTIONS, CONNECTIONS]));
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
    fetchProjectMock.mockResolvedValue(failWith(500, "boom")());
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByRole("alert")).toHaveTextContent("接続先の取得に失敗しました: APIエラー (500): boom");
  });

  it("利用可否の取得に失敗したときは握り潰さずエラーを表示する(文字列の例外でも)", async () => {
    fetchConnectionsMock.mockRejectedValue("timeout"); // fetch 自体が文字列で失敗する
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByText("利用可否の確認に失敗しました: timeout")).toBeInTheDocument();
    expect(screen.getByText("http://ollama.default:11434/v1")).toBeInTheDocument();
  });
});

describe("AiConnectionSection のOllamaモデルのインストール(issue #1675)", () => {
  it("Ollamaの接続セクションにはモデル名の入力欄とインストールボタンがある", async () => {
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);

    expect(await screen.findByLabelText("インストールするOllamaモデル名")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "インストール" })).toBeInTheDocument();
  });

  it("ComfyUIの接続セクションにはモデルのインストール欄を出さない", async () => {
    render(<AiConnectionSection projectId={3} provider="COMFYUI" />);

    await screen.findByText("http://comfy.default:8188");
    expect(screen.queryByLabelText("インストールするOllamaモデル名")).not.toBeInTheDocument();
  });
});

describe("AiConnectionSection の保存", () => {
  it("URLを入力して保存すると、そのプロバイダーの値だけを保存し、出所がプロジェクト設定になり利用可否を再確認する", async () => {
    const saved = project({
      ollama: { overrideBaseUrl: "http://new:1/v1", baseUrl: "http://new:1/v1", source: "PROJECT" },
    });
    updateMock.mockResolvedValue(saved);
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await screen.findByText("利用可能");

    fireEvent.change(input, { target: { value: "  http://new:1/v1  " } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchMock, UPDATE_CONNECTIONS)).toEqual([{ ollamaBaseUrl: "http://new:1/v1" }]);
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByText("http://new:1/v1", { selector: "dd" })).toBeInTheDocument();
    expect(input).toHaveValue("http://new:1/v1");
    expect(connectionRequests()).toHaveLength(2);
  });

  it("ComfyUIの保存はCOMFYUIとして送る", async () => {
    updateMock.mockResolvedValue(project());
    render(<AiConnectionSection projectId={4} provider="COMFYUI" />);
    const input = await screen.findByLabelText("ComfyUIの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "http://c:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(bodies(fetchMock, "PUT /api/projects/4/ai-models/connections")).toEqual([{ comfyuiBaseUrl: "http://c:1" }])
    );
  });

  it("空で保存すると上書きが解除され、表示が既定値の解決結果と出所に戻る", async () => {
    fetchProjectMock.mockResolvedValue(
      project({ ollama: { overrideBaseUrl: "http://mine:1/v1", baseUrl: "http://mine:1/v1", source: "PROJECT" } })
    );
    updateMock.mockResolvedValue(project());
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await waitFor(() => expect(input).toHaveValue("http://mine:1/v1"));

    fireEvent.change(input, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(bodies(fetchMock, UPDATE_CONNECTIONS)).toEqual([{ ollamaBaseUrl: "" }]));
    expect(await screen.findByText("システム設定")).toBeInTheDocument();
    expect(screen.getByText("http://ollama.default:11434/v1")).toBeInTheDocument();
    expect(input).toHaveValue("");
  });

  it("保存に失敗したときはエラー内容を表示し、入力したURLを入力欄に残す", async () => {
    updateMock.mockResolvedValue(failWith(400, "接続先URLの形式が不正です")());
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "ftp://bad" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (400): 接続先URLの形式が不正です");
    expect(input).toHaveValue("ftp://bad");
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();
  });

  it("保存は成功したが利用可否の再確認に失敗したときも、失敗を表示する", async () => {
    updateMock.mockResolvedValue(project());
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");
    await screen.findByText("利用可能");
    fetchConnectionsMock.mockRejectedValue(new Error("down"));

    fireEvent.change(input, { target: { value: "http://x:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("利用可否の確認に失敗しました: APIエラー (500): down")).toBeInTheDocument();
  });

  it("保存中はボタンを無効にして二重送信を防ぐ", async () => {
    let resolve: (v: ProjectConnectionsResponse) => void = () => {};
    updateMock.mockReturnValue(new Promise((r) => (resolve = r)));
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    resolve(project());
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("保存の応答に該当プロバイダーの値が無いときは入力欄を空にする", async () => {
    updateMock.mockResolvedValue({});
    render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    const input = await screen.findByLabelText("Ollamaの接続先URL(このプロジェクトで上書き)");

    fireEvent.change(input, { target: { value: "http://x:1" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await screen.findByText("保存しました。");
    expect(input).toHaveValue("");
  });
});

describe("AiConnectionSection のアンマウント", () => {
  /** 応答を保留して、あとから返せる状態にする。 */
  function deferred<T>() {
    let resolve: (value: T) => void = () => {};
    const promise = new Promise<T>((r) => (resolve = r));
    return { promise, resolve };
  }

  it("接続先の取得が終わる前にアンマウントされたら、あとから応答が返っても何も描かない", async () => {
    const pending = deferred<ProjectConnectionsResponse>();
    fetchProjectMock.mockReturnValue(pending.promise);
    const { container, unmount } = render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    await waitFor(() => expect(calledRequests(fetchMock)).toContain(PROJECT_CONNECTIONS));

    unmount();
    pending.resolve(project());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
  });

  it("接続先の取得に失敗する前にアンマウントされたら、あとからエラーが返っても何も描かない", async () => {
    const pending = deferred<Response>();
    fetchProjectMock.mockReturnValue(pending.promise);
    const { container, unmount } = render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    await waitFor(() => expect(calledRequests(fetchMock)).toContain(PROJECT_CONNECTIONS));

    unmount();
    pending.resolve(failWith(500, "boom")());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
  });

  it("利用可否の確認が終わる前にアンマウントされたら、あとから結果が返っても何も描かない", async () => {
    const pending = deferred<AiConnection[]>();
    fetchConnectionsMock.mockReturnValue(pending.promise);
    const { container, unmount } = render(<AiConnectionSection projectId={3} provider="OLLAMA" />);
    await waitFor(() => expect(connectionRequests()).toHaveLength(1));

    unmount();
    pending.resolve(connections());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
  });
});
