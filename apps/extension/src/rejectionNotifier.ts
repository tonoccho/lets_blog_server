import type { Actor } from './schemas';
import type { MyArticleReview } from './schemas';
import { messageOf } from './errorHandler';
import { logger } from './logger';

/**
 * 差し戻しの検知と通知(issue #1347)。
 *
 * サーバーに通知基盤は作らず、拡張が `my-reviews`(#1344)を定期的に確認する(利用者決定 2026-09-17)。
 * このファイルは `vscode` に依存しない。VSCode APIとの接続(通知表示・設定・状態保存)は
 * 呼び出し側(extension.ts)が `RejectionCheckDeps` として渡す。
 */

export type MyReview = MyArticleReview;

/** 通知済みの差し戻しキー(`prNumber|rejectedAt`)を `context.globalState` へ保存するキー。 */
export const NOTIFIED_REJECTIONS_STATE = 'letsBlog.notifiedRejections';

/** 設定 `letsBlog.rejectionPollIntervalMs` の既定値(5分)。 */
export const DEFAULT_POLL_INTERVAL_MS = 300000;

/** 通知済みキーの保持件数の上限。古いものから捨てる。 */
const MAX_NOTIFIED_KEYS = 200;

export interface RejectionCheckDeps {
  getProjectId: () => number | undefined;
  /** 未ログイン・更新失敗のときは例外を投げずundefinedを返す(config.getAccessToken)。 */
  getAccessToken: () => Promise<string | undefined>;
  getActor: () => Promise<Actor | undefined>;
  fetchMyReviews: (apiKey: string, actor: Actor | undefined, projectId: number) => Promise<MyReview[]>;
  state: {
    get: (key: string) => string[] | undefined;
    update: (key: string, value: string[]) => PromiseLike<void>;
  };
  notify: (message: string, review: MyReview) => void;
  reportError: (message: string) => void;
}

export type CheckOutcome =
  | { status: 'skipped'; reason: 'no-project' | 'not-logged-in' }
  | { status: 'failed' }
  | { status: 'checked'; notified: number };

/** 同じ差し戻しかどうかを識別するキー。再度差し戻されると `rejectedAt` が変わり、別の差し戻しになる。 */
export function notifiedKey(review: MyReview): string {
  return `${review.prNumber}|${normalizeTimestamp(String(review.rejectedAt))}`;
}

const NO_OFFSET = /^\d{4}-\d{2}-\d{2}T[\d:.]+$/;

/**
 * 時刻を実時刻のISO 8601(UTC, Z終端)へ揃える。オフセットの無い旧形式(#1611前)はUTCとして扱う。
 * 時刻として解釈できない値はそのまま返す。
 */
function normalizeTimestamp(value: string): string {
  const ms = Date.parse(NO_OFFSET.test(value) ? `${value}Z` : value);
  return Number.isNaN(ms) ? value : new Date(ms).toISOString();
}

/** 保存済みの `prNumber|rejectedAt` キーを、現行の形式へ揃える(旧形式のキーとも一致させる)。 */
function normalizeKey(key: string): string {
  const sep = key.indexOf('|');
  return sep < 0 ? key : `${key.slice(0, sep)}|${normalizeTimestamp(key.slice(sep + 1))}`;
}

/**
 * 通知文。サーバーの応答(MyArticleReviewResponse)に記事タイトルは含まれないため、
 * 記事のスラッグを記事の名前として表示する(Issue #1347 で決定)。
 */
export function formatRejectionMessage(review: MyReview): string {
  const comment = review.rejectComment ?? '(指摘コメントを取得できません。Pull Requestを確認してください)';
  return `記事「${review.articleSlug}」(PR #${review.prNumber})が差し戻されました。指摘事項: ${comment}`;
}

/**
 * `my-reviews` を確認し、まだ通知していない差し戻しを通知する。
 * 未ログイン・プロジェクト未選択のときは何も確認せず、エラーも通知しない。
 * 取得に失敗したときは、手動確認のときだけエラーを通知する(定期確認では利用者を邪魔しない)。
 */
export async function checkRejections(
  deps: RejectionCheckDeps,
  options: { manual?: boolean } = {}
): Promise<CheckOutcome> {
  const projectId = deps.getProjectId();
  if (projectId == null) {
    return { status: 'skipped', reason: 'no-project' };
  }
  const apiKey = await deps.getAccessToken();
  if (!apiKey) {
    return { status: 'skipped', reason: 'not-logged-in' };
  }

  let reviews: MyReview[];
  try {
    reviews = await deps.fetchMyReviews(apiKey, await deps.getActor(), projectId);
  } catch (error) {
    logger.warn('差し戻しの確認に失敗しました。', { reason: messageOf(error) });
    if (options.manual) {
      deps.reportError(`差し戻しの確認に失敗しました。${messageOf(error)}`);
    }
    return { status: 'failed' };
  }

  const known = (deps.state.get(NOTIFIED_REJECTIONS_STATE) ?? []).map(normalizeKey);
  const fresh = reviews.filter(
    (review) =>
      review.state === 'CHANGES_REQUESTED' && review.rejectedAt != null && !known.includes(notifiedKey(review))
  );
  for (const review of fresh) {
    deps.notify(formatRejectionMessage(review), review);
  }
  if (fresh.length > 0) {
    await deps.state.update(
      NOTIFIED_REJECTIONS_STATE,
      [...known, ...fresh.map(notifiedKey)].slice(-MAX_NOTIFIED_KEYS)
    );
  }
  return { status: 'checked', notified: fresh.length };
}

/** 確認間隔の下限(ミリ秒)。package.jsonの `minimum` と揃える(settings.jsonへ直接書かれた値への備え)。 */
export const MIN_POLL_INTERVAL_MS = 10000;

/** 設定値を確認間隔(ミリ秒)へ。正の有限数以外は既定値にし、下限未満は下限へ引き上げる。 */
export function resolvePollIntervalMs(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return DEFAULT_POLL_INTERVAL_MS;
  }
  return Math.max(value, MIN_POLL_INTERVAL_MS);
}

/** 起動時に1回確認し、以後は設定間隔ごとに確認する。`dispose()` で止まる(context.subscriptionsへ登録する)。 */
export class RejectionPoller {
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor(
    private readonly run: () => Promise<unknown>,
    private readonly getIntervalMs: () => number
  ) {}

  start(): void {
    this.tick();
    this.schedule();
  }

  /** 設定変更時に新しい間隔でタイマーを張り直す。即時の確認はしない。 */
  restart(): void {
    this.clear();
    this.schedule();
  }

  dispose(): void {
    this.clear();
  }

  private schedule(): void {
    this.timer = setInterval(() => this.tick(), this.getIntervalMs());
  }

  private clear(): void {
    if (this.timer !== undefined) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  private tick(): void {
    this.run().catch((error) => logger.warn('差し戻しの定期確認で例外が発生しました。', { reason: messageOf(error) }));
  }
}
