/**
 * issue #1705: `/sites` の構築パネル(`id="site-creation"`)を待つ共有ヘルパー。
 *
 * `apps/web/src/app/sites/page.tsx` は、サイト・プロジェクト・ユーザー・SSH 鍵の取得の
 * どれかが失敗すると構築パネルを描画しない(代わりに `FetchErrorNotice` を出す)。
 * 取得失敗(例: gateway の 429、#1704)はパネルが「来ない」形でしか見えず、
 * `scrollIntoViewIfNeeded()` のままではシナリオのタイムアウト上限(15〜30 分)まで待つ。
 *
 * ここでは数十秒ごとに `/sites` を再読み込みして上限回数まで再試行し、それでも出なければ
 * 画面の取得失敗の表示を含むエラーで即座に失敗する。時間・回数は注入できる。
 */
export const DEFAULT_PANEL_TIMEOUT_MS = 30_000;
export const DEFAULT_MAX_RELOADS = 3;

/** Playwright の `Page` / `Locator` のうち、このヘルパーが使う部分だけ。 */
export interface PanelLocator {
  waitFor(options: { state: 'visible'; timeout: number }): Promise<unknown>;
  allTextContents(): Promise<string[]>;
}
export interface PanelPage {
  locator(selector: string): PanelLocator;
  reload(): Promise<unknown>;
}

export interface WaitForSiteCreationPanelOptions {
  /** 1回の待ちの上限(ms)。 */
  panelTimeoutMs?: number;
  /** 再読み込みの上限回数。待ちは最大で `maxReloads + 1` 回。 */
  maxReloads?: number;
}

export async function waitForSiteCreationPanel(
  page: PanelPage,
  options?: WaitForSiteCreationPanelOptions
): Promise<void> {
  const panelTimeoutMs = options?.panelTimeoutMs ?? DEFAULT_PANEL_TIMEOUT_MS;
  const maxReloads = options?.maxReloads ?? DEFAULT_MAX_RELOADS;
  const panel = page.locator('id=site-creation');

  for (let reloads = 0; ; reloads += 1) {
    try {
      await panel.waitFor({ state: 'visible', timeout: panelTimeoutMs });
      return;
    } catch (error) {
      if (reloads >= maxReloads) {
        const notices = (await page.locator('[role="alert"]').allTextContents().catch(() => [] as string[]))
          .map((text) => text.trim())
          .filter((text) => text.length > 0);
        const detail = notices.length > 0 ? notices.join(' / ') : '取得失敗の表示は見つからなかった';
        throw new Error(
          `構築パネル(#site-creation)が ${maxReloads} 回の再読み込み後も表示されなかった。画面の表示: ${detail}`,
          { cause: error }
        );
      }
      await page.reload();
    }
  }
}
