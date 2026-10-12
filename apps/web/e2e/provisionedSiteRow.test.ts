/**
 * @jest-environment node
 *
 * issue #1768: 画面からのサイト自動構築を待つ共有ヘルパー(`./support/provisionedSiteRow`)の単体テスト。
 *
 * - 「構築する」の後、受理メッセージかエラー表示のどちらかが出るまで待ち、エラーなら即座に失敗する。
 * - 一覧の行を待つ間、そのサイトキーのジョブが failed なら時間切れを待たず、ジョブの error つきで失敗する。
 * - 時間切れの失敗メッセージに、ジョブの状態・一覧の件数表示・一覧を読んだユーザーを含める。
 *
 * `e2e/` 直下に置く理由・実行方法は `siteCreationPanel.test.ts` と同じ。実行:
 *
 *   npx jest --config jest.config.ts --testMatch='**\/e2e/provisionedSiteRow.test.ts' e2e/provisionedSiteRow.test.ts
 */
import {
  waitForProvisionedSiteRow,
  waitForProvisioningOutcome,
  type ProvisionedJob,
  type RowPage,
} from './support/provisionedSiteRow';

const FORM_ERROR = 'form:has(input[name="managedSiteKey"]) p.text-red-600';
const FORM_NOTICE = 'form:has(input[name="managedSiteKey"]) p.text-green-600';
const COUNT = 'span:text-matches("全\\\\d+件")';
const ROW = (key: string) => `tr:has-text("${key}")`;

interface FakeOptions {
  errors?: string[];
  notices?: string[];
  /** この回数だけ goto された後に行が現れる。undefined なら現れない。 */
  rowAppearsAfterGotos?: number;
  counts?: string[];
  siteKey?: string;
}

function fakePage(options: FakeOptions) {
  const calls = { goto: 0, selectors: [] as string[] };
  const page: RowPage = {
    goto: async () => {
      calls.goto += 1;
    },
    locator: (selector: string) => {
      calls.selectors.push(selector);
      return {
        waitFor: async () => {
          if (selector === ROW(options.siteKey ?? 'k') && options.rowAppearsAfterGotos !== undefined && calls.goto >= options.rowAppearsAfterGotos) return;
          throw new Error('Timeout exceeded');
        },
        allTextContents: async () => {
          if (selector === FORM_ERROR) return options.errors ?? [];
          if (selector === FORM_NOTICE) return options.notices ?? [];
          if (selector === COUNT) return options.counts ?? [];
          throw new Error(`unexpected selector ${selector}`);
        },
      };
    },
  };
  return { page, calls };
}

const FAST = { timeoutMs: 60, intervalMs: 5 };
const noJob = async (): Promise<ProvisionedJob | null> => null;

describe('waitForProvisioningOutcome', () => {
  it('受理メッセージが出たら戻る', async () => {
    const { page } = fakePage({ notices: ['構築を要求しました。処理キューに追加されました。完了後…'] });
    await expect(waitForProvisioningOutcome(page, FAST)).resolves.toBeUndefined();
  });

  it('エラー表示が出たら、その文言を含むメッセージで即座に失敗する', async () => {
    const { page } = fakePage({ errors: ['サイトキーが重複しています'] });
    await expect(waitForProvisioningOutcome(page, { timeoutMs: 10_000, intervalMs: 5 })).rejects.toThrow(
      /サイトキーが重複しています/
    );
  });

  it('受理メッセージ以外の緑の表示では戻らず、どちらも出なければ時間切れで失敗する', async () => {
    const { page } = fakePage({ notices: ['別の表示'] });
    await expect(waitForProvisioningOutcome(page, FAST)).rejects.toThrow(/受理もエラーも表示されなかった/);
  });

  it('既定の待ち時間・間隔でも動く(受理が最初から出ている)', async () => {
    const { page } = fakePage({ notices: ['構築を要求しました。処理キューに追加されました。'] });
    await expect(waitForProvisioningOutcome(page)).resolves.toBeUndefined();
  });
});

describe('waitForProvisionedSiteRow', () => {
  const base = { readUser: 'admin@example.com' };

  it('行が見えたら戻る', async () => {
    const { page, calls } = fakePage({ rowAppearsAfterGotos: 1 });
    await waitForProvisionedSiteRow(page, 'k', { ...base, findJob: noJob, ...FAST });
    expect(calls.goto).toBe(1);
  });

  it('行が現れるまで一覧を読み込み直す', async () => {
    const { page, calls } = fakePage({ rowAppearsAfterGotos: 3 });
    await waitForProvisionedSiteRow(page, 'k', { ...base, findJob: noJob, ...FAST, timeoutMs: 500 });
    expect(calls.goto).toBe(3);
  });

  it('ジョブが failed になったら、時間切れを待たず、ジョブの error つきで失敗する', async () => {
    const { page } = fakePage({});
    const findJob = async (): Promise<ProvisionedJob | null> => ({ id: 7, status: 'failed', error: 'WordPress の起動に失敗' });
    const started = Date.now();
    await expect(
      waitForProvisionedSiteRow(page, 'k', { ...base, findJob, timeoutMs: 10_000, intervalMs: 5 })
    ).rejects.toThrow(/ジョブ #7[\s\S]*failed[\s\S]*WordPress の起動に失敗/);
    expect(Date.now() - started).toBeLessThan(5_000);
  });

  it('failed で error が無くても失敗する', async () => {
    const { page } = fakePage({});
    const findJob = async (): Promise<ProvisionedJob | null> => ({ id: 8, status: 'failed' });
    await expect(waitForProvisionedSiteRow(page, 'k', { ...base, findJob, ...FAST })).rejects.toThrow(/ジョブ #8/);
  });

  it('時間切れの失敗メッセージに、ジョブの状態・一覧の件数表示・一覧を読んだユーザーを含める', async () => {
    const { page } = fakePage({ counts: ['2件を表示 (全2件中)'] });
    const findJob = async (): Promise<ProvisionedJob | null> => ({ id: 9, status: 'running' });
    const error = await waitForProvisionedSiteRow(page, 'k', { ...base, findJob, ...FAST }).catch((e: Error) => e);
    expect(error).toBeInstanceOf(Error);
    const message = (error as Error).message;
    expect(message).toContain('running');
    expect(message).toContain('2件を表示 (全2件中)');
    expect(message).toContain('admin@example.com');
  });

  it('ジョブが見つからず件数表示も読めなかった場合は、その旨を示す', async () => {
    const { page } = fakePage({});
    const error = await waitForProvisionedSiteRow(page, 'k', { ...base, findJob: noJob, ...FAST }).catch((e: Error) => e);
    const message = (error as Error).message;
    expect(message).toContain('見つからない');
    expect(message).toContain('読めなかった');
  });

  it('ジョブの取得に失敗しても待ちを続け、時間切れのメッセージにその旨を含める', async () => {
    const { page } = fakePage({});
    const findJob = async (): Promise<ProvisionedJob | null> => {
      throw new Error('boom');
    };
    const error = await waitForProvisionedSiteRow(page, 'k', { ...base, findJob, ...FAST }).catch((e: Error) => e);
    expect((error as Error).message).toContain('取得できなかった');
    expect((error as Error).message).toContain('boom');
  });

  it('Error 以外が投げられても、その文字列を時間切れのメッセージに含める', async () => {
    const { page } = fakePage({});
    const findJob = async (): Promise<ProvisionedJob | null> => {
      throw 'plain-string';
    };
    const error = await waitForProvisionedSiteRow(page, 'k', { ...base, findJob, ...FAST }).catch((e: Error) => e);
    expect((error as Error).message).toContain('plain-string');
  });

  it('既定の待ち時間・間隔でも、行が最初から見えていれば戻る', async () => {
    const { page } = fakePage({ rowAppearsAfterGotos: 1 });
    await waitForProvisionedSiteRow(page, 'k', { ...base, findJob: noJob });
  });
});
