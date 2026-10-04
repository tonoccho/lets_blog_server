import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ChatGptConnectionSection } from "../ChatGptConnectionSection";
import { clearOpenAiApiKeyAction, fetchAiConnectionsAction, setOpenAiApiKeyAction } from "../actions";
import type { AiConnection } from "@/lib/apiClient";

/**
 * issue #1506: AI・アセットタブ(LLMタブ)の ChatGPT 接続情報セクション。
 * ai-connections の OPENAI 行から状態と設定の出所を表示し、OpenAIコンソールへのリンクと
 * APIキー入力フォームで接続・解除できる。キーの値は画面に出さない。
 */
jest.mock("../actions", () => ({
  fetchAiConnectionsAction: jest.fn(),
  setOpenAiApiKeyAction: jest.fn(),
  clearOpenAiApiKeyAction: jest.fn(),
}));

const fetchMock = fetchAiConnectionsAction as jest.MockedFunction<typeof fetchAiConnectionsAction>;
const setMock = setOpenAiApiKeyAction as jest.MockedFunction<typeof setOpenAiApiKeyAction>;
const clearMock = clearOpenAiApiKeyAction as jest.MockedFunction<typeof clearOpenAiApiKeyAction>;

function rows(openai: Partial<AiConnection> = {}): AiConnection[] {
  return [
    {
      provider: "OPENAI",
      displayName: "ChatGPT",
      targetUrl: null,
      source: "NONE",
      status: "WARNING",
      detail: "APIキーが設定されていません",
      configured: false,
      ...openai,
    },
  ];
}

const connected = { source: "PROJECT", status: "NORMAL", detail: null, configured: true } as const;

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(rows());
  setMock.mockReset().mockResolvedValue({});
  clearMock.mockReset().mockResolvedValue({});
});

describe("ChatGptConnectionSection の表示", () => {
  it("未設定なら「未接続」と出所「未設定」を表示し、解除ボタンは出さない", async () => {
    render(<ChatGptConnectionSection projectId={3} />);

    expect(screen.getByText("ChatGPTの接続情報")).toBeInTheDocument();
    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(3);
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("プロジェクトのキーがあれば「接続済み」と出所「プロジェクト設定」を表示し、解除ボタンを出す", async () => {
    fetchMock.mockResolvedValue(rows(connected));
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "接続を解除" })).toBeInTheDocument();
  });

  it("システム全体のキーは存在しないので、DATABASEの行が返っても「システム設定」とは表示せず未設定・未接続として扱う(issue #1568)", async () => {
    fetchMock.mockResolvedValue(rows({ source: "DATABASE", status: "NORMAL", detail: null, configured: true }));
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(screen.queryByText("システム設定")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("解除の説明はこのプロジェクトのキーの扱いだけを述べ、システム設定のキーへ戻るとは書かない(issue #1568)", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    expect(screen.getByText(/このプロジェクトのLLM生成\(ChatGPT\)でだけ使われます/)).toBeInTheDocument();
    expect(document.body.textContent ?? "").not.toMatch(/システム設定/);
  });

  it("OPENAI行が無い応答では「未接続」のまま表示する", async () => {
    fetchMock.mockResolvedValue([]);
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("取得に失敗したらエラーを表示する(握り潰さない)", async () => {
    fetchMock.mockRejectedValue(new Error("boom"));
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("接続状態の取得に失敗しました: boom");
  });

  it("Error以外の例外も文字列として表示する", async () => {
    fetchMock.mockRejectedValue("plain");
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("plain");
  });

  it("キーを発行するリンクは OpenAI コンソールへ新規タブで開き、従量課金の説明を表示する", async () => {
    render(<ChatGptConnectionSection projectId={3} />);

    const link = screen.getByRole("link", { name: "キーを発行する" });
    expect(link).toHaveAttribute("href", "https://platform.openai.com/api-keys");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(link.getAttribute("rel")).toContain("noreferrer");
    expect(
      screen.getByText("APIキーは従量課金で、ChatGPT のサブスクリプションとは別契約です。")
    ).toBeInTheDocument();
    await screen.findByText("未接続");
  });

  it("入力欄はパスワード型である", async () => {
    render(<ChatGptConnectionSection projectId={3} />);

    expect(screen.getByLabelText("OpenAI APIキー")).toHaveAttribute("type", "password");
    await screen.findByText("未接続");
  });
});

describe("ChatGptConnectionSection の接続・解除", () => {
  it("キーを入力して接続すると保存し、表示が接続済み・プロジェクト設定に変わり、キーの値は画面に残らない", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    fetchMock.mockResolvedValue(rows(connected));

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "  sk-secret  " } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(setMock).toHaveBeenCalledWith(3, "sk-secret");
    expect(screen.getByLabelText("OpenAI APIキー")).toHaveValue("");
    expect(document.body.innerHTML).not.toContain("sk-secret");
  });

  it("空のまま接続するとエラーを表示し、何も保存しない", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    fireEvent.click(screen.getByRole("button", { name: "接続" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("APIキーを入力してください。");

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(setMock).not.toHaveBeenCalled();
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("保存に失敗したらエラー内容を表示し、入力値を残し、状態は変えない", async () => {
    setMock.mockResolvedValue({ error: "APIエラー (400): bad" });
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (400): bad");
    expect(screen.getByLabelText("OpenAI APIキー")).toHaveValue("sk-x");
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("接続を解除すると削除し、表示が未接続・未設定に戻る(システム設定へは戻らない)", async () => {
    fetchMock.mockResolvedValue(rows(connected));
    render(<ChatGptConnectionSection projectId={3} />);
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
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("プロジェクト設定");

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (500): x");
    await waitFor(() => expect(screen.getByRole("button", { name: "接続を解除" })).toBeEnabled());
  });

  it("保存後の状態の再取得に失敗したらエラーを表示する", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    fetchMock.mockRejectedValue(new Error("later"));

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("later");
  });
});
