import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { LlmModelPanel } from "../LlmModelPanel";
import type { LlmModelListResponse } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend } from "./projectCoreRuntime";

/**
 * issue #1051: フォームの<form>にmethod="post"を明示した(JS無効時のネイティブGET
 * フォールバックでモデル名がURLへ漏れるのを防ぐ)。それ以外の挙動は変えていないため、
 * このテストは変更点(method="post")の確認と、保存成功/失敗の主要な分岐を最小限で押さえる。
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

const LIST = "GET /api/projects/1/ai-models/llm/models";
const SELECT = "PUT /api/projects/1/ai-models/llm/models/selection";

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const fetchMock = jest.fn();
const selectMock = jest.fn();

let fetchSpy: jest.Mock;

function installLlmBackend() {
  resetServerRuntime(ADMIN_SESSION);
  fetchMock.mockReset();
  selectMock.mockReset();
  fetchSpy = installBackend({ [LIST]: answer(() => fetchMock()), [SELECT]: answer(() => selectMock()) });
}

function initialData(overrides: Partial<LlmModelListResponse> = {}): LlmModelListResponse {
  return {
    selected: "gpt-4o-mini",
    availableModels: ["gpt-4o-mini", "gpt-4o"],
    ...overrides,
  };
}

describe("LlmModelPanel", () => {
  beforeEach(installLlmBackend);

  it("フォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    const form = container.querySelector("form");
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("モデル欄はドロップダウンで、選択肢は取得した一覧と一致し、保存済みのモデルが選択されている(issue #1674)", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    const select = screen.getByLabelText("LLMのモデル");
    expect(select.tagName).toBe("SELECT");
    const options = within(select).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["gpt-4o-mini", "gpt-4o"]);
    expect(select).toHaveValue("gpt-4o-mini");
  });

  it("テキスト入力欄と候補ボタンは無い(手入力の逃げ道を残さない、issue #1674)", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    expect(screen.queryByPlaceholderText("gpt-4o-mini")).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: "gpt-4o" })).toBeNull();
  });

  it("選択中のモデルが一覧に無くても、選択肢に残り選択された状態になる(issue #1674)", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData({ selected: "legacy-model" })} />);

    const select = screen.getByLabelText("LLMのモデル");
    const options = within(select).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["gpt-4o-mini", "gpt-4o", "legacy-model"]);
    expect(select).toHaveValue("legacy-model");
  });

  it("一覧に同じモデルが重複していても選択肢は1つだけ", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData({ availableModels: ["a", "a", "b"], selected: "a" })} />);

    const options = within(screen.getByLabelText("LLMのモデル")).getAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["a", "b"]);
  });

  it("取得に成功した一覧には、代替リストである旨を表示しない", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData({ fallback: false })} />);

    expect(screen.queryByText(/取得できなかった/)).toBeNull();
  });

  it("fallbackのときは、一覧を取得できなかった旨を表示し、現在のモデルは選択されたまま(issue #1674)", () => {
    render(<LlmModelPanel projectId={1} initialData={initialData({ fallback: true, availableModels: ["sys-a"], selected: "gpt-4o" })} />);

    expect(
      screen.getByText("モデル一覧をプロバイダーから取得できなかったため、システム設定のモデル一覧を表示しています。")
    ).toBeInTheDocument();
    const select = screen.getByLabelText("LLMのモデル");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["sys-a", "gpt-4o"]);
    expect(select).toHaveValue("gpt-4o");
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("選択中のモデルが空のまま送信しても保存アクションを呼ばない", () => {
    const { container } = render(<LlmModelPanel projectId={1} initialData={initialData({ selected: "", availableModels: [] })} />);

    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    expect(bodies(fetchSpy, SELECT)).toEqual([]);
  });

  it("保存に失敗するとエラーを表示する", async () => {
    selectMock.mockResolvedValue(failWith(400, "モデル名が不正です")());
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (400): モデル名が不正です")).toBeInTheDocument();
    });
  });

  it("保存に成功すると成功メッセージを表示し、一覧を再取得する", async () => {
    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(initialData({ selected: "gpt-4o" }));
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(screen.getByText("保存しました。")).toBeInTheDocument();
    });
    expect(calledRequests(fetchSpy)).toContain(LIST);
  });

  it("保存後の一覧の再取得に失敗すると、失敗の理由を表示する(issue #1715)", async () => {
    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(failWith(502, "再取得の失敗")());
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText(/再取得の失敗/)).toBeInTheDocument();
  });

  it("ドロップダウンで選んだモデルを保存する(issue #1674)", async () => {
    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(initialData({ selected: "gpt-4o" }));
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.change(screen.getByLabelText("LLMのモデル"), { target: { value: "gpt-4o" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(bodies(fetchSpy, SELECT)).toEqual([{ modelName: "gpt-4o" }]));
  });

  it("保存に成功すると onSaved を呼び、失敗したときは呼ばない(issue #1644)", async () => {
    const onSaved = jest.fn();
    selectMock.mockResolvedValue(failWith(400, "不正")());
    const { unmount } = render(<LlmModelPanel projectId={1} initialData={initialData()} onSaved={onSaved} />);
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByText("APIエラー (400): 不正");
    expect(onSaved).not.toHaveBeenCalled();
    unmount();

    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(initialData());
    render(<LlmModelPanel projectId={1} initialData={initialData()} onSaved={onSaved} />);
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await screen.findByText("保存しました。");
    expect(onSaved).toHaveBeenCalledTimes(1);
  });

  it("保存後の再取得でfallbackが分かれば、その旨を表示する", async () => {
    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(initialData({ fallback: true }));
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(screen.getByText(/取得できなかったため/)).toBeInTheDocument());
  });
});

/** issue #1414(#1413の横展開): マウント前は押せない(LlmModelPanel.mountGate.test.tsx)。マウント後は押せて保存できる。 */
describe("LlmModelPanel(マウント後、issue #1414)", () => {
  it("マウント後は保存ボタンが有効で、押すと保存アクションを呼ぶ", async () => {
    installLlmBackend();
    selectMock.mockResolvedValue(initialData());
    fetchMock.mockResolvedValue(initialData());
    render(<LlmModelPanel projectId={1} initialData={initialData()} />);

    const button = screen.getByRole("button", { name: "保存" });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    await waitFor(() => expect(bodies(fetchSpy, SELECT)).toEqual([{ modelName: "gpt-4o-mini" }]));
  });
});
