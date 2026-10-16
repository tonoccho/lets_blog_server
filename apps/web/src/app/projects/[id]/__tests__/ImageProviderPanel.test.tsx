import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ImageProviderPanel } from "../ImageProviderPanel";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, bodies, calledRequests, failWith, installBackend } from "./projectCoreRuntime";

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

const LIST = "GET /api/projects/1/ai-models/image/provider";
const SELECT = "PUT /api/projects/1/ai-models/image/provider/selection";

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const fetchMock = jest.fn();
const selectMock = jest.fn();

let fetchSpy: jest.Mock;

const data = { availableProviders: ["COMFYUI", "CHATGPT"], selected: null };

describe("ImageProviderPanel", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock.mockReset().mockResolvedValue({ ...data, selected: "CHATGPT" });
    selectMock.mockReset().mockResolvedValue(data);
    fetchSpy = installBackend({ [LIST]: answer(() => fetchMock()), [SELECT]: answer(() => selectMock()) });
  });

  function change() {
    render(<ImageProviderPanel projectId={1} initialData={data} />);
    fireEvent.change(screen.getByLabelText("画像生成AI(このプロジェクトの既定)"), { target: { value: "CHATGPT" } });
  }

  it("保存に成功すると、再取得して「保存しました。」を表示する", async () => {
    change();

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchSpy, SELECT)).toEqual([{ provider: "CHATGPT" }]);
    expect(calledRequests(fetchSpy)).toContain(LIST);
  });

  it("保存に失敗すると、理由を表示し再取得しない", async () => {
    selectMock.mockResolvedValue(failWith(400, "保存できません")());
    change();

    expect(await screen.findByText("APIエラー (400): 保存できません")).toBeInTheDocument();
    expect(calledRequests(fetchSpy)).not.toContain(LIST);
  });

  it("保存後の再取得に失敗すると、成功ではなく失敗の理由を表示する", async () => {
    fetchMock.mockResolvedValue(failWith(502, "再取得に失敗(HTTP 502)")());
    change();

    expect(await screen.findByText(/HTTP 502/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("保存しました。")).not.toBeInTheDocument());
  });

  it("プロバイダーの値が未知なら、表示名の対応が無くても値そのものを選択肢に出す", () => {
    render(<ImageProviderPanel projectId={1} initialData={{ availableProviders: ["COMFYUI", "FUTURE_AI"], selected: null }} />);

    expect(screen.getByRole("option", { name: "FUTURE_AI" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "ComfyUI" })).toBeInTheDocument();
  });
});
