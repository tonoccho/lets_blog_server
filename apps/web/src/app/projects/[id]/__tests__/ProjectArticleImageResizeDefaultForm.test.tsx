import { act, fireEvent, render, screen } from "@testing-library/react";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies, failWith, installBackend, jsonResponse } from "./projectCoreRuntime";
import { ProjectArticleImageResizeDefaultForm } from "../ProjectArticleImageResizeDefaultForm";

/**
 * 記事投稿時の画像リサイズ(issue #291)。自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、
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

const UPDATE = "PUT /api/projects/1/article-image-resize-default";

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

describe("ProjectArticleImageResizeDefaultForm", () => {
  it("設定済みの長編が入力欄に入り、未設定なら空になる", () => {
    installBackend({});
    const { unmount } = render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={1600} />);
    expect(screen.getByLabelText("長編(px)")).toHaveValue(1600);
    unmount();

    render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={null} />);
    expect(screen.getByLabelText("長編(px)")).toHaveValue(null);
  });

  it("長編を保存すると数値で送り、保存したことを示す", async () => {
    const fetchMock = installBackend({ [UPDATE]: () => jsonResponse({}) });
    render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={null} />);

    fireEvent.change(screen.getByLabelText("長編(px)"), { target: { value: "1600" } });
    await submit(form());

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchMock, UPDATE)).toEqual([{ defaultArticleImageLongEdgePx: 1600 }]);
  });

  it("空で保存すると、アプリ全体のデフォルトへ戻す null を送る", async () => {
    const fetchMock = installBackend({ [UPDATE]: () => jsonResponse({}) });
    render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={1600} />);

    fireEvent.change(screen.getByLabelText("長編(px)"), { target: { value: "" } });
    await submit(form());

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchMock, UPDATE)).toEqual([{ defaultArticleImageLongEdgePx: null }]);
  });

  it("往復の間は「保存中…」を示して押せなくする", async () => {
    const { promise, release } = pendingResponse();
    installBackend({ [UPDATE]: () => promise });
    render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={null} />);

    await submit(form());

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({}));
    });
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("バックエンドが拒否したら、その理由を示し、保存したとは示さない", async () => {
    installBackend({ [UPDATE]: failWith(400, "長編が範囲外です") });
    render(<ProjectArticleImageResizeDefaultForm projectId={1} defaultArticleImageLongEdgePx={null} />);

    await submit(form());

    expect(await screen.findByText("APIエラー (400): 長編が範囲外です")).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).toBeNull();
  });
});
