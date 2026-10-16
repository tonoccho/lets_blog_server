import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LlmProviderPanel } from "../LlmProviderPanel";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend } from "./projectCoreRuntime";

/** issue #1644: プロバイダーの保存に成功したときだけ onChanged で親へ知らせる(モデル表示を切り替え先へ更新するため)。 */
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

const LIST = "GET /api/projects/1/ai-models/llm/provider";
const SELECT = "PUT /api/projects/1/ai-models/llm/provider/selection";

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const fetchMock = jest.fn();
const selectMock = jest.fn();

let fetchSpy: jest.Mock;

const data = { availableProviders: ["OLLAMA", "OPENAI", "CLAUDE"], selected: null };

describe("LlmProviderPanel", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock.mockReset().mockResolvedValue({ ...data, selected: "CLAUDE" });
    selectMock.mockReset();
    fetchSpy = installBackend({ [LIST]: answer(() => fetchMock()), [SELECT]: answer(() => selectMock()) });
  });

  it("保存に成功すると onChanged を呼ぶ", async () => {
    selectMock.mockResolvedValue(data);
    const onChanged = jest.fn();
    render(<LlmProviderPanel projectId={1} initialData={data} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(bodies(fetchSpy, SELECT)).toEqual([{ provider: "CLAUDE" }]);
  });

  it("保存に失敗すると onChanged を呼ばずエラーを表示する", async () => {
    selectMock.mockResolvedValue(failWith(400, "失敗")());
    const onChanged = jest.fn();
    render(<LlmProviderPanel projectId={1} initialData={data} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "OPENAI" } });

    await screen.findByText("APIエラー (400): 失敗");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("保存後の再取得に失敗すると、onChanged を呼ばず失敗の理由を表示する(issue #1715)", async () => {
    selectMock.mockResolvedValue(data);
    fetchMock.mockResolvedValue(failWith(502, "再取得の失敗")());
    const onChanged = jest.fn();
    render(<LlmProviderPanel projectId={1} initialData={data} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    expect(await screen.findByText(/再取得の失敗/)).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("onChanged が無くても保存できる", async () => {
    selectMock.mockResolvedValue(data);
    render(<LlmProviderPanel projectId={1} initialData={data} />);

    fireEvent.change(screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)"), { target: { value: "CLAUDE" } });

    await screen.findByText("保存しました。");
  });

  it("プロバイダーの値が未知なら、表示名の対応が無くても値そのものを選択肢に出す", async () => {
    const unknown = { availableProviders: ["OLLAMA", "FUTURE_AI"], selected: null };
    render(<LlmProviderPanel projectId={1} initialData={unknown} />);

    expect(screen.getByRole("option", { name: "FUTURE_AI" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Ollama" })).toBeInTheDocument();
    expect(calledRequests(fetchSpy)).toEqual([]);
  });

  it("上書き値がある状態では、その値が選択され、空にすると上書きの解除として null を送る", async () => {
    selectMock.mockResolvedValue(data);
    render(<LlmProviderPanel projectId={1} initialData={{ ...data, selected: "OPENAI" }} />);
    const select = screen.getByLabelText("AIプロバイダー(このプロジェクトの既定)");
    expect(select).toHaveValue("OPENAI");

    fireEvent.change(select, { target: { value: "" } });

    await screen.findByText("保存しました。");
    expect(bodies(fetchSpy, SELECT)).toEqual([{ provider: null }]);
  });
});
