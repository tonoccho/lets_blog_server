import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PostComparisonTable } from "../PostComparisonTable";
import type { PostComparisonPage, PostComparisonRow, PostEnvironmentValue } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies } from "./projectCoreRuntime";

/**
 * issue #1181: 投稿の行削除の確認に、その投稿が存在する環境の数と環境名を表示する。
 * 環境の有無は行データ(available / postId / error)だけから求め、追加のAPI呼び出しはしない。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを
 * 代替する(#1836)。バックエンドへ送られた要求は `fetch` の層で確かめる。
 */
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

const COMPARISON = "GET /api/projects/1/bulk-management/posts/comparison";
const STATUSES = "GET /api/metadata/post-statuses";
const DELETE_ALL = "POST /api/projects/1/bulk-management/posts/delete-all";
const STATUS_UPDATE = "POST /api/projects/1/bulk-management/posts/status-update";

function envValue(overrides: Partial<PostEnvironmentValue> = {}): PostEnvironmentValue {
  return {
    available: true,
    error: false,
    errorMessage: null,
    postId: "10",
    title: "こんにちは",
    status: "publish",
    ...overrides,
  };
}

function row(overrides: Partial<PostComparisonRow> = {}): PostComparisonRow {
  return { slug: "hello", local: envValue(), test: envValue(), production: envValue(), ...overrides };
}

function page(overrides: Partial<PostComparisonPage> = {}): PostComparisonPage {
  return { items: [row()], page: 0, size: 20, totalCount: 1, postType: "post", ...overrides } as PostComparisonPage;
}

describe("PostComparisonTable", () => {
  let confirmSpy: jest.SpyInstance;
  let backend: jest.Mock;

  /** 既定はステータス一覧と一覧の再取得が成功する(想定外の通信は例外)。テストごとに必要な応答だけ足す。 */
  function install(routes: Record<string, () => Response> = {}) {
    backend = installFetchByPath({
      [STATUSES]: () => jsonResponse([{ value: "draft", label: "下書き" }]),
      [COMPARISON]: () => jsonResponse(page()),
      ...routes,
    });
  }

  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    install();
    confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(false);
  });
  afterEach(() => {
    confirmSpy.mockRestore();
  });

  async function renderTable(overrides: Partial<PostComparisonPage> = {}) {
    render(<PostComparisonTable projectId={1} initialPage={page(overrides)} />);
    await waitFor(() => expect(calledRequests(backend)).toContain(STATUSES));
  }

  it("削除の確認に、投稿が存在する環境の数と環境名を表示する", async () => {
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith(
      "「hello」を、存在する3つの環境(ローカル・テスト・本番)から削除します。よろしいですか?"
    );
  });

  it("一部の環境にしか無い投稿では、存在しない環境を確認に含めない", async () => {
    await renderTable({
      items: [
        row({
          local: envValue({ available: false, postId: null }),
          test: envValue({ postId: null }),
          production: envValue({ error: true }),
        }),
      ],
    });
    // 本番はエラー、ローカルは対象外、テストは未投稿 → 1環境も存在しない
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(confirmSpy).toHaveBeenCalledWith("「hello」を、存在するすべての環境から削除します。よろしいですか?");
  });

  it("2環境にある投稿は、その2環境だけを確認に表示する", async () => {
    await renderTable({ items: [row({ local: envValue({ postId: null }) })] });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    const message = confirmSpy.mock.calls[0][0] as string;
    expect(message).toContain("存在する2つの環境(テスト・本番)");
    expect(message).not.toContain("ローカル");
  });

  it("確認をキャンセルすると削除しない", async () => {
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(bodies(backend, DELETE_ALL)).toEqual([]);
  });

  it("確認を承認すると削除し、成功メッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [DELETE_ALL]: () => jsonResponse([]) });
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => expect(screen.getByText("削除しました。")).toBeInTheDocument());
    expect(bodies(backend, DELETE_ALL)).toEqual([{ slug: "hello" }]);
    expect(calledRequests(backend)).toContain(`${DELETE_ALL}?postType=post`);
  });

  it("削除に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [DELETE_ALL]: () => textResponse("削除に失敗しました", 500) });
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => expect(screen.getByText("APIエラー (500): 削除に失敗しました")).toBeInTheDocument());
  });

  it("ステータス未選択で適用するとエラーを表示し、選択後は確認して変更する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [STATUS_UPDATE]: () => jsonResponse([]) });
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "適用" }));
    expect(screen.getByText("変更後のステータスを選択してください。")).toBeInTheDocument();

    fireEvent.change(screen.getAllByRole("combobox")[1], { target: { value: "draft" } });
    fireEvent.click(screen.getByRole("button", { name: "適用" }));

    await waitFor(() => expect(screen.getByText("変更しました。")).toBeInTheDocument());
    expect(bodies(backend, STATUS_UPDATE)).toEqual([{ slug: "hello", status: "draft" }]);
    expect(calledRequests(backend)).toContain(`${STATUS_UPDATE}?postType=post`);
  });

  it("ステータスの変更を確認でキャンセルすると、変更を送らない", async () => {
    await renderTable();

    fireEvent.change(screen.getAllByRole("combobox")[1], { target: { value: "draft" } });
    await waitFor(() => expect(screen.getByRole("option", { name: "下書き" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "適用" }));

    expect(confirmSpy).toHaveBeenCalledWith("「hello」のステータスを全環境で変更します。よろしいですか?");
    expect(bodies(backend, STATUS_UPDATE)).toEqual([]);
  });

  it("ステータスの変更に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [STATUS_UPDATE]: () => textResponse("変更に失敗しました", 500) });
    await renderTable();

    await waitFor(() => expect(screen.getByRole("option", { name: "下書き" })).toBeInTheDocument());
    fireEvent.change(screen.getAllByRole("combobox")[1], { target: { value: "draft" } });
    fireEvent.click(screen.getByRole("button", { name: "適用" }));

    await waitFor(() => expect(screen.getByText("APIエラー (500): 変更に失敗しました")).toBeInTheDocument());
  });

  // issue #1382: ステータス一覧の取得失敗を無言にしない
  it("ステータス一覧の取得に失敗するとエラーメッセージを表示する", async () => {
    install({ [STATUSES]: () => textResponse("boom", 500) });
    await renderTable();

    await waitFor(() => expect(screen.getByText("ステータス一覧の取得に失敗しました。")).toBeInTheDocument());
  });

  it("ステータス一覧を取得できたときはエラーメッセージを表示せず、選択肢を描画する", async () => {
    await renderTable();

    await waitFor(() => expect(screen.getByRole("option", { name: "下書き" })).toBeInTheDocument());
    expect(screen.queryByText("ステータス一覧の取得に失敗しました。")).not.toBeInTheDocument();
  });

  it("取得エラー・対象外・未投稿・無題の環境を、それぞれ区別して表示する", async () => {
    await renderTable({
      items: [
        row({
          local: envValue({ error: true, errorMessage: "取得失敗" }),
          test: envValue({ available: false, postId: null }),
          production: envValue({ postId: null }),
        }),
        row({ slug: "untitled", local: envValue({ title: null }), test: envValue({ title: "" }), production: envValue() }),
      ],
    });

    expect(screen.getByText("エラー")).toHaveAttribute("title", "取得失敗");
    expect(screen.getByText("対象外")).toBeInTheDocument();
    expect(screen.getByText("未投稿")).toBeInTheDocument();
    expect(screen.getAllByText("(無題)")).toHaveLength(2);
  });

  it("項目が無ければ、種別に応じてポスト/ページはまだありませんと表示する", async () => {
    install({ [COMPARISON]: () => jsonResponse(page({ items: [], totalCount: 0, postType: "page" })) });
    await renderTable({ items: [], totalCount: 0 });
    expect(screen.getByText("ポストはまだありません。")).toBeInTheDocument();

    fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "page" } });

    expect(await screen.findByText("ページはまだありません。")).toBeInTheDocument();
    expect(calledRequests(backend)).toContain(`${COMPARISON}?postType=page&page=0`);
    expect(screen.getByText("0件中 0-0件を表示")).toBeInTheDocument();
  });

  it("ページ送りと更新は、現在の種別で一覧を取り直す", async () => {
    install({ [COMPARISON]: () => jsonResponse(page({ page: 1, size: 1, totalCount: 3 })) });
    await renderTable({ page: 0, size: 1, totalCount: 3 });
    expect(screen.getByRole("button", { name: "前へ" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "次へ" }));
    expect(await screen.findByText("2 / 3")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "前へ" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "更新" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "更新" }));

    await waitFor(() =>
      expect(calledRequests(backend).filter((r) => r.startsWith(COMPARISON))).toEqual([
        `${COMPARISON}?postType=post&page=1`,
        `${COMPARISON}?postType=post&page=0`,
        `${COMPARISON}?postType=post&page=1`,
      ])
    );
  });
});
