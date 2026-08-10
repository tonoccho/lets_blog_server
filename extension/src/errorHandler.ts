import * as vscode from 'vscode';
import { logger } from './logger';

/**
 * APIサーバーがエラーステータスを返した場合の例外。
 * ステータスコードとレスポンス本文を保持し、原因と対応策を含むメッセージを組み立てられるようにする。
 */
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly responseBody: string,
    public readonly url: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** fetchが失敗した(サーバーへ到達できなかった)場合の例外。リトライ対象。 */
export class NetworkError extends Error {
  constructor(
    message: string,
    public readonly url: string,
    public readonly cause: unknown
  ) {
    super(message);
    this.name = 'NetworkError';
  }
}

/**
 * APIレスポンスがスキーマ検証を通らなかった場合の例外。
 * 再試行しても同じ結果になるためリトライ対象にはしない。
 */
export class ResponseValidationError extends Error {
  constructor(
    message: string,
    public readonly url: string,
    /** どのフィールドがどう不正だったかの一覧(例: "sources.0.url: Invalid input")。 */
    public readonly issues: string[]
  ) {
    super(message);
    this.name = 'ResponseValidationError';
  }
}

/**
 * 利用者が操作を中断した場合の例外。失敗ではないため、通知やリトライの対象にしない。
 */
export class CancelledError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CancelledError';
  }
}

/** リクエストが所定時間内に完了しなかった場合の例外。リトライ対象。 */
export class TimeoutError extends Error {
  constructor(
    message: string,
    public readonly url: string,
    public readonly timeoutMs: number
  ) {
    super(message);
    this.name = 'TimeoutError';
  }
}

/**
 * ステータスコードごとの原因説明と対応策。「APIエラー (500)」だけでは利用者が
 * 次に何をすればよいか判断できないため、代表的なステータスに具体的な対応策を添える。
 */
const STATUS_GUIDANCE: Record<number, { cause: string; remedy: string }> = {
  400: { cause: 'リクエスト内容がサーバーに受け付けられませんでした。', remedy: 'front matterの項目(title/slug/categories等)に不正な値がないか確認してください。' },
  401: { cause: 'APIキーが無効か期限切れです。', remedy: '「Let\'s Blog: Login」で再ログインするか、「Let\'s Blog: Set API Key」でAPIキーを再設定してください。' },
  403: { cause: 'この操作を行う権限がありません。', remedy: '対象プロジェクトのメンバーに追加されているか、管理者に確認してください。' },
  404: { cause: '対象のリソースが見つかりませんでした。', remedy: 'プロジェクト/サイト/Issueが削除されていないか、選択中のプロジェクトが正しいか確認してください。' },
  408: { cause: 'サーバーがリクエストをタイムアウトしました。', remedy: '時間をおいて再実行してください。' },
  409: { cause: '対象リソースの状態が競合しています。', remedy: '他の端末や画面から同じリソースを更新していないか確認してください。' },
  413: { cause: '送信データがサーバーの上限を超えています。', remedy: '同梱する画像の枚数やサイズを減らしてください。' },
  429: { cause: 'リクエストが多すぎるため、サーバーに一時的に拒否されました。', remedy: 'しばらく待ってから再実行してください。' },
  500: { cause: 'サーバー内部でエラーが発生しました。', remedy: 'しばらく待ってから再実行し、解消しない場合はサーバーのログを確認してください。' },
  502: { cause: 'リバースプロキシがAPIサーバーへ到達できませんでした。', remedy: 'APIサーバーのコンテナが起動しているか確認してください。' },
  503: { cause: 'サーバーが一時的に利用できません。', remedy: 'サーバーの起動完了を待ってから再実行してください。' },
  504: { cause: '上流サーバーの応答がタイムアウトしました。', remedy: 'AI生成など時間のかかる処理の場合は、条件を軽くして再実行してください。' },
};

/**
 * 例外を、原因と対応策を含む利用者向けメッセージへ変換する。
 * どの例外型でも「何が起きたか」「次に何をすべきか」が読み取れる文面にする。
 */
export function describeError(error: unknown): string {
  if (error instanceof CancelledError) {
    return '操作をキャンセルしました。';
  }
  if (error instanceof ApiError) {
    const guidance = STATUS_GUIDANCE[error.status];
    const detail = error.responseBody.trim();
    const parts = [`APIエラー (${error.status})`];
    if (guidance) {
      parts.push(guidance.cause, `対応: ${guidance.remedy}`);
    }
    if (detail) {
      parts.push(`サーバーからの応答: ${truncate(detail, 500)}`);
    }
    return parts.join(' ');
  }
  if (error instanceof ResponseValidationError) {
    return (
      `サーバーの応答が想定した形式ではありませんでした (${error.url})。` +
      ` 不一致: ${error.issues.slice(0, 5).join(' / ')}` +
      ' 対応: APIサーバーと拡張のバージョンが対応しているか確認してください。' +
      ' letsBlog.debugModeを有効にすると応答内容をログで確認できます。'
    );
  }
  if (error instanceof TimeoutError) {
    return (
      `サーバーからの応答が ${Math.round(error.timeoutMs / 1000)} 秒以内に返りませんでした。` +
      ' 対応: サーバーが起動しているか、letsBlog.serverUrlの設定が正しいか確認してください。' +
      ' AI生成など時間のかかる処理はletsBlog.requestTimeoutMsを延ばしてください。'
    );
  }
  if (error instanceof NetworkError) {
    return (
      `サーバー(${error.url})へ接続できませんでした: ${messageOf(error.cause)}` +
      ' 対応: letsBlog.serverUrlの設定値、サーバーの起動状態、証明書の設定(letsBlog.allowInsecureTls)を確認してください。'
    );
  }
  return messageOf(error);
}

/** 例外からメッセージ文字列を取り出す(Error以外もそのまま文字列化する)。 */
export function messageOf(error: unknown): string {
  return String(error instanceof Error ? error.message : error);
}

/**
 * 例外をログへ記録し、対応策付きのエラーメッセージを通知する。
 * 詳細(スタックトレース等)は通知に載せずログへ送り、必要な人だけが辿れるようにする。
 */
export function reportError(prefix: string, error: unknown): void {
  const description = describeError(error);
  logger.error(`${prefix}: ${description}`, { stack: error instanceof Error ? error.stack : undefined });
  void vscode.window.showErrorMessage(`${prefix}: ${description}`, 'ログを表示').then((selection) => {
    if (selection === 'ログを表示') {
      logger.show();
    }
  });
}

export interface RetryOptions {
  /** 初回実行の後に行う再試行の最大回数。 */
  maxRetries?: number;
  /** 1回目の再試行までの待機時間(ミリ秒)。以降は指数的に倍増する。 */
  initialDelayMs?: number;
  /** 待機時間の上限(ミリ秒)。 */
  maxDelayMs?: number;
  /** ログ出力時に処理を識別するラベル。 */
  label?: string;
  /** テスト用の待機関数の差し替え口。 */
  sleep?: (ms: number) => Promise<void>;
}

const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_INITIAL_DELAY_MS = 500;
const DEFAULT_MAX_DELAY_MS = 8000;

/**
 * 一時的な失敗(ネットワーク断・タイムアウト・5xx・429)に限り、指数バックオフで再試行する。
 * 400番台のような再試行しても結果が変わらないエラーは即座に投げ直す。
 */
export async function withRetry<T>(operation: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
  const initialDelayMs = options.initialDelayMs ?? DEFAULT_INITIAL_DELAY_MS;
  const maxDelayMs = options.maxDelayMs ?? DEFAULT_MAX_DELAY_MS;
  const sleep = options.sleep ?? defaultSleep;
  const label = options.label ?? 'request';

  let attempt = 0;
  for (;;) {
    try {
      return await operation();
    } catch (error) {
      if (attempt >= maxRetries || !isRetryable(error)) {
        throw error;
      }
      const delayMs = Math.min(initialDelayMs * 2 ** attempt, maxDelayMs);
      attempt += 1;
      logger.warn(`${label} failed, retrying (${attempt}/${maxRetries}) in ${delayMs}ms`, {
        reason: messageOf(error),
      });
      await sleep(delayMs);
    }
  }
}

/** 再試行しても結果が変わりうる(一時的な)失敗かどうかを判定する。 */
export function isRetryable(error: unknown): boolean {
  if (error instanceof NetworkError || error instanceof TimeoutError) {
    return true;
  }
  if (error instanceof ApiError) {
    return error.status === 429 || error.status >= 500;
  }
  return false;
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function truncate(text: string, maxLength: number): string {
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}…`;
}
