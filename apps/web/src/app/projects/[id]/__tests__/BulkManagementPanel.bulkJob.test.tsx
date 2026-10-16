import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { BulkManagementPanel } from "../BulkManagementPanel";
import type { BulkOperationLog, Project, Site, TermComparisonPage } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse, type FetchRoutes } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, attachFile, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies } from "./projectCoreRuntime";

/**
 * issue #1702: zipの全環境インストールは受理だけで返り(完了を待たない)、環境ごとの成否は処理キューの
 * 「結果を見る」(`?tab=maintenance&bulkJob=<ジョブID>`)で開くジョブ結果に出る。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session`・比較表のコンポーネント(`TermComparisonTable` など)は差し替えず、
 * Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1836)。
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

const JOB = "GET /api/generation-jobs/9";
const ZIP_JOBS = "POST /api/projects/1/bulk-management/upload/jobs";
const TAG_COMPARISON = "GET /api/projects/1/bulk-management/tags/comparison";
const PLUGIN_COMPARISON = "GET /api/projects/1/bulk-management/plugins/comparison";
const THEME_COMPARISON = "GET /api/projects/1/bulk-management/themes/comparison";
const POST_COMPARISON = "GET /api/projects/1/bulk-management/posts/comparison";
const POST_STATUSES = "GET /api/metadata/post-statuses";

function site(): Site {
  return {
    id: 1, name: "s", siteKey: "s", cmsType: "WORDPRESS", baseUrl: "https://example.com", createdAt: "", updatedAt: "",
    connectionCheckStatus: null, managedWordpress: true, sshConfigured: false,
  };
}

const project = {
  id: 1, name: "p", slug: "p", localSite: site(), testSite: site(), productionSite: null, masterEnvironment: "test",
  githubRepository: null, createdAt: "", updatedAt: "",
} as unknown as Project;

const categoryPage: TermComparisonPage = { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" };

function log(environment: "local" | "test" | "production", status: "SUCCESS" | "FAILED" | "SKIPPED", errorMessage: string | null = null): BulkOperationLog {
  return {
    operationType: "PLUGIN_INSTALL", sourceType: "SLUG", value: "akismet", categorySlug: null, categoryParentSlug: null,
    categoryTargetSlug: null, categoryDescription: null, originalFilename: null, postStatus: null, environment, status,
    level: status === "FAILED" ? "ERROR" : "INFO", errorMessage, stackTrace: null, createdAt: "2026-10-13T01:02:03Z",
  };
}

function job(status: string, resultPayload: unknown) {
  return {
    id: 9, type: "bulk_slug_install", status, requestPayload: null,
    resultPayload: resultPayload === null ? null : JSON.stringify(resultPayload), createdAt: "", updatedAt: "",
  };
}

const termPage = (items: unknown[] = []) => ({ items, page: 0, size: 20, totalCount: items.length, masterEnvironment: "test" });
const statusPage = (items: unknown[] = []) => ({ items, page: 0, size: 20, totalCount: items.length, masterEnvironment: "test" });
const postPage = { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test", postType: "post" };

/** 全ての取得が成功するバックエンド。テストごとに `routes` で一部だけ差し替える。 */
function installBackend(routes: FetchRoutes = {}): jest.Mock {
  return installFetchByPath({
    [TAG_COMPARISON]: () => jsonResponse(termPage()),
    [PLUGIN_COMPARISON]: () => jsonResponse(statusPage()),
    [THEME_COMPARISON]: () => jsonResponse(statusPage()),
    [POST_COMPARISON]: () => jsonResponse(postPage),
    [POST_STATUSES]: () => jsonResponse([]),
    ...routes,
  });
}

function renderPanel(bulkJobId?: number) {
  return render(
    <BulkManagementPanel projectId={1} project={project} categoryPage={categoryPage} timezone={null} bulkJobId={bulkJobId} />
  );
}

let confirmSpy: jest.SpyInstance;
beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  installBackend();
  confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => {
  confirmSpy.mockRestore();
});

describe("BulkManagementPanel ?bulkJob の結果表示(issue #1702)", () => {
  it("done のジョブは、環境ごとの成否を local, test の順に表示する", async () => {
    const fetchMock = installBackend({
      [JOB]: () => jsonResponse(job("done", { phase: "done", projectId: 1, logs: [log("local", "SUCCESS"), log("test", "FAILED", "not found")] })),
    });
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    // 表示は取得の完了後に出る(読み込み中は結果の一覧が無い)。
    const items = await within(result).findAllByRole("listitem");
    expect(calledRequests(fetchMock)).toEqual([JOB]);
    expect(items.map((li) => li.textContent)).toEqual(["ローカル: 成功", "テスト: 失敗(not found)コピー"]);
  });

  it("実行中のジョブは、まだ終わっていない旨を表示する", async () => {
    installBackend({ [JOB]: () => jsonResponse(job("running", null)) });
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("実行中"));
    expect(within(result).queryByRole("listitem")).toBeNull();
  });

  it("failed のジョブは、失敗の理由を表示する", async () => {
    installBackend({ [JOB]: () => jsonResponse(job("failed", { error: "待ち行列が満杯です", errorType: "queue_full" })) });
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("待ち行列が満杯です"));
  });

  it("結果が読めない done のジョブは、読めない旨を表示する", async () => {
    installBackend({ [JOB]: () => jsonResponse(job("done", null)) });
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("結果を読み取れませんでした"));
  });

  it("ジョブを取得できなければ、そのエラーを表示する", async () => {
    installBackend({ [JOB]: () => textResponse("not found", 404) });
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("APIエラー (404): not found"));
  });

  it("文字列でない失敗も、読める文言で表示する", async () => {
    global.fetch = jest.fn().mockRejectedValue("boom") as unknown as typeof fetch;
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("boom"));
  });

  it("bulkJob が無ければ、結果の表示もジョブの取得もしない", () => {
    const fetchMock = installBackend();
    renderPanel();

    expect(screen.queryByTestId("bulk-job-result")).toBeNull();
    expect(calledRequests(fetchMock)).toEqual([]);
  });
});

describe("BulkManagementPanel zipの全環境インストールの受理(issue #1702)", () => {
  async function openZipForm(tab = "プラグイン", fileName = "p.zip") {
    const rendered = renderPanel();
    fireEvent.click(screen.getByRole("button", { name: tab }));
    await screen.findByRole("button", { name: "全環境へインストール" });
    const fileInput = rendered.container.querySelector('input[type="file"]') as HTMLInputElement;
    attachFile(fileInput, new File(["x"], fileName, { type: "application/zip" }));
    return rendered;
  }

  async function submit(container: HTMLElement) {
    await act(async () => {
      fireEvent.submit(container.querySelector("form") as HTMLFormElement);
    });
  }

  it("受理されたら、処理キューへ追加された旨を示し、ボタンは押せる状態のままで、環境ごとの結果は出さない", async () => {
    const fetchMock = installBackend({ [ZIP_JOBS]: () => jsonResponse({ id: 5, status: "running" }) });
    const { container } = await openZipForm();

    await submit(container);

    await screen.findByText(/全環境へのインストールを要求しました。処理キューに追加されました/);
    expect(screen.getByRole("button", { name: "全環境へインストール" })).toBeEnabled();
    expect(screen.queryByRole("listitem")).toBeNull();
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull();
    const [sent] = bodies(fetchMock, ZIP_JOBS) as FormData[];
    expect(sent.get("operationType")).toBe("PLUGIN_INSTALL");
    expect((sent.get("file") as File).name).toBe("p.zip");
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining("プラグインインストール「p.zip」"));
  });

  it("テーマのタブでは、テーマのインストールとして送る", async () => {
    const fetchMock = installBackend({ [ZIP_JOBS]: () => jsonResponse({ id: 5, status: "running" }) });
    const { container } = await openZipForm("テーマ", "t.zip");

    await submit(container);

    await screen.findByText(/全環境へのインストールを要求しました/);
    expect((bodies(fetchMock, ZIP_JOBS)[0] as FormData).get("operationType")).toBe("THEME_INSTALL");
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining("テーマインストール「t.zip」"));
  });

  it("エラーは赤字で示し、受理の表示は出さない", async () => {
    installBackend({ [ZIP_JOBS]: () => jsonResponse({ id: 5, status: "failed" }) });
    const { container } = await openZipForm();

    await submit(container);

    await waitFor(() => expect(screen.getByText(/全環境インストールの待ち行列が満杯です/)).toBeInTheDocument());
    expect(screen.queryByText(/全環境へのインストールを要求しました/)).toBeNull();
  });

  it("要求の往復の間は「要求中…」を示して送信できなくする", async () => {
    let release: (r: Response) => void = () => {};
    installBackend({ [ZIP_JOBS]: () => new Promise<Response>((resolve) => (release = resolve)) });
    const { container } = await openZipForm();

    await submit(container);

    expect(await screen.findByRole("button", { name: "要求中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({ id: 5, status: "running" }));
    });
    await screen.findByText(/全環境へのインストールを要求しました/);
  });

  it("確認で取り消すと、zip を送らない", async () => {
    confirmSpy.mockReturnValue(false);
    const fetchMock = installBackend({ [ZIP_JOBS]: () => jsonResponse({ id: 5, status: "running" }) });
    const { container } = await openZipForm();

    await submit(container);

    expect(calledRequests(fetchMock)).not.toContain(ZIP_JOBS);
    expect(screen.queryByText(/全環境へのインストールを要求しました/)).toBeNull();
  });
});

describe("BulkManagementPanel のタブ切り替え", () => {
  const tagItem = {
    name: "雑記",
    slug: "misc",
    local: { available: true, error: false, errorMessage: null, slug: "misc", parentSlug: null, description: null },
    test: { available: true, error: false, errorMessage: null, slug: "misc", parentSlug: null, description: null },
    production: { available: false, error: false, errorMessage: null, slug: null, parentSlug: null, description: null },
  };

  it("管理対象(managed)の環境が無いときは、案内だけを出す", () => {
    const unmanaged = { ...project, localSite: { ...site(), managedWordpress: false }, testSite: null } as unknown as Project;
    render(<BulkManagementPanel projectId={1} project={unmanaged} categoryPage={categoryPage} timezone={null} />);

    expect(screen.getByText(/自動構築\(managed\)されたWordPress環境が1つ以上紐付いている場合/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "タグ" })).toBeNull();
  });

  it("紐付いている環境の名前と、操作ログへのリンクを示す", () => {
    renderPanel();

    expect(screen.getByText(/紐付いている環境\(ローカル・テスト\)の/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "操作ログ" })).toHaveAttribute("href", "/operation-logs?type=OPERATION");
  });

  it("タグのタブを初めて開くと取得して比較表を出し、2回目以降は取り直さない", async () => {
    const fetchMock = installBackend({ [TAG_COMPARISON]: () => jsonResponse(termPage([tagItem])) });
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "タグ" }));
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    expect(await screen.findByText("タグ名")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "カテゴリ" }));
    fireEvent.click(screen.getByRole("button", { name: "タグ" }));

    expect(screen.getByText("タグ名")).toBeInTheDocument();
    expect(calledRequests(fetchMock)).toEqual([`${TAG_COMPARISON}?page=0`]);
  });

  it("プラグイン・テーマのタブも初めて開いたときに取得し、比較表と zip の欄を出す", async () => {
    const fetchMock = installBackend();
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "プラグイン" }));
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    expect(await screen.findByText("プラグインはまだありません。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "テーマ" }));
    expect(await screen.findByText("テーマはまだありません。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "プラグイン" }));
    fireEvent.click(screen.getByRole("button", { name: "テーマ" }));

    expect(screen.getByRole("button", { name: "全環境へインストール" })).toBeInTheDocument();
    expect(calledRequests(fetchMock)).toEqual([`${PLUGIN_COMPARISON}?page=0`, `${THEME_COMPARISON}?page=0`]);
  });

  it("別のタブの取得が終わって読み込み中の表示が消えたとき、まだ取得中のタブは取得を案内する文言になる", async () => {
    let releaseTag: ((r: Response) => void) | null = null;
    let releaseTheme: ((r: Response) => void) | null = null;
    installBackend({
      [TAG_COMPARISON]: () => new Promise<Response>((resolve) => (releaseTag = resolve)),
      [THEME_COMPARISON]: () => new Promise<Response>((resolve) => (releaseTheme = resolve)),
    });
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "タグ" }));
    fireEvent.click(screen.getByRole("button", { name: "テーマ" }));
    // 2 つの取得がどちらもバックエンドに届いてから、タグだけ先に終わらせる
    await waitFor(() => {
      expect(releaseTag).not.toBeNull();
      expect(releaseTheme).not.toBeNull();
    });
    await act(async () => {
      (releaseTag as unknown as (r: Response) => void)(jsonResponse(termPage()));
    });

    expect(screen.getByText("このタブを開くとデータを取得します。")).toBeInTheDocument();
    await act(async () => {
      (releaseTheme as unknown as (r: Response) => void)(jsonResponse(statusPage()));
    });
    expect(await screen.findByText("テーマはまだありません。")).toBeInTheDocument();
  });

  it("ポスト/ページのタブも初めて開いたときに取得して比較表を出す", async () => {
    const fetchMock = installBackend();
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "ポスト/ページ" }));
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    expect(await screen.findByText("ポストはまだありません。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "カテゴリ" }));
    fireEvent.click(screen.getByRole("button", { name: "ポスト/ページ" }));

    expect(screen.getByText("ポストはまだありません。")).toBeInTheDocument();
    await act(async () => {}); // ステータス一覧の取得(マウント時)の完了を待つ
    expect(calledRequests(fetchMock).filter((r) => r.startsWith(POST_COMPARISON))).toEqual([
      `${POST_COMPARISON}?postType=post&page=0`,
    ]);
  });
});
