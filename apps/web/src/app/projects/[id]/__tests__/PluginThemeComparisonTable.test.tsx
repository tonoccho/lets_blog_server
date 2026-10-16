import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { PluginThemeComparisonTable } from "../PluginThemeComparisonTable";
import type { StatusComparisonPage, StatusComparisonRow, StatusEnvironmentValue } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies } from "./projectCoreRuntime";

/**
 * issue #1051: 新規インストールフォームの<form>にmethod="post"を明示した(JS無効時の
 * ネイティブGETフォールバックでダウンロードURL等がURLへ漏れるのを防ぐ)。
 *
 * `scripts/check-changed-coverage.py` はファイル単位で分岐カバレッジを見るため、この変更に
 * よって「変更したファイル」として扱われる本コンポーネントの既存ロジック(環境間の反映・
 * 削除・ページング)も、この機会にあわせて可能な範囲で押さえる。
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

const COMPARISON = "GET /api/projects/1/bulk-management/plugins/comparison";
const THEME_COMPARISON = "GET /api/projects/1/bulk-management/themes/comparison";
const RECONCILE = "POST /api/projects/1/bulk-management/plugins/reconcile";
const DELETE_ALL = "POST /api/projects/1/bulk-management/plugins/delete-all";
const APPLY = "POST /api/projects/1/bulk-management/apply";
const APPLY_ALL_JOBS = "POST /api/projects/1/bulk-management/apply-all/jobs";

const managedEnvironments = [
  { value: "test" as const, label: "テスト" },
  { value: "production" as const, label: "本番" },
];

function envValue(overrides: Partial<StatusEnvironmentValue> = {}): StatusEnvironmentValue {
  return { available: true, error: false, errorMessage: null, status: "ACTIVE", ...overrides };
}

function row(overrides: Partial<StatusComparisonRow> = {}): StatusComparisonRow {
  return {
    slug: "akismet",
    local: envValue(),
    test: envValue(),
    production: envValue({ status: "NOT_INSTALLED" }),
    ...overrides,
  };
}

function page(overrides: Partial<StatusComparisonPage> = {}): StatusComparisonPage {
  return {
    items: [row()],
    page: 0,
    size: 20,
    totalCount: 1,
    masterEnvironment: "production",
    ...overrides,
  };
}

describe("PluginThemeComparisonTable", () => {
  let confirmSpy: jest.SpyInstance;
  let backend: jest.Mock;

  /** 既定は一覧の再取得だけ成功する(想定外の通信は例外)。テストごとに必要な応答だけ足す。 */
  function install(routes: Record<string, () => Response> = {}) {
    backend = installFetchByPath({
      [COMPARISON]: () => jsonResponse(page()),
      [THEME_COMPARISON]: () => jsonResponse(page()),
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

  function renderTable(overrides: Partial<StatusComparisonPage> = {}, kind: "plugin" | "theme" = "plugin") {
    return render(
      <PluginThemeComparisonTable
        projectId={1}
        kind={kind}
        initialPage={page(overrides)}
        managedEnvironments={managedEnvironments}
      />
    );
  }

  it("新規インストールフォームはmethod=\"post\"を持つ", () => {
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));

    expect(container.querySelector("form")?.getAttribute("method")).toBe("post");
  });

  it("項目が無ければその旨を表示する", () => {
    renderTable({ items: [] });

    expect(screen.getByText("プラグインはまだありません。")).toBeInTheDocument();
  });

  it("テーマの場合はラベルが切り替わる", () => {
    renderTable({}, "theme");

    expect(screen.queryByText("プラグインはまだありません。")).not.toBeInTheDocument();
    expect(screen.getAllByText("テーマ", { exact: false }).length).toBeGreaterThan(0);
  });

  it("取得エラーの環境は「エラー」セルを表示する", () => {
    renderTable({ items: [row({ local: envValue({ error: true, errorMessage: "取得失敗" }) })] });

    const errorCell = screen.getByText("エラー");
    expect(errorCell).toHaveAttribute("title", "取得失敗");
  });

  it("未対応環境は「対象外」セルを表示する", () => {
    renderTable({ items: [row({ local: envValue({ available: false, status: null }) })] });

    expect(screen.getByText("対象外")).toBeInTheDocument();
  });

  it("変更が無い状態で反映を押すとエラーを表示し、確認ダイアログを出さない", async () => {
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("変更されたセルがありません。")).toBeInTheDocument();
    });
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(calledRequests(backend)).toEqual([]);
  });

  it("変更後に確認をキャンセルすると反映アクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    const select = screen.getAllByRole("combobox")[2]; // production列(NOT_INSTALLED)
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    expect(calledRequests(backend)).toEqual([]);
  });

  it("変更を反映すると成功メッセージを表示し、一覧を再取得する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [RECONCILE]: () => jsonResponse([]) });
    renderTable();

    const select = screen.getAllByRole("combobox")[2];
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("反映しました。")).toBeInTheDocument();
    });
    expect(bodies(backend, RECONCILE)).toEqual([
      { slug: "akismet", changes: [{ environment: "production", desiredStatus: "ACTIVE" }] },
    ]);
    expect(calledRequests(backend)).toEqual([RECONCILE, `${COMPARISON}?page=0`]);
  });

  it("対象外・状態が取れていない環境のセルは、変更の対象に含めない", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [RECONCILE]: () => jsonResponse([]) });
    renderTable({
      items: [row({ local: envValue({ available: false, status: null }), test: envValue({ status: null }) })],
    });

    // 対象外・取得できていないセルは select ではないので、残る production 列(NOT_INSTALLED)だけが選べる
    fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => expect(screen.getByText("反映しました。")).toBeInTheDocument());
    expect(bodies(backend, RECONCILE)).toEqual([
      { slug: "akismet", changes: [{ environment: "production", desiredStatus: "ACTIVE" }] },
    ]);
  });

  it("反映に失敗するとエラーメッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [RECONCILE]: () => textResponse("反映に失敗しました", 500) });
    renderTable();

    const select = screen.getAllByRole("combobox")[2];
    fireEvent.change(select, { target: { value: "ACTIVE" } });
    fireEvent.click(screen.getByRole("button", { name: "反映" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 反映に失敗しました")).toBeInTheDocument();
    });
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(calledRequests(backend)).toEqual([]);
  });

  it("削除の確認に、インストールされている環境の数と環境名を表示する", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({ items: [row({ production: envValue({ status: "INACTIVE" }) })] });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith(
      "「akismet」を、存在する3つの環境(ローカル・テスト・本番)から削除します。よろしいですか?"
    );
  });

  it("未インストール・対象外・エラーの環境は、確認に含めない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({
      items: [
        row({
          local: envValue({ available: false, status: null }),
          test: envValue({ error: true, status: null }),
          production: envValue({ status: "NOT_INSTALLED" }),
        }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith("「akismet」を、存在するすべての環境から削除します。よろしいですか?");
  });

  it("状態が取得できていない環境は、確認に含めない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({ items: [row({ local: envValue({ status: null }) })] });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(confirmSpy).toHaveBeenCalledWith("「akismet」を、存在する1つの環境(テスト)から削除します。よろしいですか?");
  });

  it("一部の環境にしか無い項目では、存在しない環境を確認に含めない", () => {
    confirmSpy.mockReturnValue(false);
    renderTable({ items: [row({ local: envValue({ status: "NOT_INSTALLED" }) })] }, "theme");

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    const message = confirmSpy.mock.calls[0][0] as string;
    expect(message).toContain("存在する1つの環境(テスト)");
    expect(message).not.toContain("ローカル");
    expect(message).not.toContain("本番");
  });

  it("削除に成功すると成功メッセージを表示する", async () => {
    confirmSpy.mockReturnValue(true);
    install({ [DELETE_ALL]: () => jsonResponse([]) });
    renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("削除しました。")).toBeInTheDocument();
    });
    expect(bodies(backend, DELETE_ALL)).toEqual([{ slug: "akismet" }]);
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

  it("先頭ページでは「前へ」が無効、最終ページでは「次へ」が無効になる", () => {
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

  it("環境更新ボタンで一覧を再取得する", async () => {
    renderTable();

    fireEvent.click(screen.getAllByRole("button", { name: /を更新$/ })[0]);

    await waitFor(() => {
      expect(calledRequests(backend)).toEqual([`${COMPARISON}?page=0`]);
    });
  });

  it("新規インストールフォームで単一環境へ送信すると一覧を更新して閉じる", async () => {
    install({ [APPLY]: () => jsonResponse({}) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "test" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, APPLY)).toEqual([{ environment: "test", operationType: "PLUGIN_INSTALL", value: "akismet" }]);
    });
    await waitFor(() => {
      expect(screen.queryByLabelText("対象環境")).not.toBeInTheDocument();
    });
  });

  it("新規インストールフォームで「全ての環境」へ送信すると全環境インストールのジョブ受理口へ送る", async () => {
    install({ [APPLY_ALL_JOBS]: () => jsonResponse({ id: 9, status: "running" }) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "__all__" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, APPLY_ALL_JOBS)).toEqual([{ operationType: "PLUGIN_INSTALL", value: "akismet" }]);
    });
    expect(bodies(backend, APPLY)).toEqual([]);
  });

  it("テーマの新規インストールはテーマのインストールとして送る", async () => {
    install({ [APPLY]: () => jsonResponse({}) });
    const { container } = renderTable({}, "theme");

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "production" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "twentytwentyfive" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(bodies(backend, APPLY)).toEqual([
        { environment: "production", operationType: "THEME_INSTALL", value: "twentytwentyfive" },
      ]);
    });
  });

  it("「全ての環境」への要求が受理されたら、処理キューへ追加された旨を示し、完了を待たずボタンは押せる状態に戻る(#1702)", async () => {
    install({ [APPLY_ALL_JOBS]: () => jsonResponse({ id: 9, status: "running" }) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "__all__" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText(/全環境へのインストールを要求しました。処理キューに追加されました/)).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "インストール" })).toBeEnabled();
    // インストールはまだ終わっていないので、一覧は取り直さない
    expect(calledRequests(backend)).toEqual([APPLY_ALL_JOBS]);
  });

  it("「全ての環境」への要求がエラーなら、受理の表示は出さない(#1702)", async () => {
    install({ [APPLY_ALL_JOBS]: () => jsonResponse({ id: 9, status: "failed" }) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "__all__" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText(/全環境インストールの待ち行列が満杯です/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/全環境へのインストールを要求しました/)).toBeNull();
  });

  it("新規インストールに失敗するとエラーを表示したままフォームを開いておく", async () => {
    install({ [APPLY]: () => textResponse("インストールに失敗しました", 500) });
    const { container } = renderTable();

    fireEvent.click(screen.getByRole("button", { name: "+ 新規インストール" }));
    fireEvent.change(screen.getByLabelText("対象環境"), { target: { value: "test" } });
    fireEvent.change(screen.getByPlaceholderText("akismet"), { target: { value: "akismet" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): インストールに失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("対象環境")).toBeInTheDocument();
  });

  it("テーマがACTIVEの場合、無効の選択肢を無効化する", () => {
    renderTable({ items: [row({ local: envValue({ status: "ACTIVE" }) })] }, "theme");

    const select = screen.getAllByRole("combobox")[0] as HTMLSelectElement;
    const inactiveOption = within(select).getByRole("option", { name: "無効" }) as HTMLOptionElement;
    expect(inactiveOption.disabled).toBe(true);
  });
});
