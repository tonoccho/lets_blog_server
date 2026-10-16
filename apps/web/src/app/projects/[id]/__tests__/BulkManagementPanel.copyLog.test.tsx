import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { BulkManagementPanel } from "../BulkManagementPanel";
import type { BulkOperationLog, Project, Site, TermComparisonPage } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";

/**
 * issue #1363(親issue #1261 分割B)Requirement 4 / Acceptance Criteria 4。
 *
 * `describeLogText()` は失敗ログの「コピー」ボタンの`onClick`(`CopyLogButton`の
 * `handleCopy`)からしか呼ばれず、`ImageGalleryGrid`等と違いJSXの描画中には走らない
 * (`ZipUploadPanel`の`uploadState.results`はアップロード完了後にしか埋まらない)。
 * サーバー描画時点の値が存在しないため、#1362/#1363の`mounted`ゲートが防ぐ
 * ハイドレーション不一致はそもそも起こり得ず、本番コードの変更は不要という結論を
 * Readiness Report(2026-09-20、2回目)で確認済み。
 *
 * (issue #1702: zipの全環境インストールはジョブとして受理されるだけになり、環境ごとの結果は
 * 処理キューの「結果を見る」(`?bulkJob=`)で開くジョブ結果の表示に載る。失敗ログのコピーボタンは
 * その表示から使うので、本テストもその経路で失敗ログを表示する。)
 *
 * ここでは「本番コードを変えない」という判断そのものを固定するため、コピーされる
 * テキストが受け取った`timezone`で書式化されていることを確かめる。`formatDateTime` は差し替えず実物を通す
 * (`Asia/Tokyo` の日時は UTC から +9 時間ずれる)。自前の `../actions`・`@/lib/apiClient`・比較表のコンポーネントも
 * 差し替えず、外部との通信(`fetch`)と Next.js の実行基盤だけを代替する(#1836)。
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

function buildSite(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    name: "site",
    siteKey: "site",
    cmsType: "WORDPRESS",
    baseUrl: "https://example.com",
    createdAt: "",
    updatedAt: "",
    connectionCheckStatus: null,
    managedWordpress: true,
    sshConfigured: false,
    ...overrides,
  };
}

function buildProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: "project",
    slug: "project",
    localSite: null,
    testSite: buildSite({ id: 20, siteKey: "test-site" }),
    productionSite: null,
    masterEnvironment: "test",
    githubRepository: null,
    createdAt: "",
    updatedAt: "",
    ...overrides,
  };
}

function buildCategoryPage(): TermComparisonPage {
  return { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" };
}

function buildFailedLog(overrides: Partial<BulkOperationLog> = {}): BulkOperationLog {
  return {
    operationType: "PLUGIN_INSTALL",
    sourceType: "ZIP",
    value: "custom.zip",
    categorySlug: null,
    categoryParentSlug: null,
    categoryTargetSlug: null,
    categoryDescription: null,
    originalFilename: "custom.zip",
    postStatus: null,
    environment: "test",
    status: "FAILED",
    level: "ERROR",
    errorMessage: "boom",
    stackTrace: "trace",
    createdAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

/** 処理キューの「結果を見る」から開いたときと同じく、ジョブの結果を表示し、失敗ログの「コピー」ボタンが現れるまで待つ。 */
async function openJobResultWithFailure(timezone: string | null, log: BulkOperationLog) {
  installFetchByPath({
    "GET /api/generation-jobs/9": () =>
      jsonResponse({
        id: 9,
        type: "bulk_zip_install",
        status: "done",
        requestPayload: null,
        resultPayload: JSON.stringify({ phase: "done", projectId: 1, logs: [log] }),
        createdAt: "",
        updatedAt: "",
      }),
  });

  render(
    <BulkManagementPanel
      projectId={1}
      project={buildProject()}
      categoryPage={buildCategoryPage()}
      timezone={timezone}
      bulkJobId={9}
    />
  );

  await waitFor(() => {
    expect(screen.getByRole("button", { name: "コピー" })).toBeInTheDocument();
  });
}

async function copiedText(timezone: string | null, log: BulkOperationLog): Promise<string> {
  await openJobResultWithFailure(timezone, log);
  fireEvent.click(screen.getByRole("button", { name: "コピー" }));
  await waitFor(() => {
    expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1);
  });
  return (navigator.clipboard.writeText as jest.Mock).mock.calls[0][0] as string;
}

describe("BulkManagementPanel 失敗ログのコピー(issue #1363 Requirement 4)", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } });
  });

  it("個人設定TZがあるとき、コピーした内容はそのTZで書式化した日時を含む", async () => {
    const copied = await copiedText("Asia/Tokyo", buildFailedLog());

    // 2026-09-08T20:03:35(オフセット無し = UTC)は、Asia/Tokyo では翌日の 05:03:35
    expect(copied).toContain("日時: 2026/9/9 5:03:35");
  });

  it("個人設定TZが未設定のとき、コピーした内容はブラウザ既定TZで書式化した日時を含む", async () => {
    const copied = await copiedText(null, buildFailedLog());

    expect(copied).toMatch(/^日時: \d{4}\/\d{1,2}\/\d{1,2} \d{1,2}:\d{2}:\d{2}\n/);
  });

  it("zip のログは「元のファイル名(zip)」を値として示し、エラーとスタックトレースも含める", async () => {
    const copied = await copiedText("UTC", buildFailedLog());

    expect(copied).toBe(
      [
        "日時: 2026/9/8 20:03:35",
        "操作: プラグインインストール",
        "値: custom.zip(zip)",
        "環境: テスト",
        "ステータス: 失敗",
        "エラー: boom",
        "",
        "スタックトレース:",
        "trace",
      ].join("\n")
    );
  });

  it("zip で元のファイル名が無いときは値をそのまま示し、エラーとスタックトレースが無ければ「(なし)」にする", async () => {
    const copied = await copiedText(
      "UTC",
      buildFailedLog({ originalFilename: null, errorMessage: null, stackTrace: null })
    );

    expect(copied).toContain("値: custom.zip(zip)");
    expect(copied).toContain("エラー: (なし)");
    expect(copied.endsWith("スタックトレース:\n(なし)")).toBe(true);
  });

  it("カテゴリ・タグの操作は、スラッグ(編集・削除は対象のスラッグ)を値に添える", async () => {
    const withSlug = await copiedText(
      "UTC",
      buildFailedLog({ operationType: "CATEGORY_CREATE", sourceType: "SLUG", value: "お知らせ", categorySlug: "news" })
    );
    expect(withSlug).toContain("値: お知らせ(news)");
  });

  it("カテゴリ・タグの操作でスラッグが無ければ、値だけを示す", async () => {
    const copied = await copiedText(
      "UTC",
      buildFailedLog({ operationType: "TAG_DELETE", sourceType: "SLUG", value: "雑記", categorySlug: null, categoryTargetSlug: null })
    );
    expect(copied).toContain("値: 雑記\n");
  });

  it("カテゴリ・タグの編集は、対象のスラッグを値に添える", async () => {
    const copied = await copiedText(
      "UTC",
      buildFailedLog({ operationType: "TAG_EDIT", sourceType: "SLUG", value: "雑記", categorySlug: null, categoryTargetSlug: "misc" })
    );
    expect(copied).toContain("値: 雑記(misc)");
  });

  it("ポスト/ページのステータス変更は「値 → 変更後のステータス」を示す", async () => {
    const copied = await copiedText(
      "UTC",
      buildFailedLog({ operationType: "POST_STATUS_UPDATE", sourceType: "SLUG", value: "hello", postStatus: "draft" })
    );
    expect(copied).toContain("操作: ポスト/ページステータス変更");
    expect(copied).toContain("値: hello → draft");
  });

  it("ステータス変更でも変更後のステータスが無ければ、値だけを示す", async () => {
    const copied = await copiedText(
      "UTC",
      buildFailedLog({ operationType: "POST_STATUS_UPDATE", sourceType: "SLUG", value: "hello", postStatus: null })
    );
    expect(copied).toContain("値: hello\n");
  });

  it("その他の操作は値をそのまま示し、コピー後は「コピーしました」に変わって2秒で戻る", async () => {
    jest.useFakeTimers();
    try {
      installFetchByPath({
        "GET /api/generation-jobs/9": () =>
          jsonResponse({
            id: 9,
            type: "bulk_slug_install",
            status: "done",
            requestPayload: null,
            resultPayload: JSON.stringify({ phase: "done", projectId: 1, logs: [buildFailedLog({ sourceType: "SLUG", value: "akismet" })] }),
            createdAt: "",
            updatedAt: "",
          }),
      });
      render(
        <BulkManagementPanel projectId={1} project={buildProject()} categoryPage={buildCategoryPage()} timezone="UTC" bulkJobId={9} />
      );
      const copyButton = await screen.findByRole("button", { name: "コピー" });

      fireEvent.click(copyButton);
      expect(await screen.findByRole("button", { name: "コピーしました" })).toBeInTheDocument();
      expect((navigator.clipboard.writeText as jest.Mock).mock.calls[0][0]).toContain("値: akismet\n");
      await act(async () => {
        await jest.advanceTimersByTimeAsync(2000);
      });

      expect(screen.getByRole("button", { name: "コピー" })).toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });
});
