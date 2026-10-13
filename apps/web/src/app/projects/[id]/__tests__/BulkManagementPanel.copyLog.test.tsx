import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { BulkManagementPanel } from "../BulkManagementPanel";
import type { BulkOperationLog, Project, Site, TermComparisonPage } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B)Requirement 4 / Acceptance Criteria 4。
 *
 * `describeLogText()`(:308)は失敗ログの「コピー」ボタンの`onClick`(`CopyLogButton`の
 * `handleCopy`、:326)からしか呼ばれず、`ImageGalleryGrid`等と違いJSXの描画中には走らない
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
 * テキストが受け取った`timezone`を素直に`formatDateTime`へ渡していることを確かめる。
 * `timezone`がnullのときに`formatDateTime`自身がブラウザTZへフォールバックする挙動は
 * `formatDate.test.ts`で既に確認済みのため、ここでは伝播の正しさだけを見る。
 */
jest.mock("../actions", () => ({
  runBulkOperationUploadAction: jest.fn(),
  fetchGenerationJobAction: jest.fn(),
  fetchTermComparisonAction: jest.fn(),
  fetchStatusComparisonAction: jest.fn(),
  fetchPostComparisonAction: jest.fn(),
}));

jest.mock("../TermComparisonTable", () => ({ TermComparisonTable: () => null }));
jest.mock("../PluginThemeComparisonTable", () => ({ PluginThemeComparisonTable: () => null }));
jest.mock("../PostComparisonTable", () => ({ PostComparisonTable: () => null }));

jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn((iso: string, tz?: string | null) => `FORMATTED(${iso}|${tz})`),
}));

import { fetchGenerationJobAction } from "../actions";

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
  (fetchGenerationJobAction as jest.Mock).mockResolvedValue({
    id: 9,
    type: "bulk_zip_install",
    status: "done",
    requestPayload: null,
    resultPayload: JSON.stringify({ phase: "done", projectId: 1, logs: [log] }),
    createdAt: "",
    updatedAt: "",
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

describe("BulkManagementPanel 失敗ログのコピー(issue #1363 Requirement 4)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } });
  });

  it("個人設定TZがあるとき、コピーした内容はそのTZでformatDateTimeを呼ぶ", async () => {
    const log = buildFailedLog();
    await openJobResultWithFailure("Asia/Tokyo", log);

    fireEvent.click(screen.getByRole("button", { name: "コピー" }));

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1);
    });
    const copied = (navigator.clipboard.writeText as jest.Mock).mock.calls[0][0] as string;
    expect(copied).toContain(`日時: FORMATTED(${log.createdAt}|Asia/Tokyo)`);
  });

  it("個人設定TZが未設定のとき、コピーした内容はTZ引数無しでformatDateTimeを呼ぶ(ブラウザTZへフォールバック)", async () => {
    const log = buildFailedLog();
    await openJobResultWithFailure(null, log);

    fireEvent.click(screen.getByRole("button", { name: "コピー" }));

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1);
    });
    const copied = (navigator.clipboard.writeText as jest.Mock).mock.calls[0][0] as string;
    expect(copied).toContain(`日時: FORMATTED(${log.createdAt}|null)`);
  });
});
