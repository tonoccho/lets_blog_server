import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useSession } from "next-auth/react";
import type { CustomTag } from "@/lib/apiClient";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { ProjectCustomTagManager } from "../ProjectCustomTagManager";

/**
 * 自前のモジュール(CssSelectorPrefixForm・CustomTagGenerationForm・CustomTagGenerationResult・CustomTagManager・
 * Server Action・@/lib/session・@/lib/apiClient)は差し替えない。差し替えるのは Next.js の実行基盤・`next-auth/react`
 * (ブラウザのセッション取得)と外部との通信(fetch)だけ(#1835)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock("next-auth/react");

const base = { projectId: 7, projectName: "案件", projectSlug: "slug", cssSelectorPrefix: null };

const note: CustomTag = {
  id: 1,
  tagName: "note",
  htmlTemplate: "<div>{{content}}</div>",
  description: "注意書き",
  cssContent: ".note{}",
  tagFormat: "BLOCK",
  projectId: 7,
  createdAt: "2026-10-12T00:00:00Z",
  updatedAt: "2026-10-12T00:00:00Z",
};
const inline: CustomTag = { ...note, id: 2, tagName: "mark", description: null, cssContent: null, tagFormat: "INLINE" };

let previewBodies: Record<string, string>[];
let preview: (body: Record<string, string>) => Response;
let created: Record<string, unknown>[];
let updated: { id: string; body: Record<string, unknown> }[];
let deleted: string[];
let save: () => Response;

beforeEach(() => {
  previewBodies = [];
  created = [];
  updated = [];
  deleted = [];
  resetServerRuntime(ADMIN_SESSION);
  (useSession as jest.Mock).mockReturnValue({
    data: { user: { email: "a@example.com", role: "admin" }, expires: "2099-01-01" },
    status: "authenticated",
    update: jest.fn(),
  });
  preview = () => jsonResponse({ html: "<p>描画済み</p>", css: ".x{}" });
  save = () => jsonResponse({ id: 1 }, 201);
  installFetch({
    "POST /projects/7/custom-tags/preview": (_u, init) => {
      const body = JSON.parse(String(init.body));
      previewBodies.push(body);
      return preview(body);
    },
    "POST /api/custom-tags": (_u, init) => {
      created.push(JSON.parse(String(init.body)));
      return save();
    },
    "PUT /api/custom-tags/:id": (_u, init, [id]) => {
      updated.push({ id, body: JSON.parse(String(init.body)) });
      return save();
    },
    "DELETE /api/custom-tags/:id": (_u, _i, [id]) => {
      deleted.push(id);
      return { ok: true, status: 204, statusText: "No Content", text: async () => "", headers: { get: () => null } } as unknown as Response;
    },
  });
});

// 生成フォームにも tagName・description があるので、タグの追加・編集フォームの中だけを探す。
const field = (container: HTMLElement, name: string) =>
  container.querySelector(`#custom-tag-form [name="${name}"]`) as HTMLInputElement;
const submit = (container: HTMLElement) => fireEvent.submit(container.querySelector("#custom-tag-form") as HTMLFormElement);

describe("ProjectCustomTagManager: 一覧", () => {
  it("タグごとに、タグ名・形式のバッジ・説明・サーバーでレンダリングした表示サンプルを出す", async () => {
    render(<ProjectCustomTagManager {...base} tags={[note, inline]} />);

    expect(screen.getByText("[note]")).toBeInTheDocument();
    expect(screen.getByText("ブロック")).toBeInTheDocument();
    expect(screen.getByText("インライン")).toBeInTheDocument();
    expect(screen.getByText("注意書き")).toBeInTheDocument();

    const frame = (await screen.findByTitle("[note]の表示サンプル")) as HTMLIFrameElement;
    await waitFor(() => expect(frame.getAttribute("srcdoc")).toContain("<p>描画済み</p>"));
    expect(previewBodies).toContainEqual({
      htmlTemplate: note.htmlTemplate,
      cssContent: ".note{}",
      testContent: "サンプルテキストです。ここに本文が入ります。",
    });
    // CSS が無いタグは空文字で問い合わせる
    expect(previewBodies).toContainEqual(expect.objectContaining({ htmlTemplate: inline.htmlTemplate, cssContent: "" }));
  });

  it("表示サンプルを取得できなければ、テンプレートの HTML をそのまま示す", async () => {
    preview = () => textResponse("render failed", 500);
    render(<ProjectCustomTagManager {...base} tags={[note]} />);

    await waitFor(() => expect(screen.queryByTitle("[note]の表示サンプル")).not.toBeInTheDocument());
    expect(screen.getByText("<div>{{content}}</div>")).toBeInTheDocument();
  });

  it("表示サンプルの取得が終わる前に外れても、状態を更新しない", async () => {
    let resolve: (r: Response) => void = () => {};
    const waiting = new Promise<Response>((r) => (resolve = r));
    preview = () => waiting as unknown as Response;
    const { unmount } = render(<ProjectCustomTagManager {...base} tags={[note]} />);
    unmount();
    await act(async () => resolve(jsonResponse({ html: "", css: "" })));
  });

  it("表示サンプルの取得が終わる前に外れ、そのあと失敗しても、状態を更新しない", async () => {
    let resolve: (r: Response) => void = () => {};
    const waiting = new Promise<Response>((r) => (resolve = r));
    preview = () => waiting as unknown as Response;
    const { unmount } = render(<ProjectCustomTagManager {...base} tags={[note]} />);
    unmount();
    await act(async () => resolve(textResponse("late failure", 500)));
  });
});

describe("ProjectCustomTagManager: 追加と編集", () => {
  it("追加: 入力して保存すると作成し、保存済みと示してフォームを空に戻す", async () => {
    const { container } = render(<ProjectCustomTagManager {...base} tags={[]} />);
    expect(screen.getByRole("heading", { name: "カスタムタグを追加" })).toBeInTheDocument();

    fireEvent.change(field(container, "tagName"), { target: { value: "alert" } });
    fireEvent.change(field(container, "htmlTemplate"), { target: { value: "<b>{{content}}</b>" } });
    submit(container);

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(created).toEqual([
      { tagName: "alert", htmlTemplate: "<b>{{content}}</b>", tagFormat: "BLOCK", projectId: 7 },
    ]);
    await waitFor(() => expect(field(container, "tagName").value).toBe(""));
  });

  it("保存に失敗したら、その理由を示す。そのあと成功すれば、成功の表示に戻る", async () => {
    save = () => textResponse("タグ名が重複しています", 409);
    const { container } = render(<ProjectCustomTagManager {...base} tags={[]} />);
    fireEvent.change(field(container, "tagName"), { target: { value: "alert" } });
    fireEvent.change(field(container, "htmlTemplate"), { target: { value: "<b/>" } });
    submit(container);
    expect(await screen.findByText(/タグ名が重複しています/)).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();

    save = () => jsonResponse({ id: 1 }, 201);
    fireEvent.change(field(container, "tagName"), { target: { value: "alert2" } });
    fireEvent.change(field(container, "htmlTemplate"), { target: { value: "<b/>" } });
    submit(container);
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();

    // 成功のあとに失敗が返ると、成功の表示は消える
    save = () => textResponse("また失敗", 500);
    fireEvent.change(field(container, "tagName"), { target: { value: "alert3" } });
    fireEvent.change(field(container, "htmlTemplate"), { target: { value: "<b/>" } });
    submit(container);
    expect(await screen.findByText(/また失敗/)).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).not.toBeInTheDocument();
  });

  it("編集: 「編集」で値を入れ、更新すると同じ id を更新し、編集を終える。「新規作成に戻す」でも編集を終える", async () => {
    const { container } = render(<ProjectCustomTagManager {...base} tags={[note, inline]} />);

    fireEvent.click(screen.getAllByRole("button", { name: "編集" })[0]);
    expect(screen.getByRole("heading", { name: "カスタムタグを編集: [note]" })).toBeInTheDocument();
    expect(field(container, "tagName").value).toBe("note");
    expect(field(container, "description").value).toBe("注意書き");
    expect(screen.getByRole("button", { name: "更新" })).toBeInTheDocument();

    fireEvent.change(field(container, "description"), { target: { value: "更新後" } });
    submit(container);
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(updated).toEqual([{ id: "1", body: expect.objectContaining({ tagName: "note", description: "更新後" }) }]);
    expect(screen.getByRole("heading", { name: "カスタムタグを追加" })).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "編集" })[1]);
    expect(field(container, "tagFormat").value).toBe("INLINE");
    expect(field(container, "description").value).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "新規作成に戻す" }));
    expect(screen.getByRole("heading", { name: "カスタムタグを追加" })).toBeInTheDocument();
    expect(field(container, "tagName").value).toBe("");
  });
});

describe("ProjectCustomTagManager: 削除", () => {
  afterEach(() => jest.restoreAllMocks());

  it("確認でキャンセルすれば削除しない", () => {
    jest.spyOn(window, "confirm").mockReturnValue(false);
    render(<ProjectCustomTagManager {...base} tags={[note]} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(deleted).toEqual([]);
  });

  it("確認で承認すれば、そのタグを削除する", async () => {
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    render(<ProjectCustomTagManager {...base} tags={[note]} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(confirmSpy).toHaveBeenCalledWith("このカスタムタグを削除しますか?");
    await waitFor(() => expect(deleted).toEqual(["1"]));
  });
});
