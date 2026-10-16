import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ProjectPluginOverridesPanel } from "../ProjectPluginOverridesPanel";
import {
  activateProjectPluginRegistrationAction,
  clearProjectPluginRegistrationCredentialAction,
  deleteProjectPluginRegistrationAction,
  fetchProjectPluginRegistrationsAction,
  saveProjectPluginRegistrationAction,
} from "../projectPluginRegistrationActions";
import type { ProjectPluginRegistration } from "@/lib/apiClient";

jest.mock("../projectPluginRegistrationActions", () => ({
  fetchProjectPluginRegistrationsAction: jest.fn(),
  saveProjectPluginRegistrationAction: jest.fn(),
  deleteProjectPluginRegistrationAction: jest.fn(),
  activateProjectPluginRegistrationAction: jest.fn(),
  clearProjectPluginRegistrationCredentialAction: jest.fn(),
}));

const fetchMock = fetchProjectPluginRegistrationsAction as jest.MockedFunction<typeof fetchProjectPluginRegistrationsAction>;
const saveMock = saveProjectPluginRegistrationAction as jest.MockedFunction<typeof saveProjectPluginRegistrationAction>;
const deleteMock = deleteProjectPluginRegistrationAction as jest.MockedFunction<typeof deleteProjectPluginRegistrationAction>;
const activateMock = activateProjectPluginRegistrationAction as jest.MockedFunction<typeof activateProjectPluginRegistrationAction>;
const clearMock = clearProjectPluginRegistrationCredentialAction as jest.MockedFunction<typeof clearProjectPluginRegistrationCredentialAction>;

const REGISTRATIONS: ProjectPluginRegistration[] = [
  { id: 1, kind: "LLM", name: "社内のOllama", url: "http://gpu-pc:11434/v1", inUse: true, drawioUrl: null, credentialConfigured: true },
  { id: 2, kind: "LLM", name: "予備", url: "http://backup.lan:11434/v1", inUse: false, drawioUrl: null, credentialConfigured: false },
  { id: 3, kind: "DIAGRAM_RENDERER", name: "図", url: "http://gpu-pc:8085", inUse: false, drawioUrl: "https://drawio.example/", credentialConfigured: false },
];

function region(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

async function renderLoaded(registrations = REGISTRATIONS) {
  fetchMock.mockResolvedValue({ data: registrations });
  render(<ProjectPluginOverridesPanel projectId={10} />);
  await screen.findByRole("region", { name: "LLM の上書き" });
}

describe("ProjectPluginOverridesPanel(issue #1808)", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    saveMock.mockReset().mockResolvedValue({});
    deleteMock.mockReset().mockResolvedValue({});
    activateMock.mockReset().mockResolvedValue({});
    clearMock.mockReset().mockResolvedValue({});
  });

  it("LLM・画像生成・図のレンダリングの3種類の欄を出し、Penpotの欄は出さない。上書きしたらその種類は上書き側だけを見ると注記する", async () => {
    await renderLoaded([]);

    for (const name of ["LLM の上書き", "画像生成 の上書き", "図のレンダリング の上書き"]) {
      expect(region(name)).toBeInTheDocument();
    }
    expect(screen.queryByRole("region", { name: /Penpot/ })).toBeNull();
    expect(screen.getByText(/上書きした種類は、システム全体の登録を見ません/)).toBeInTheDocument();
    expect(within(region("LLM の上書き")).getByText("上書きはありません")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(10);
  });

  it("取得に失敗したら理由を出す", async () => {
    fetchMock.mockResolvedValue({ error: "メンバーが必要です" });
    render(<ProjectPluginOverridesPanel projectId={10} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("メンバーが必要です");
  });

  it("上書きを名前・URLで並べ、使用中と認証情報の「設定済み」を示す(値は出さない)", async () => {
    await renderLoaded();

    const items = within(region("LLM の上書き")).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("社内のOllama");
    expect(items[0]).toHaveTextContent("http://gpu-pc:11434/v1");
    expect(items[0]).toHaveTextContent("使用中");
    expect(items[0]).toHaveTextContent("認証情報: 設定済み");
    expect(within(items[0]).getByRole("button", { name: "認証情報を消去" })).toBeInTheDocument();
    expect(items[1]).toHaveTextContent("認証情報: 未設定");
    expect(within(items[1]).queryByRole("button", { name: "認証情報を消去" })).toBeNull();
    expect(within(items[1]).getByRole("button", { name: "使用中にする" })).toBeInTheDocument();
    expect(within(items[0]).queryByRole("button", { name: "使用中にする" })).toBeNull();
    const diagram = within(region("図のレンダリング の上書き")).getByRole("listitem");
    expect(diagram).toHaveTextContent("https://drawio.example/");
    expect(within(diagram).queryByRole("button", { name: "使用中にする" })).toBeNull();
  });

  it("追加は名前・URL・認証情報を送って再取得する。drawioUrl の欄は図のレンダリングだけにある", async () => {
    await renderLoaded([]);
    expect(within(region("LLM の上書き")).queryByLabelText("drawioUrl(任意)")).toBeNull();
    expect(within(region("図のレンダリング の上書き")).getByLabelText("drawioUrl(任意)")).toBeInTheDocument();

    const llm = region("LLM の上書き");
    fireEvent.change(within(llm).getByLabelText("名前"), { target: { value: "新規" } });
    fireEvent.change(within(llm).getByLabelText("URL"), { target: { value: "http://gpu-pc:1" } });
    fireEvent.change(within(llm).getByLabelText("認証情報(任意)"), { target: { value: "sk-1" } });
    fireEvent.click(within(llm).getByRole("button", { name: "追加" }));

    await waitFor(() => expect(saveMock).toHaveBeenCalledWith(10, {
      kind: "LLM", name: "新規", url: "http://gpu-pc:1", drawioUrl: null, credential: "sk-1",
    }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it("図のレンダリングの追加は drawioUrl を送る", async () => {
    await renderLoaded([]);
    const diagram = region("図のレンダリング の上書き");
    fireEvent.change(within(diagram).getByLabelText("名前"), { target: { value: "図" } });
    fireEvent.change(within(diagram).getByLabelText("URL"), { target: { value: "http://gpu-pc:8085" } });
    fireEvent.change(within(diagram).getByLabelText("drawioUrl(任意)"), { target: { value: "https://d.example/" } });
    fireEvent.click(within(diagram).getByRole("button", { name: "追加" }));

    await waitFor(() => expect(saveMock).toHaveBeenCalledWith(10, expect.objectContaining({
      kind: "DIAGRAM_RENDERER", drawioUrl: "https://d.example/", credential: null,
    })));
  });

  it("保存が拒否されたら、その種類の欄に理由を出し、再取得しない", async () => {
    saveMock.mockResolvedValue({ error: "プロジェクト設定の「LLM の接続先」(lbx-ollama)がサーバ内部のネットワークのため" });
    await renderLoaded([]);
    const llm = region("LLM の上書き");
    fireEvent.change(within(llm).getByLabelText("名前"), { target: { value: "拒否" } });
    fireEvent.change(within(llm).getByLabelText("URL"), { target: { value: "http://lbx-ollama:1" } });
    fireEvent.click(within(llm).getByRole("button", { name: "追加" }));

    expect(await within(llm).findByRole("alert")).toHaveTextContent("サーバ内部のネットワーク");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("編集は id つきで保存し、認証情報の欄が空なら null を送る(保存済みの値は変えない)", async () => {
    await renderLoaded();
    const llm = region("LLM の上書き");
    fireEvent.click(within(within(llm).getAllByRole("listitem")[1]).getByRole("button", { name: "編集" }));
    const form = within(llm).getByRole("form", { name: "予備 を編集" });
    fireEvent.change(within(form).getByLabelText("名前"), { target: { value: "予備(改)" } });
    fireEvent.click(within(form).getByRole("button", { name: "更新" }));

    await waitFor(() => expect(saveMock).toHaveBeenCalledWith(10, {
      id: 2, kind: "LLM", name: "予備(改)", url: "http://backup.lan:11434/v1", drawioUrl: null, credential: null,
    }));
    // 保存が済むと編集フォームは閉じる。閉じてから、キャンセルでも編集フォームを閉じられることを確かめる
    await waitFor(() => expect(within(llm).queryByRole("form", { name: "予備 を編集" })).toBeNull());
    fireEvent.click(within(within(llm).getAllByRole("listitem")[1]).getByRole("button", { name: "編集" }));
    fireEvent.click(within(llm).getByRole("button", { name: "キャンセル" }));
    expect(within(llm).queryByRole("form", { name: "予備 を編集" })).toBeNull();
  });

  it("使用中にする・削除・認証情報の消去はそれぞれのアクションを呼び、再取得する", async () => {
    await renderLoaded();
    const llm = region("LLM の上書き");
    const items = within(llm).getAllByRole("listitem");

    fireEvent.click(within(items[1]).getByRole("button", { name: "使用中にする" }));
    await waitFor(() => expect(activateMock).toHaveBeenCalledWith(10, 2));
    fireEvent.click(within(items[0]).getByRole("button", { name: "認証情報を消去" }));
    await waitFor(() => expect(clearMock).toHaveBeenCalledWith(10, 1));
    fireEvent.click(within(items[1]).getByRole("button", { name: "削除" }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(10, 2));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
  });

  it("操作が失敗したら理由を出す", async () => {
    deleteMock.mockResolvedValue({ error: "見つかりません" });
    await renderLoaded();
    const llm = region("LLM の上書き");
    fireEvent.click(within(within(llm).getAllByRole("listitem")[0]).getByRole("button", { name: "削除" }));

    expect(await within(llm).findByRole("alert")).toHaveTextContent("見つかりません");
  });
});
