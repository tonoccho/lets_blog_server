/**
 * 画面から要求したサイト自動構築の完了を、サイト一覧にそのサイトの行が現れることで確かめる(issue #1696、#1768)。
 *
 * サイト自動構築はジョブとして受理され(`POST /api/sites/managed-wordpress/jobs`)、フォームは完了を待たない。
 * 完了後の一覧は自動では更新されないので、行が現れるまで一覧を読み込み直す。構築は実測で最大240秒かかる。
 *
 * #1768: 原因が何であっても「280秒の時間切れ」にしかならず証跡から切り分けられなかったため、
 * 1. 「構築する」の後、受理メッセージかエラー表示が出るまで待つ(`waitForProvisioningOutcome`)。エラーなら即失敗。
 * 2. 行を待つ間、そのサイトキーのジョブを見て、failed なら即失敗する。
 * 3. 時間切れのメッセージに、ジョブの状態・一覧の件数表示・一覧を読んだユーザーを含める。
 *
 * Playwright の実体は import しない(型だけ)。偽の Page で単体テストできるようにするため
 * (`e2e/provisionedSiteRow.test.ts`。前例は `siteCreationPanel.ts`)。
 */

export const DEFAULT_ROW_TIMEOUT_MS = 280_000;
export const DEFAULT_ROW_INTERVAL_MS = 3_000;
export const DEFAULT_OUTCOME_TIMEOUT_MS = 30_000;
export const DEFAULT_OUTCOME_INTERVAL_MS = 250;
const ROW_VISIBLE_TIMEOUT_MS = 5_000;

/** `ManagedWordPressForm.tsx` のエラー表示と受理メッセージ。 */
const FORM_ERROR_SELECTOR = 'form:has(input[name="managedSiteKey"]) p.text-red-600';
const FORM_NOTICE_SELECTOR = 'form:has(input[name="managedSiteKey"]) p.text-green-600';
const ACCEPTED_NOTICE = '構築を要求しました。処理キューに追加されました';
/** `SiteListTable.tsx` の件数表示。「N件を表示 (全M件中)」と「全N件」の両方に当たる。 */
const COUNT_SELECTOR = 'span:text-matches("全\\\\d+件")';

/** Playwright の `Page` / `Locator` のうち、このヘルパーが使う部分だけ。 */
export interface RowLocator {
  waitFor(options: { state: 'visible'; timeout: number }): Promise<unknown>;
  allTextContents(): Promise<string[]>;
}
export interface RowPage {
  goto(url: string): Promise<unknown>;
  locator(selector: string): RowLocator;
}

/** そのサイトキーを要求に持つ `site_provisioning` ジョブ。`error` は結果ペイロードの `error`。 */
export interface ProvisionedJob {
  id: number;
  status: string;
  error?: string | null;
}

export interface WaitForProvisioningOutcomeOptions {
  timeoutMs?: number;
  intervalMs?: number;
}

export interface WaitForProvisionedSiteRowOptions {
  /** 一覧を読んだユーザー(失敗メッセージに含める)。 */
  readUser: string;
  /** そのサイトキーのジョブを探す。見つからなければ null。 */
  findJob: (siteKey: string) => Promise<ProvisionedJob | null>;
  timeoutMs?: number;
  intervalMs?: number;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

async function texts(page: RowPage, selector: string): Promise<string[]> {
  return (await page.locator(selector).allTextContents().catch(() => [] as string[]))
    .map((text) => text.trim())
    .filter((text) => text.length > 0);
}

/**
 * 「構築する」の後、受理メッセージかエラー表示のどちらかが出るまで待つ。
 * エラー表示が出たら、その文言を含めて即座に失敗する。どちらも出なければ時間切れで失敗する。
 */
export async function waitForProvisioningOutcome(page: RowPage, options?: WaitForProvisioningOutcomeOptions): Promise<void> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_OUTCOME_TIMEOUT_MS;
  const intervalMs = options?.intervalMs ?? DEFAULT_OUTCOME_INTERVAL_MS;
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const errors = await texts(page, FORM_ERROR_SELECTOR);
    if (errors.length > 0) {
      throw new Error(`サイト自動構築のフォームがエラーを表示した: ${errors.join(' / ')}`);
    }
    const notices = await texts(page, FORM_NOTICE_SELECTOR);
    if (notices.some((text) => text.includes(ACCEPTED_NOTICE))) return;
    if (Date.now() >= deadline) {
      throw new Error(`「構築する」から ${timeoutMs}ms 経っても、受理もエラーも表示されなかった(送信が打ち切られた可能性)`);
    }
    await sleep(intervalMs);
  }
}

/** 失敗メッセージ用のジョブの状態。`見つからない` / `pending` / `running` / `done` / `failed` のいずれか、または取得失敗。 */
function describeJob(job: ProvisionedJob | null | 'error', lookupError: string): string {
  if (job === 'error') return `取得できなかった(${lookupError})`;
  return job === null ? '見つからない' : job.status;
}

export async function waitForProvisionedSiteRow(
  page: RowPage,
  siteKey: string,
  options: WaitForProvisionedSiteRowOptions
): Promise<void> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_ROW_TIMEOUT_MS;
  const intervalMs = options.intervalMs ?? DEFAULT_ROW_INTERVAL_MS;
  const deadline = Date.now() + timeoutMs;
  let job: ProvisionedJob | null | 'error' = null;
  let lookupError = '';
  let lastCount = '読めなかった';

  for (;;) {
    await page.goto('/sites');
    const counts = await texts(page, COUNT_SELECTOR);
    if (counts.length > 0) lastCount = counts[0];
    try {
      await page.locator(`tr:has-text("${siteKey}")`).waitFor({ state: 'visible', timeout: ROW_VISIBLE_TIMEOUT_MS });
      return;
    } catch {
      // 行はまだ無い。ジョブの状態を見る。
    }
    try {
      job = await options.findJob(siteKey);
    } catch (error) {
      job = 'error';
      lookupError = error instanceof Error ? error.message : String(error);
    }
    if (job !== null && job !== 'error' && job.status === 'failed') {
      throw new Error(`サイト ${siteKey} の構築ジョブ #${job.id} が failed になった: ${job.error ?? '(error なし)'}`);
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `サイト ${siteKey} の行が ${timeoutMs}ms 経っても一覧に現れなかった。` +
          `ジョブの状態: ${describeJob(job, lookupError)}。一覧の件数表示: ${lastCount}。一覧を読んだユーザー: ${options.readUser}`
      );
    }
    await sleep(intervalMs);
  }
}
