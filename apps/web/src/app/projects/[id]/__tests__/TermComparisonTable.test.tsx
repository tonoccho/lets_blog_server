import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TermComparisonTable } from "../TermComparisonTable";
import type { TermComparisonPage, TermComparisonRow, TermEnvironmentValue } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies } from "./projectCoreRuntime";

/**
 * issue #1051: 新規追加・編集フォームの<form>にmethod="post"を明示した(JS無効時のネイティブ
 * GETフォールバックで名前・スラッグ等がURLへ漏れるのを防ぐ)。
 *
 * `scripts/check-changed-coverage.py` はファイル単位で分岐カバレッジを見るため、この変更に
 * よって「変更したファイル」として扱われる本コンポーネントの既存ロジック(環境間の同期・
 * 編集・削除・一括同期・ページング)も、この機会にあわせて可能な範囲で押さえる。
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

const COMPARISON = "GET /api/projects/1/bulk-management/categories/comparison";
const SYNC = "POST /api/projects/1/bulk-management/categories/sync";
const DELETE_ALL = "POST /api/projects/1/bulk-management/categories/delete-all";
const EDIT_SYNC = "POST /api/projects/1/bulk-management/categories/edit-sync";
const SYNC_ALL = "POST /api/projects/1/bulk-management/categories/sync-all";
const APPLY = "POST /api/projects/1/bulk-management/apply";
const TAG_COMPARISON = "GET /api/projects/1/bulk-management/tags/comparison";

function envValue(overrides: Partial<TermEnvironmentValue> = {}): TermEnvironmentValue {
  return {
    available: true,
    error: false,
    errorMessage: null,
    slug: "akismet",
    parentSlug: null,
    description: null,
    ...overrides,
  };
}

function row(overrides: Partial<TermComparisonRow> = {}): TermComparisonRow {
  return {
    name: "お知らせ",
    slug: "news",
    local: envValue(),
    test: envValue(),
    production: envValue(),
    ...overrides,
  };
}

function page(overrides: Partial<TermComparisonPage> = {}): TermComparisonPage {
  return {
    items: [row()],
    page: 0,
    size: 20,
    totalCount: 1,
    masterEnvironment: "production",
    ...overrides,
  };
}

describe("TermComparisonTable", () => {
  let confirmSpy: jest.SpyInstance;
  let backend: jest.Mock;

  /** 既定は何も起きない(想定外の通信は例外)。テストごとに必要な応答だけ足す。 */
  function install(routes: Record<string, () => Response> = {}) {
    backend = installFetchByPath({
      [COMPARISON]: () => jsonResponse(page()),
      [TAG_COMPARISON]: () => jsonResponse(page()),
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

  function renderTable(overrides: Partial<TermComparisonPage> = {}, kind: "category" | "tag" = "category") {
    return render(<TermComparisonTable projectId={1} kind={kind} initialPage={page(overrides)} />);
  }

  it("新規追加フォームはmethod=\"post\"を持つ", () => {
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));

    expect(container.querySelector("form")?.getAttribute("method")).toBe("post");
  });

  it("項目が無ければその旨を表示する", () => {
    renderTable({ items: [] });

    expect(screen.getByText("カテゴリはまだありません。")).toBeInTheDocument();
  });

  it("タグの場合はラベルが切り替わり、親カテゴリ欄が無い", () => {
    renderTable({}, "tag");

    expect(screen.getAllByText("タグ", { exact: false }).length).toBeGreaterThan(0);
    expect(screen.queryByText("親カテゴリ")).not.toBeInTheDocument();
  });

  it("取得エラーの環境は「エラー」を表示する", () => {
    renderTable({ items: [row({ local: envValue({ error: true, errorMessage: "取得失敗" }) })] });

    const errorTexts = screen.getAllByText("エラー");
    expect(errorTexts.length).toBeGreaterThan(0);
    expect(errorTexts[0]).toHaveAttribute("title", "取得失敗");
  });

  it("未対応環境は「対象外」を表示する", () => {
    renderTable({ items: [row({ local: envValue({ available: false, slug: null }) })] });

    expect(screen.getAllByText("対象外").length).toBeGreaterThan(0);
  });

  it("マスター環境に値がある行では同期ボタンを表示し、無い行では表示しない", () => {
    renderTable({
      items: [
        row({ slug: "with-master", production: envValue({ slug: "with-master" }) }),
        row({ slug: "no-master", production: envValue({ available: false, slug: null }) }),
      ],
    });

    const syncButtons = screen.getAllByRole("button", { name: "同期" });
    expect(syncButtons).toHaveLength(1);
  });

  it("同期確認をキャンセルすると同期アクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    expect(calledRequests(backend)).toEqual([]);
  });

  it("同期に成功すると成功メッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [SYNC]: () => jsonResponse([]) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    await waitFor(() => {
      expect(screen.getByText("同期しました。")).toBeInTheDocument();
    });
    expect(bodies(backend, SYNC)).toEqual([{ slug: "news" }]);
    expect(calledRequests(backend)).toEqual([SYNC, `${COMPARISON}?page=0`]);
  });

  it("同期に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [SYNC]: () => textResponse("同期に失敗しました", 500) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "同期" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 同期に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(calledRequests(backend)).toEqual([]);
  });

  it("削除の確認に、項目が存在する環境の数と環境名を表示する", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith(
      "「お知らせ」を、存在する3つの環境(ローカル・テスト・本番)から削除します。よろしいですか?"
    );
  });

  it("一部の環境にしか無い項目では、存在しない環境を確認に含めない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({
      items: [
        row({
          local: envValue({ available: false, slug: null }),
          test: envValue({ error: true, slug: null }),
          production: envValue(),
        }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    const message = confirmSpy.mock.calls[0][0] as string;
    expect(message).toContain("存在する1つの環境(本番)");
    expect(message).not.toContain("ローカル");
    expect(message).not.toContain("テスト");
  });

  it("どの環境にも存在しない行は、従来の文言で確認する", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({
      items: [
        row({
          local: envValue({ slug: null }),
          test: envValue({ available: false }),
          production: envValue({ slug: null }),
        }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith("「お知らせ」を、存在するすべての環境から削除します。よろしいですか?");
  });

  it("削除すると成功メッセージを表示し、編集中の行を閉じる", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [DELETE_ALL]: () => jsonResponse([]) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除しました。")).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "保存(全環境に反映)" })).not.toBeInTheDocument();
    expect(bodies(backend, DELETE_ALL)).toEqual([{ slug: "news" }]);
  });

  it("削除に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [DELETE_ALL]: () => textResponse("削除に失敗しました", 500) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 削除に失敗しました")).toBeInTheDocument();
    });
  });

  it("編集ボタンを再度押すと編集フォームを閉じる", () => {
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(screen.queryByRole("button", { name: "保存(全環境に反映)" })).not.toBeInTheDocument();
  });

  it("マスターに値がある行の編集を確認キャンセルすると保存アクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    expect(calledRequests(backend)).toEqual([]);
  });

  it("マスターに値が無い行の編集は新規作成の確認文言になり、保存できる", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [EDIT_SYNC]: () => jsonResponse([]) });
    renderTable({
      items: [row({ production: envValue({ available: false, slug: null }) })],
    });

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, EDIT_SYNC)).toEqual([{ targetSlug: "news", value: "お知らせ", slug: "akismet" }]);
    });
    expect(confirmSpy.mock.calls[0][0]).toContain("新規作成");
  });

  it("マスターに値が無い行の編集フォームは、ローカル→テスト→本番の順で最初にある値を初期値にする", () => {
    renderTable({
      items: [
        row({
          local: envValue({ available: false, slug: null }),
          test: envValue({ slug: "from-test", description: "テストの説明" }),
          production: envValue({ available: false, slug: null }),
        }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "編集" }));

    expect(screen.getByLabelText("スラッグ", { selector: "form input" })).toHaveValue("from-test");
    expect(screen.getByLabelText("説明(任意)")).toHaveValue("テストの説明");
  });

  it("どの環境にも値が無い行の編集フォームは、スラッグ・説明を空で開く", () => {
    renderTable({
      items: [
        row({
          local: envValue({ available: false, slug: null }),
          test: envValue({ available: false, slug: null }),
          production: envValue({ available: false, slug: null }),
        }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "編集" }));

    expect(screen.getByLabelText("スラッグ", { selector: "form input" })).toHaveValue("");
    expect(screen.getByLabelText("説明(任意)")).toHaveValue("");
  });

  it("編集の保存に失敗するとエラーを表示したままにする", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [EDIT_SYNC]: () => textResponse("保存に失敗しました", 500) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 保存に失敗しました")).toBeInTheDocument();
    });
  });

  it("一括同期確認をキャンセルするとアクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    expect(calledRequests(backend)).toEqual([]);
  });

  it("一括同期で差分が無い場合は専用メッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [SYNC_ALL]: () => jsonResponse([]) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("マスターとの差分はありませんでした。")).toBeInTheDocument();
    });
  });

  it("一括同期で操作が発生した場合は件数を表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [SYNC_ALL]: () => jsonResponse([{ slug: "a" }, { slug: "b" }]) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("2件の操作でマスターに揃えました。")).toBeInTheDocument();
    });
  });

  it("一括同期に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [SYNC_ALL]: () => textResponse("一括同期に失敗しました", 500) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "マスターに一括で揃える" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 一括同期に失敗しました")).toBeInTheDocument();
    });
  });

  it("環境更新ボタンで一覧を再取得する", async () => {
    renderTable();

    fireEvent.click(screen.getAllByRole("button", { name: /を更新$/ })[0]);

    await waitFor(() => {
      expect(calledRequests(backend)).toEqual([`${COMPARISON}?page=0`]);
    });
  });

  it("先頭かつ最終ページでは前へ・次へが無効になる", () => {
    renderTable({ page: 0, size: 20, totalCount: 1 });

    expect(screen.getByRole("button", { name: "前へ" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
  });

  it("次のページがある場合は「次へ」で取得する", async () => {
    install({ [COMPARISON]: () => jsonResponse(page({ page: 1, size: 1, totalCount: 2 })) });
    renderTable({ page: 0, size: 1, totalCount: 2 });

    fireEvent.click(screen.getByRole("button", { name: "次へ" }));

    await waitFor(() => {
      expect(calledRequests(backend)).toEqual([`${COMPARISON}?page=1`]);
    });
    expect(await screen.findByText("2 / 2")).toBeInTheDocument();
  });

  it("新規追加フォームを送信すると一覧を更新して閉じる", async () => {
    install({ [APPLY]: () => jsonResponse({}) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    fireEvent.change(screen.getAllByLabelText("名前")[0], { target: { value: "イベント" } });
    fireEvent.change(screen.getByLabelText("スラッグ"), { target: { value: "event" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, APPLY)).toEqual([
        { environment: "production", operationType: "CATEGORY_CREATE", value: "イベント", categorySlug: "event" },
      ]);
    });
    await waitFor(() => {
      expect(screen.queryByLabelText("スラッグ")).not.toBeInTheDocument();
    });
  });

  it("新規追加に失敗するとエラーを表示したままフォームを開いておく", async () => {
    install({ [APPLY]: () => textResponse("追加に失敗しました", 500) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    fireEvent.change(screen.getAllByLabelText("名前")[0], { target: { value: "イベント" } });
    fireEvent.change(screen.getByLabelText("スラッグ"), { target: { value: "event" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 追加に失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("スラッグ")).toBeInTheDocument();
  });

  it("タグの新規追加はタグの作成として送り、親カテゴリ欄は出さない", async () => {
    install({ [APPLY]: () => jsonResponse({}) });
    const { container } = renderTable({}, "tag");

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    expect(screen.queryByLabelText("親カテゴリのスラッグ(任意)")).not.toBeInTheDocument();
    fireEvent.change(screen.getAllByLabelText("名前")[0], { target: { value: "雑記" } });
    fireEvent.change(screen.getByLabelText("スラッグ"), { target: { value: "misc" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, APPLY)).toEqual([
        { environment: "production", operationType: "TAG_CREATE", value: "雑記", categorySlug: "misc" },
      ]);
    });
  });

  it("タグの編集は親カテゴリを送らず、カテゴリの編集は親カテゴリと説明を送る", async () => {
    confirmSpy.mockReturnValue(true);
    const TAG_EDIT = "POST /api/projects/1/bulk-management/tags/edit-sync";
    install({ [EDIT_SYNC]: () => jsonResponse([]), [TAG_EDIT]: () => jsonResponse([]) });
    const tag = renderTable({}, "tag");
    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);
    await waitFor(() => expect(bodies(backend, TAG_EDIT)).toEqual([{ targetSlug: "news", value: "お知らせ", slug: "akismet" }]));
    tag.unmount();

    renderTable({ items: [row({ production: envValue({ slug: "news", parentSlug: "parent", description: "説明" }) })] });
    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    fireEvent.submit(screen.getByRole("button", { name: "保存(全環境に反映)" }).closest("form") as HTMLFormElement);
    await waitFor(() =>
      expect(bodies(backend, EDIT_SYNC)).toEqual([
        { targetSlug: "news", value: "お知らせ", slug: "news", parentSlug: "parent", description: "説明" },
      ])
    );
  });

  it("マスターと異なる値のセルは赤字で強調表示する", () => {
    renderTable({
      items: [
        row({
          production: envValue({ description: "マスターの説明" }),
          local: envValue({ description: "ローカルだけの説明" }),
        }),
      ],
    });

    const localDescriptionCell = screen.getByText("ローカルだけの説明");
    expect(localDescriptionCell).toHaveClass("text-red-600");
  });

  it("値が無いセルはマスター/非マスターで表示文言を変える", () => {
    renderTable({
      items: [row({ production: envValue({ description: null }), local: envValue({ description: null }) })],
    });

    expect(screen.getAllByText("(未設定)").length).toBeGreaterThan(0);
    expect(screen.getAllByText("(未登録)").length).toBeGreaterThan(0);
  });
});

/** issue #1414(#1413の横展開): マウント前は押せない(TermComparisonTable.mountGate.test.tsx)。マウント後は押せる。 */
describe("TermComparisonTable(マウント後、issue #1414)", () => {
  it("マウント後は新規追加フォームの送信ボタンが有効", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={page()} />);

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));

    expect(screen.getByRole("button", { name: "マスター環境に追加" })).toBeEnabled();
  });

  it("マウント後は編集フォームの送信ボタンが有効", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={page()} />);

    fireEvent.click(screen.getByRole("button", { name: "編集" }));

    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeEnabled();
  });
});
