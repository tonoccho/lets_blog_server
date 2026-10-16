import { act, fireEvent, render, screen } from "@testing-library/react";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies, failWith, installBackend, jsonResponse } from "./projectCoreRuntime";
import { ProjectImageGenerationPromptDefaultsForm } from "../ProjectImageGenerationPromptDefaultsForm";

/**
 * 画像生成のデフォルトプロンプト(issue #293)。自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、
 * Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
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

const UPDATE = "PUT /api/projects/1/image-generation-prompt-defaults";

function pendingResponse(): { promise: Promise<Response>; release: (r: Response) => void } {
  let release: (r: Response) => void = () => {};
  const promise = new Promise<Response>((resolve) => (release = resolve));
  return { promise, release };
}

async function submit(form: HTMLElement | null) {
  await act(async () => {
    fireEvent.submit(form as HTMLFormElement);
  });
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
});

const form = () => screen.getByRole("button", { name: "保存" }).closest("form");

describe("ProjectImageGenerationPromptDefaultsForm", () => {
  it("設定済みのプロンプトが入力欄に入り、未設定なら空になる", () => {
    installBackend({});
    const { unmount } = render(<ProjectImageGenerationPromptDefaultsForm projectId={1} defaultNegativePrompt="blurry" defaultQualityPrompt="sharp" />);
    expect(screen.getByLabelText("negative prompt")).toHaveValue("blurry");
    expect(screen.getByLabelText(/画質プロンプト/)).toHaveValue("sharp");
    unmount();

    render(<ProjectImageGenerationPromptDefaultsForm projectId={1} defaultNegativePrompt={null} defaultQualityPrompt={null} />);
    expect(screen.getByLabelText("negative prompt")).toHaveValue("");
    expect(screen.getByLabelText(/画質プロンプト/)).toHaveValue("");
  });

  it("プロンプトを保存すると前後の空白を除いて送り、保存したことを示す", async () => {
    const fetchMock = installBackend({ [UPDATE]: () => jsonResponse({}) });
    render(<ProjectImageGenerationPromptDefaultsForm projectId={1} defaultNegativePrompt={null} defaultQualityPrompt={null} />);

    fireEvent.change(screen.getByLabelText("negative prompt"), { target: { value: "  low quality  " } });
    fireEvent.change(screen.getByLabelText(/画質プロンプト/), { target: { value: " masterpiece " } });
    await submit(form());

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchMock, UPDATE)).toEqual([{ defaultNegativePrompt: "low quality", defaultQualityPrompt: "masterpiece" }]);
  });

  it("往復の間は「保存中…」を示して押せなくする", async () => {
    const { promise, release } = pendingResponse();
    installBackend({ [UPDATE]: () => promise });
    render(<ProjectImageGenerationPromptDefaultsForm projectId={1} defaultNegativePrompt={null} defaultQualityPrompt={null} />);

    await submit(form());

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({}));
    });
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("バックエンドが拒否したら、その理由を示し、保存したとは示さない", async () => {
    installBackend({ [UPDATE]: failWith(400, "プロンプトが長すぎます") });
    render(<ProjectImageGenerationPromptDefaultsForm projectId={1} defaultNegativePrompt={null} defaultQualityPrompt={null} />);

    await submit(form());

    expect(await screen.findByText("APIエラー (400): プロンプトが長すぎます")).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).toBeNull();
  });
});
