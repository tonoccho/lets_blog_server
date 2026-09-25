import * as vscode from 'vscode';
import { logger } from './logger';
import { downstreamServiceFor, GATEWAY, pathOf } from './downstreamServices';

/**
 * APIサーバーがエラーステータスを返した場合の例外。
 * ステータスコードとレスポンス本文を保持し、原因と対応策を含むメッセージを組み立てられるようにする。
 */
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly responseBody: string,
    public readonly url: string,
    /**
     * gatewayが応答へ付与した相関ID(X-Correlation-Id、issue #582)。
     * 各サービスのログをこのIDで横断的に追えるため、下流サービス障害時の調査手掛かりとして
     * 利用者へ提示する(issue #585)。取得できなかった場合はundefined。
     */
    public readonly correlationId?: string
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
  401: { cause: 'アクセストークンが無効か期限切れです。', remedy: '「Let\'s Blog: Login」で再ログインしてください。' },
  403: { cause: 'この操作を行う権限がありません。', remedy: '対象プロジェクトのメンバーに追加されているか、管理者に確認してください。' },
  404: { cause: '対象のリソースが見つかりませんでした。', remedy: 'プロジェクト/サイト/Issueが削除されていないか、選択中のプロジェクトが正しいか確認してください。' },
  408: { cause: 'サーバーがリクエストをタイムアウトしました。', remedy: '時間をおいて再実行してください。' },
  409: { cause: '対象リソースの状態が競合しています。', remedy: '他の端末や画面から同じリソースを更新していないか確認してください。' },
  413: { cause: '送信データがサーバーの上限を超えています。', remedy: '同梱する画像の枚数やサイズを減らしてください。' },
  429: { cause: 'リクエストが多すぎるため、サーバーに一時的に拒否されました。', remedy: 'しばらく待ってから再実行してください。' },
  500: { cause: 'サーバー内部でエラーが発生しました。', remedy: 'しばらく待ってから再実行し、解消しない場合はサーバーのログを確認してください。' },
  502: { cause: 'gatewayが担当サービスへ到達できませんでした。', remedy: '担当サービスのコンテナが起動しているか確認してください。' },
  503: { cause: '担当サービスが一時的に利用できません。', remedy: '担当サービスの起動完了を待ってから再実行してください。' },
  504: { cause: 'gatewayから見て担当サービスの応答がタイムアウトしました。', remedy: 'AI生成など時間のかかる処理の場合は、条件を軽くして再実行してください。' },
};

/**
 * 「どのサービスが失敗したか」を伝える一文を組み立てる(issue #585)。
 *
 * サービス分割(Epic #551)後、拡張が受け取る5xxや接続失敗は個々の下流サービスの障害であることが
 * ほとんどだが、gatewayは応答にサービス名を載せない。そこでリクエストパスからgatewayの
 * ルート表を逆引きし、コンテナ名(`docker logs lbs-<id>`で辿れる)を添えて提示する。
 */
function describeResponsibleService(url: string, reached = false): string {
  const service = downstreamServiceFor(url);
  const identity = `担当サービス: ${service.label} (コンテナ: lbs-${service.id}、パス: ${pathOf(url)})。`;
  // 応答が返っている場合、コンテナは起動しており到達もできている。ログ確認は補助に留める。
  return reached
    ? `${identity} 補足: 必要に応じて「docker logs lbs-${service.id}」でそのサービスのログも確認できます。`
    : `${identity} 対応: 「docker logs lbs-${service.id}」でそのサービスのログを確認してください。`;
}

/** 相関IDが取れている場合に、ログ横断検索の手掛かりとして添える一文。 */
function describeCorrelationId(correlationId: string | undefined): string | undefined {
  return correlationId ? `相関ID: ${correlationId}(全サービスのログをこのIDで追えます)。` : undefined;
}

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
    // 502で応答本文がある場合、gatewayは担当サービスへ到達済みで、担当サービスが上流(外部サービス)の
    // 失敗を報告している(issue #1082)。「到達できなかった」と断定せず、原因を持つ応答本文を先頭に置く。
    const upstreamFailure = error.status === 502 && detail !== '';
    if (upstreamFailure) {
      parts.push(
        `サーバーからの応答: ${truncate(detail, 500)}`,
        'gatewayは担当サービスへ到達できましたが、担当サービスの上流(外部サービス)が失敗しました。',
        '対応: 上記の応答内容に沿って、上流サービスの状態や設定を確認してください。'
      );
    } else if (guidance) {
      parts.push(guidance.cause, `対応: ${guidance.remedy}`);
    }
    // 5xxは下流サービス側の障害(gatewayは応答をそのまま中継する)。どのサービスを見ればよいかを
    // 示さないと利用者はサービス分割後の構成から当たりを付けられないため、ここで明示する。
    // 4xxは利用者の操作・入力に起因することが大半で、サービス名を出すとむしろ誤誘導になるため付けない。
    if (error.status >= 500) {
      parts.push(describeResponsibleService(error.url, upstreamFailure));
      const correlation = describeCorrelationId(error.correlationId);
      if (correlation) {
        parts.push(correlation);
      }
    }
    if (detail && !upstreamFailure) {
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
    // 応答自体が返っていないため、gatewayが遅いのか担当サービスが遅いのかは拡張からは区別できない。
    // ただし「この処理を担当するのはどのサービスか」は分かるため、調査の起点として示す。
    return (
      `サーバーからの応答が ${Math.round(error.timeoutMs / 1000)} 秒以内に返りませんでした。` +
      ` ${describeResponsibleService(error.url)}` +
      ' 対応: サーバーが起動しているか、letsBlog.serverUrlの設定が正しいか確認してください。' +
      ' AI生成など時間のかかる処理はletsBlog.requestTimeoutMsを延ばしてください。'
    );
  }
  if (error instanceof NetworkError) {
    // 接続そのものが確立できていないため、失敗しているのは個々の下流サービスではなく
    // リバースプロキシ/gatewayへの到達性。担当サービス名を出すと誤誘導になるので出さない。
    return (
      `サーバー(${error.url})へ接続できませんでした: ${messageOf(error.cause)}` +
      ` この段階では ${GATEWAY.label}(コンテナ: lbs-${GATEWAY.id})やリバースプロキシへ届いていないため、` +
      '個々の下流サービスではなく到達経路の問題です。' +
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

/** withRetryの挙動を調整するオプション。 */
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
