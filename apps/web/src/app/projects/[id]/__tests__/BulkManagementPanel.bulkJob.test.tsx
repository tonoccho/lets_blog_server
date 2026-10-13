import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { BulkManagementPanel } from "../BulkManagementPanel";
import type { BulkOperationLog, Project, Site, TermComparisonPage } from "@/lib/apiClient";

/**
 * issue #1702: zipの全環境インストールは受理だけで返り(完了を待たない)、環境ごとの成否は処理キューの
 * 「結果を見る」(`?tab=maintenance&bulkJob=<ジョブID>`)で開くジョブ結果に出る。
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

import { fetchGenerationJobAction, runBulkOperationUploadAction } from "../actions";

const fetchJob = fetchGenerationJobAction as jest.Mock;
const runUpload = runBulkOperationUploadAction as jest.Mock;

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

function renderPanel(bulkJobId?: number) {
  return render(
    <BulkManagementPanel projectId={1} project={project} categoryPage={categoryPage} timezone={null} bulkJobId={bulkJobId} />
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("BulkManagementPanel ?bulkJob の結果表示(issue #1702)", () => {
  it("done のジョブは、環境ごとの成否を local, test の順に表示する", async () => {
    fetchJob.mockResolvedValue(job("done", { phase: "done", projectId: 1, logs: [log("local", "SUCCESS"), log("test", "FAILED", "not found")] }));
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    // 表示は取得の完了後に出る(読み込み中は結果の一覧が無い)。
    const items = await within(result).findAllByRole("listitem");
    expect(fetchJob).toHaveBeenCalledWith(9);
    expect(items.map((li) => li.textContent)).toEqual(["ローカル: 成功", "テスト: 失敗(not found)コピー"]);
  });

  it("実行中のジョブは、まだ終わっていない旨を表示する", async () => {
    fetchJob.mockResolvedValue(job("running", null));
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("実行中"));
    expect(within(result).queryByRole("listitem")).toBeNull();
  });

  it("failed のジョブは、失敗の理由を表示する", async () => {
    fetchJob.mockResolvedValue(job("failed", { error: "待ち行列が満杯です", errorType: "queue_full" }));
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("待ち行列が満杯です"));
  });

  it("結果が読めない done のジョブは、読めない旨を表示する", async () => {
    fetchJob.mockResolvedValue(job("done", null));
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("結果を読み取れませんでした"));
  });

  it("ジョブを取得できなければ、そのエラーを表示する", async () => {
    fetchJob.mockRejectedValue(new Error("404"));
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("404"));
  });

  it("文字列でない失敗も、読める文言で表示する", async () => {
    fetchJob.mockRejectedValue("boom");
    renderPanel(9);

    const result = await screen.findByTestId("bulk-job-result");
    await waitFor(() => expect(result).toHaveTextContent("boom"));
  });

  it("bulkJob が無ければ、結果の表示もジョブの取得もしない", () => {
    renderPanel();

    expect(screen.queryByTestId("bulk-job-result")).toBeNull();
    expect(fetchJob).not.toHaveBeenCalled();
  });
});

describe("BulkManagementPanel zipの全環境インストールの受理(issue #1702)", () => {
  async function openZipForm() {
    const rendered = renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "プラグイン" }));
    await screen.findByRole("button", { name: "全環境へインストール" });
    const fileInput = rendered.container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(fileInput, { target: { files: [new File(["x"], "p.zip", { type: "application/zip" })] } });
    jest.spyOn(window, "confirm").mockReturnValue(true);
    return rendered;
  }

  it("受理されたら、処理キューへ追加された旨を示し、ボタンは押せる状態のままで、環境ごとの結果は出さない", async () => {
    runUpload.mockResolvedValue({ success: true, jobId: 5 });
    const { container } = await openZipForm();

    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await screen.findByText(/全環境へのインストールを要求しました。処理キューに追加されました/);
    expect(screen.getByRole("button", { name: "全環境へインストール" })).toBeEnabled();
    expect(screen.queryByRole("listitem")).toBeNull();
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull();
  });

  it("エラーは赤字で示し、受理の表示は出さない", async () => {
    runUpload.mockResolvedValue({ error: "全環境インストールの待ち行列が満杯です" });
    const { container } = await openZipForm();

    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    await waitFor(() => expect(screen.getByText("全環境インストールの待ち行列が満杯です")).toBeInTheDocument());
    expect(screen.queryByText(/全環境へのインストールを要求しました/)).toBeNull();
  });
});
