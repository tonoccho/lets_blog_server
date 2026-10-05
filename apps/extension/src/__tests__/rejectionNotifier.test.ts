import * as fs from 'fs';
import * as path from 'path';
import {
  checkRejections,
  DEFAULT_POLL_INTERVAL_MS,
  formatRejectionMessage,
  MyReview,
  NOTIFIED_REJECTIONS_STATE,
  notifiedKey,
  RejectionCheckDeps,
  RejectionPoller,
  resolvePollIntervalMs,
} from '../rejectionNotifier';

/** issue #1347: 差し戻しの検知と通知(vscodeにもサーバーにも依存しない層)。 */

const rejected = (over: Partial<MyReview> = {}): MyReview => ({
  prNumber: 7,
  articleSlug: 'my-post',
  state: 'CHANGES_REQUESTED',
  submittedAt: '2026-10-01T00:00:00Z',
  rejectComment: '見出しを直してください',
  rejectedAt: '2026-10-02T03:04:05Z',
  ...over,
});

function makeDeps(over: Partial<RejectionCheckDeps> & { reviews?: MyReview[] } = {}) {
  const stored = new Map<string, unknown>();
  const notices: string[] = [];
  const errors: string[] = [];
  const fetchCalls: number[] = [];
  const deps: RejectionCheckDeps = {
    getProjectId: () => 42,
    getAccessToken: async () => 'token',
    getActor: async () => ({ email: 'a@example.test', role: 'WRITER' }),
    fetchMyReviews: async (_t, _a, projectId) => {
      fetchCalls.push(projectId);
      return over.reviews ?? [rejected()];
    },
    state: {
      get: (key) => stored.get(key) as string[] | undefined,
      update: async (key, value) => void stored.set(key, value),
    },
    notify: (message) => void notices.push(message),
    reportError: (message) => void errors.push(message),
    ...over,
  };
  return { deps, stored, notices, errors, fetchCalls };
}

describe('formatRejectionMessage', () => {
  it('記事のスラッグと指摘事項を含む', () => {
    const message = formatRejectionMessage(rejected());
    expect(message).toContain('my-post');
    expect(message).toContain('見出しを直してください');
    expect(message).toContain('#7');
  });

  it('指摘コメントが取得できない(null)場合はその旨を示す', () => {
    expect(formatRejectionMessage(rejected({ rejectComment: null }))).toContain('取得できません');
  });
});

describe('notifiedKey', () => {
  it('prNumberとrejectedAtで識別する', () => {
    expect(notifiedKey(rejected())).toBe('7|2026-10-02T03:04:05.000Z');
    expect(notifiedKey(rejected({ rejectedAt: '2026-10-03T00:00:00Z' }))).not.toBe(notifiedKey(rejected()));
  });

  it('オフセットの有無・表記の違いに関わらず同じ時刻は同じキーになる(#1627)', () => {
    const legacy = notifiedKey(rejected({ rejectedAt: '2026-10-02T03:04:05' }));
    expect(legacy).toBe(notifiedKey(rejected()));
    expect(notifiedKey(rejected({ rejectedAt: '2026-10-02T12:04:05+09:00' }))).toBe(legacy);
    expect(notifiedKey(rejected({ rejectedAt: '2026-10-02T03:04:05.000Z' }))).toBe(legacy);
  });

  it('時刻として解釈できない値はそのままキーにする', () => {
    expect(notifiedKey(rejected({ rejectedAt: 'not-a-date' }))).toBe('7|not-a-date');
  });
});

describe('checkRejections', () => {
  it('新たに差し戻された記事を通知し、通知済みとして記録する', async () => {
    const { deps, notices, stored } = makeDeps();
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'checked', notified: 1 });
    expect(notices).toHaveLength(1);
    expect(notices[0]).toContain('見出しを直してください');
    expect(stored.get(NOTIFIED_REJECTIONS_STATE)).toEqual(['7|2026-10-02T03:04:05.000Z']);
  });

  it('2回目の確認では同じ差し戻しを通知しない', async () => {
    const { deps, notices } = makeDeps();
    await checkRejections(deps);
    const second = await checkRejections(deps);
    expect(second).toEqual({ status: 'checked', notified: 0 });
    expect(notices).toHaveLength(1);
  });

  it('旧形式(オフセットなし)で保存済みのキーがあれば、Z終端の同じ差し戻しを再通知しない(#1627)', async () => {
    const { deps, notices, stored } = makeDeps();
    stored.set(NOTIFIED_REJECTIONS_STATE, ['7|2026-10-02T03:04:05']);
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'checked', notified: 0 });
    expect(notices).toHaveLength(0);
  });

  it('旧形式のキーが残っていても、別の差し戻しは通知し、保存するキーは正規化する(#1627)', async () => {
    const { deps, notices, stored } = makeDeps({ reviews: [rejected({ prNumber: 8 })] });
    stored.set(NOTIFIED_REJECTIONS_STATE, ['7|2026-10-02T03:04:05']);
    await checkRejections(deps);
    expect(notices).toHaveLength(1);
    expect(stored.get(NOTIFIED_REJECTIONS_STATE)).toEqual(['7|2026-10-02T03:04:05.000Z', '8|2026-10-02T03:04:05.000Z']);
  });

  it('区切りの無い壊れた保存済みキーがあっても落ちず、そのまま保持する(#1627)', async () => {
    const { deps, notices, stored } = makeDeps();
    stored.set(NOTIFIED_REJECTIONS_STATE, ['broken-key']);
    await checkRejections(deps);
    expect(notices).toHaveLength(1);
    expect(stored.get(NOTIFIED_REJECTIONS_STATE)).toEqual(['broken-key', '7|2026-10-02T03:04:05.000Z']);
  });

  it('同じ記事が再度差し戻されたとき(rejectedAtが違う)は再び通知する', async () => {
    let reviews = [rejected()];
    const { deps, notices } = makeDeps({ fetchMyReviews: async () => reviews });
    await checkRejections(deps);
    reviews = [rejected({ rejectedAt: '2026-10-05T00:00:00Z' })];
    await checkRejections(deps);
    expect(notices).toHaveLength(2);
  });

  it('差し戻し以外の状態と、rejectedAtが無い行は通知しない', async () => {
    const { deps, notices } = makeDeps({
      reviews: [
        rejected({ state: 'SUBMITTED', rejectedAt: null, rejectComment: null }),
        rejected({ prNumber: 8, state: 'IN_REVIEW', rejectedAt: null }),
        rejected({ prNumber: 9, state: 'PUBLISHED', rejectedAt: null }),
        rejected({ prNumber: 10, rejectedAt: undefined }),
      ],
    });
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'checked', notified: 0 });
    expect(notices).toEqual([]);
  });

  it('プロジェクト未選択なら確認もエラー通知もしない', async () => {
    const { deps, fetchCalls, errors, notices } = makeDeps({ getProjectId: () => undefined });
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'skipped', reason: 'no-project' });
    expect(fetchCalls).toEqual([]);
    expect(errors).toEqual([]);
    expect(notices).toEqual([]);
  });

  it('未ログインなら確認もエラー通知もしない', async () => {
    const { deps, fetchCalls, errors } = makeDeps({ getAccessToken: async () => undefined });
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'skipped', reason: 'not-logged-in' });
    expect(fetchCalls).toEqual([]);
    expect(errors).toEqual([]);
  });

  it('取得に失敗しても自動確認ではエラー通知を出さない', async () => {
    const { deps, errors } = makeDeps({
      fetchMyReviews: async () => {
        throw new Error('boom');
      },
    });
    const outcome = await checkRejections(deps);
    expect(outcome).toEqual({ status: 'failed' });
    expect(errors).toEqual([]);
  });

  it('取得に失敗したとき、手動確認ではエラーを通知する', async () => {
    const { deps, errors } = makeDeps({
      fetchMyReviews: async () => {
        throw new Error('boom');
      },
    });
    await checkRejections(deps, { manual: true });
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('boom');
  });

  it('通知済みの記録は直近200件に切り詰める', async () => {
    const many = Array.from({ length: 205 }, (_, i) => rejected({ prNumber: i + 1 }));
    const { deps, stored } = makeDeps({ reviews: many });
    await checkRejections(deps);
    const keys = stored.get(NOTIFIED_REJECTIONS_STATE) as string[];
    expect(keys).toHaveLength(200);
    expect(keys[199]).toBe('205|2026-10-02T03:04:05.000Z');
  });
});

describe('resolvePollIntervalMs', () => {
  it('正の数はそのまま使う', () => {
    expect(resolvePollIntervalMs(60000)).toBe(60000);
  });
  it.each([undefined, 0, -5, NaN, Infinity, 'x' as unknown as number])('不正な値(%p)は既定の5分にする', (value) => {
    expect(resolvePollIntervalMs(value)).toBe(DEFAULT_POLL_INTERVAL_MS);
  });
  it('package.jsonが下限として宣言する10000未満は、10000へ引き上げる', () => {
    expect(resolvePollIntervalMs(1)).toBe(10000);
    expect(resolvePollIntervalMs(9999)).toBe(10000);
    expect(resolvePollIntervalMs(10000)).toBe(10000);
  });
  it('既定は300000ミリ秒', () => {
    expect(DEFAULT_POLL_INTERVAL_MS).toBe(300000);
  });
});

describe('RejectionPoller', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('開始時に1回、その後は設定間隔ごとに確認する', () => {
    const run = jest.fn().mockResolvedValue(undefined);
    const poller = new RejectionPoller(run, () => 1000);
    poller.start();
    expect(run).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(3000);
    expect(run).toHaveBeenCalledTimes(4);
    poller.dispose();
  });

  it('disposeすると以後は確認しない', () => {
    const run = jest.fn().mockResolvedValue(undefined);
    const poller = new RejectionPoller(run, () => 1000);
    poller.start();
    poller.dispose();
    jest.advanceTimersByTime(5000);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('restartは新しい間隔でタイマーを張り直し、即時確認はしない', () => {
    let interval = 1000;
    const run = jest.fn().mockResolvedValue(undefined);
    const poller = new RejectionPoller(run, () => interval);
    poller.start();
    interval = 5000;
    poller.restart();
    expect(run).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(4999);
    expect(run).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(1);
    expect(run).toHaveBeenCalledTimes(2);
    poller.dispose();
  });

  it('確認が例外を投げても定期確認は止まらない', async () => {
    const run = jest.fn().mockRejectedValue(new Error('x'));
    const poller = new RejectionPoller(run, () => 1000);
    poller.start();
    jest.advanceTimersByTime(2000);
    expect(run).toHaveBeenCalledTimes(3);
    poller.dispose();
  });
});

describe('差し戻し確認コマンドの登録(issue #1347)', () => {
  it('package.jsonのコマンドIDが、extension.tsでregisterCommandしている文字列と一致する', () => {
    const root = path.join(__dirname, '..', '..');
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf-8'));
    const source = fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf-8');
    const declared = manifest.contributes.commands.map((c: { command: string }) => c.command);
    expect(declared).toContain('letsBlog.checkRejections');
    expect(source).toContain("registerCommand('letsBlog.checkRejections'");
  });
});
