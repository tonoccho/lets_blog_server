/**
 * @jest-environment node
 *
 * issue #1705: `/sites` の構築パネル(`#site-creation`)が出ないとき、ステップが
 * シナリオのタイムアウト上限まで待ち続けず、再読み込みを上限回数まで試した後、
 * 画面の取得失敗表示(「〜を取得できませんでした」)つきで失敗する共有ヘルパー
 * (`./support/siteCreationPanel`)の単体テスト。
 *
 * `e2e/` 直下に置く理由・実行方法は `retryClick.test.ts` と同じ(`e2e/support/**` に
 * 置くと bddgen が require して落ちる)。実行:
 *
 *   npx jest --config jest.config.ts --testMatch='**\/e2e/siteCreationPanel.test.ts' e2e/siteCreationPanel.test.ts
 */
import { waitForSiteCreationPanel, type PanelPage } from './support/siteCreationPanel';

interface FakeOptions {
  /** この回数だけ reload された後にパネルが現れる。undefined なら現れない。 */
  appearsAfterReloads?: number;
  alertTexts?: string[];
}

function fakePage(options: FakeOptions) {
  const calls = { reload: 0, waitFor: 0, timeouts: [] as number[] };
  const page: PanelPage = {
    locator: (selector: string) => ({
      waitFor: async (opts: { state: 'visible'; timeout: number }) => {
        if (selector !== 'id=site-creation') throw new Error(`unexpected selector ${selector}`);
        calls.waitFor += 1;
        calls.timeouts.push(opts.timeout);
        if (options.appearsAfterReloads !== undefined && calls.reload >= options.appearsAfterReloads) return;
        throw new Error('Timeout exceeded');
      },
      allTextContents: async () => options.alertTexts ?? [],
    }),
    reload: async () => {
      calls.reload += 1;
    },
  };
  return { page, calls };
}

describe('waitForSiteCreationPanel', () => {
  it('パネルが最初から見えていれば再読み込みしない', async () => {
    const { page, calls } = fakePage({ appearsAfterReloads: 0 });
    await waitForSiteCreationPanel(page, { panelTimeoutMs: 10, maxReloads: 3 });
    expect(calls.reload).toBe(0);
    expect(calls.waitFor).toBe(1);
  });

  it('パネルが出ないときは上限回数だけ再読み込みし、取得失敗の表示を含むエラーで失敗する', async () => {
    const { page, calls } = fakePage({ alertTexts: ['サイトを取得できませんでした。時間をおいて再読み込みしてください。'] });
    await expect(waitForSiteCreationPanel(page, { panelTimeoutMs: 10, maxReloads: 2 })).rejects.toThrow(
      /サイトを取得できませんでした/
    );
    expect(calls.reload).toBe(2);
    expect(calls.waitFor).toBe(3);
    expect(calls.timeouts.every((t) => t === 10)).toBe(true);
  });

  it('取得失敗の表示が無いときもエラーで失敗し、表示が無かったことを示す', async () => {
    const { page } = fakePage({});
    await expect(waitForSiteCreationPanel(page, { panelTimeoutMs: 10, maxReloads: 1 })).rejects.toThrow(
      /取得失敗の表示は見つからなかった/
    );
  });

  it('再読み込みでパネルが現れれば成功する', async () => {
    const { page, calls } = fakePage({ appearsAfterReloads: 1 });
    await waitForSiteCreationPanel(page, { panelTimeoutMs: 10, maxReloads: 3 });
    expect(calls.reload).toBe(1);
  });

  it('既定値を使える(最初から見えていれば即座に成功する)', async () => {
    const { page, calls } = fakePage({ appearsAfterReloads: 0 });
    await waitForSiteCreationPanel(page);
    expect(calls.timeouts).toEqual([30_000]);
  });
});
