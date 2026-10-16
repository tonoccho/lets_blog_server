import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ChatGptConnectionSection } from "../ChatGptConnectionSection";
import type { AiConnection } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend, noContent } from "./projectCoreRuntime";

/**
 * issue #1506: AI・アセットタブ(LLMタブ)の ChatGPT 接続情報セクション。
 * ai-connections の OPENAI 行から状態と設定の出所を表示し、OpenAIコンソールへのリンクと
 * APIキー入力フォームで接続・解除できる。キーの値は画面に出さない。
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
const KEY = "/api/projects/3/api-keys/openai-api-key";

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const connectionsMock = jest.fn();
const setMock = jest.fn();
const clearMock = jest.fn();

let fetchSpy: jest.Mock;

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
  resetServerRuntime(ADMIN_SESSION);
  connectionsMock.mockReset().mockResolvedValue(rows());
  setMock.mockReset().mockResolvedValue(noContent());
  clearMock.mockReset().mockResolvedValue(noContent());
  fetchSpy = installBackend({
    [CONNECTIONS]: answer(() => connectionsMock()),
    [`PUT ${KEY}`]: answer(() => setMock()),
    [`DELETE ${KEY}`]: answer(() => clearMock()),
  });
});

describe("ChatGptConnectionSection の表示", () => {
  it("未設定なら「未接続」と出所「未設定」を表示し、解除ボタンは出さない", async () => {
    render(<ChatGptConnectionSection projectId={3} />);

    expect(screen.getByText("ChatGPTの接続情報")).toBeInTheDocument();
    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(calledRequests(fetchSpy)).toContain(CONNECTIONS);
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("プロジェクトのキーがあれば「接続済み」と出所「プロジェクト設定」を表示し、解除ボタンを出す", async () => {
    connectionsMock.mockResolvedValue(rows(connected));
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "接続を解除" })).toBeInTheDocument();
  });

  it("システム全体のキーは存在しないので、DATABASEの行が返っても「システム設定」とは表示せず未設定・未接続として扱う(issue #1568)", async () => {
    connectionsMock.mockResolvedValue(rows({ source: "DATABASE", status: "NORMAL", detail: null, configured: true }));
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
    connectionsMock.mockResolvedValue([]);
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("取得に失敗したらエラーを表示する(握り潰さない)", async () => {
    connectionsMock.mockRejectedValue(new Error("boom"));
    render(<ChatGptConnectionSection projectId={3} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("接続状態の取得に失敗しました: APIエラー (500): boom");
  });

  it("Error以外の例外も文字列として表示する", async () => {
    connectionsMock.mockRejectedValue("plain");
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
    connectionsMock.mockResolvedValue(rows(connected));

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "  sk-secret  " } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByText("接続済み")).toBeInTheDocument();
    expect(screen.getByText("プロジェクト設定")).toBeInTheDocument();
    expect(bodies(fetchSpy, `PUT ${KEY}`)).toEqual([{ apiKey: "sk-secret" }]);
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

    expect(bodies(fetchSpy, `PUT ${KEY}`)).toEqual([]);
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("保存に失敗したらエラー内容を表示し、入力値を残し、状態は変えない", async () => {
    setMock.mockResolvedValue(failWith(400, "bad")());
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (400): bad");
    expect(screen.getByLabelText("OpenAI APIキー")).toHaveValue("sk-x");
    expect(screen.getByText("未接続")).toBeInTheDocument();
  });

  it("接続を解除すると削除し、表示が未接続・未設定に戻る(システム設定へは戻らない)", async () => {
    connectionsMock.mockResolvedValue(rows(connected));
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("プロジェクト設定");
    connectionsMock.mockResolvedValue(rows());

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
    expect(screen.queryByText("システム設定")).not.toBeInTheDocument();
    expect(calledRequests(fetchSpy)).toContain(`DELETE ${KEY}`);
    expect(screen.queryByRole("button", { name: "接続を解除" })).not.toBeInTheDocument();
  });

  it("解除に失敗したらエラーを表示し、解除ボタンは残る", async () => {
    connectionsMock.mockResolvedValue(rows(connected));
    clearMock.mockResolvedValue(failWith(500, "x")());
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("プロジェクト設定");

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("APIエラー (500): x");
    await waitFor(() => expect(screen.getByRole("button", { name: "接続を解除" })).toBeEnabled());
  });

  it("保存後の状態の再取得に失敗したらエラーを表示する", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    connectionsMock.mockRejectedValue(new Error("later"));

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("later");
  });
});

describe("ChatGptConnectionSection の再取得とアンマウント", () => {
  /** 応答を保留して、あとから返せる状態にする。 */
  function deferred<T>() {
    let resolve: (value: T) => void = () => {};
    const promise = new Promise<T>((r) => (resolve = r));
    return { promise, resolve };
  }

  it("保存後の再取得で OPENAI の行が無い応答なら、未接続・未設定に戻る", async () => {
    connectionsMock.mockResolvedValue(rows(connected));
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("接続済み");
    connectionsMock.mockResolvedValue([]);

    fireEvent.click(screen.getByRole("button", { name: "接続を解除" }));

    expect(await screen.findByText("接続を解除しました。")).toBeInTheDocument();
    expect(screen.getByText("未接続")).toBeInTheDocument();
    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("保存後の再取得が Error でない値で失敗しても、その文字列を表示する", async () => {
    render(<ChatGptConnectionSection projectId={3} />);
    await screen.findByText("未接続");
    connectionsMock.mockRejectedValue("plain-later");

    fireEvent.change(screen.getByLabelText("OpenAI APIキー"), { target: { value: "sk-x" } });
    fireEvent.click(screen.getByRole("button", { name: "接続" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("接続状態の取得に失敗しました: plain-later");
  });

  it("状態の取得が終わる前にアンマウントされたら、あとから応答が返っても何も描かない", async () => {
    const pending = deferred<AiConnection[]>();
    connectionsMock.mockReturnValue(pending.promise);
    const { container, unmount } = render(<ChatGptConnectionSection projectId={3} />);
    await waitFor(() => expect(calledRequests(fetchSpy)).toContain(CONNECTIONS));

    unmount();
    pending.resolve(rows());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
  });

  it("状態の取得に失敗する前にアンマウントされたら、あとからエラーが返っても何も描かない", async () => {
    const pending = deferred<Response>();
    connectionsMock.mockReturnValue(pending.promise);
    const { container, unmount } = render(<ChatGptConnectionSection projectId={3} />);
    await waitFor(() => expect(calledRequests(fetchSpy)).toContain(CONNECTIONS));

    unmount();
    pending.resolve(failWith(500, "boom")());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
  });
});
