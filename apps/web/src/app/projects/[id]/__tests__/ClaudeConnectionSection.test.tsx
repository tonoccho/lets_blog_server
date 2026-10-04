import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ClaudeConnectionSection } from "../ClaudeConnectionSection";
import { clearClaudeApiKeyAction, fetchAiConnectionsAction, setClaudeApiKeyAction } from "../actions";
import type { AiConnection } from "@/lib/apiClient";

/**
 * issue #1507: AI・アセットタブ(LLMタブ)の Claude 接続情報セクション。
 * ai-connections の CLAUDE 行から状態と設定の出所を表示し、Anthropicコンソールへのリンクと
 * APIキー入力フォームで接続・解除できる。キーの値は画面に出さない。
 */
jest.mock("../actions", () => ({
  fetchAiConnectionsAction: jest.fn(),
  setClaudeApiKeyAction: jest.fn(),
  clearClaudeApiKeyAction: jest.fn(),
}));

const fetchMock = fetchAiConnectionsAction as jest.MockedFunction<typeof fetchAiConnectionsAction>;
const setMock = setClaudeApiKeyAction as jest.MockedFunction<typeof setClaudeApiKeyAction>;
const clearMock = clearClaudeApiKeyAction as jest.MockedFunction<typeof clearClaudeApiKeyAction>;

function rows(claude: Partial<AiConnection> = {}): AiConnection[] {
  return [
    {
      provider: "CLAUDE",
      displayName: "Claude",
      targetUrl: null,
      source: "NONE",
      status: "WARNING",
      detail: "APIキーが設定されていません",
      configured: false,
      ...claude,
    },
  ];
}

const connected = { source: "PROJECT", status: "NORMAL", detail: null, configured: true } as const;

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(rows());
  setMock.mockReset().mockResolvedValue({});
  clearMock.mockReset().mockResolvedValue({});
});

describe("ClaudeConnectionSection の表示", () => {
  it("未設定なら「未接続」と出所「未設定」を表示し、解除ボタンは出さない", async () => {
    render(<ClaudeConnectionSection projectId={3} />);

    expect(screen.getByText("Claudeの接続情報")).toBeInTheDocument();
    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(3);
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("プロジェクトのキーがあれば「接続済み」と出所「プロジェクト設定」を表示し、解除ボタンを出す", async () => {
    fetchMock.mockResolvedValue(rows(connected));
    render(<ClaudeConnectionSection projectId={3} />);

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "接続を解除" })).toBeInTheDocument();
  });

  it("システム全体のキーは存在しないので、DATABASEの行が返っても「システム設定」とは表示せず未設定・未接続として扱う(issue #1568)", async () => {
    fetchMock.mockResolvedValue(rows({ source: "DATABASE", status: "NORMAL", detail: null, configured: true }));
    render(<ClaudeConnectionSection projectId={3} />);

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(screen.queryByText("システム設定")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("解除の説明はこのプロジェクトのキーの扱いだけを述べ、システム設定のキーへ戻るとは書かない(issue #1568)", async () => {
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    expect(screen.getByText(/このプロジェクトのLLM生成\(Claude\)でだけ使われます/)).toBeInTheDocument();
    expect(document.body.textContent ?? "").not.toMatch(/システム設定/);
  });

  it("CLAUDE行が無い応答では「未接続」のまま表示する", async () => {
    fetchMock.mockResolvedValue([]);
    render(<ClaudeConnectionSection projectId={3} />);

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("取得に失敗したらエラーを表示する(握り潰さない)", async () => {
    fetchMock.mockRejectedValue(new Error("boom"));
    render(<ClaudeConnectionSection projectId={3} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("接続状態の取得に失敗しました: boom");
  });

  it("Error以外の例外も文字列として表示する", async () => {
    fetchMock.mockRejectedValue("plain");
    render(<ClaudeConnectionSection projectId={3} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("plain");
  });

  it("キーを発行するリンクは Anthropic コンソールへ新規タブで開き、従量課金の説明を表示する", async () => {
    render(<ClaudeConnectionSection projectId={3} />);

    const link = screen.getByRole("link", { name: "キーを発行する" });
    expect(link).toHaveAttribute("href", "https://platform.claude.com/settings/keys");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(link.getAttribute("rel")).toContain("noreferrer");
    expect(
      screen.getByText("APIキーは従量課金で、Claude のサブスクリプションとは別契約です。")
    ).toBeInTheDocument();
    await screen.findByText("未接続");
  });

  it("入力欄はパスワード型である", async () => {
    render(<ClaudeConnectionSection projectId={3} />);

    expect(screen.getByLabelText("Anthropic APIキー")).toHaveAttribute("type", "password");
    await screen.findByText("未接続");
  });
});

describe("ClaudeConnectionSection の接続・解除", () => {
  it("キーを入力して接続すると保存し、表示が接続済み・プロジェクト設定に変わり、キーの値は画面に残らない", async () => {
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    fetchMock.mockResolvedValue(rows(connected));

    fireEvent.change(screen.getByLabelText("Anthropic APIキー"), { target: { value: "  sk-secret  " } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(setMock).toHaveBeenCalledWith(3, "sk-secret");
    expect(screen.getByLabelText("Anthropic APIキー")).toHaveValue("");
    expect(document.body.innerHTML).not.toContain("sk-secret");
  });

  it("空のまま接続するとエラーを表示し、何も保存しない", async () => {
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    fireEvent.click(screen.getByRole("button", { name: "接続" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("APIキーを入力してください。");

    fireEvent.change(screen.getByLabelText("Anthropic APIキー"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(setMock).not.toHaveBeenCalled();
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("保存に失敗したらエラー内容を表示し、入力値を残し、状態は変えない", async () => {
    setMock.mockResolvedValue({ error: "APIエラー (400): bad" });
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    fireEvent.change(screen.getByLabelText("Anthropic APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (400): bad");
    expect(screen.getByLabelText("Anthropic APIキー")).toHaveValue("sk-x");
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("接続を解除すると削除し、表示が未接続・未設定に戻る(システム設定へは戻らない)", async () => {
    fetchMock.mockResolvedValue(rows(connected));
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("プロジェクト設定");
    fetchMock.mockResolvedValue(rows());

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(screen.queryByText("システム設定")).not.toBeInTheDocument();
    expect(clearMock).toHaveBeenCalledWith(3);
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("解除に失敗したらエラーを表示し、解除ボタンは残る", async () => {
    fetchMock.mockResolvedValue(rows(connected));
    clearMock.mockResolvedValue({ error: "APIエラー (500): x" });
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("プロジェクト設定");

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (500): x");
    await waitFor(() => expect(screen.getByRole("button", { name: "接続を解除" })).toBeEnabled());
  });

  it("保存後の状態の再取得に失敗したらエラーを表示する", async () => {
    render(<ClaudeConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    fetchMock.mockRejectedValue(new Error("later"));

    fireEvent.change(screen.getByLabelText("Anthropic APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("later");
  });
});
