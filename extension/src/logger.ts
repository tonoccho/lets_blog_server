import * as vscode from 'vscode';

/** ログレベル。数値が大きいほど重大度が高い(閾値比較に使う)。 */
export enum LogLevel {
  Debug = 10,
  Info = 20,
  Warn = 30,
  Error = 40,
}

const LEVEL_LABELS: Record<LogLevel, string> = {
  [LogLevel.Debug]: 'DEBUG',
  [LogLevel.Info]: 'INFO',
  [LogLevel.Warn]: 'WARN',
  [LogLevel.Error]: 'ERROR',
};

/**
 * 出力先の抽象。テストや拡張ホスト外での利用を可能にするため、
 * vscode.OutputChannelそのものではなく必要な操作だけを要求する。
 */
export interface LogSink {
  /** 1行を出力する。 */
  appendLine(value: string): void;
  /** 出力先を前面に表示する(対応する出力先のみ)。 */
  show?(preserveFocus?: boolean): void;
  /** 出力先を破棄する(対応する出力先のみ)。 */
  dispose?(): void;
}

/**
 * 構造化ログ機構。`letsBlog.debugMode`が有効な場合のみDEBUGレベルを出力し、
 * それ以外はINFO以上を出力する。ログはOutputChannel「Let's Blog」へ書き出す。
 *
 * 認証情報の漏洩を防ぐため、コンテキストオブジェクトの値はredactContext()で
 * マスクしてから出力する。
 */
export class Logger {
  private _sink: LogSink | undefined;
  private _minLevel: LogLevel = LogLevel.Info;

  /** 出力先を差し替える(既定ではOutputChannelを遅延生成する)。テストから利用する。 */
  public setSink(sink: LogSink | undefined): void {
    this._sink = sink;
  }

  /** 出力する最小レベルを明示的に指定する。指定しない場合は設定値から解決される。 */
  public setMinLevel(level: LogLevel): void {
    this._minLevel = level;
  }

  /** `letsBlog.debugMode`設定を読み、出力レベルを更新する。 */
  public refreshFromConfiguration(): void {
    const debugMode = vscode.workspace.getConfiguration('letsBlog').get<boolean>('debugMode', false);
    this._minLevel = debugMode ? LogLevel.Debug : LogLevel.Info;
  }

  /** 不具合調査用の詳細ログ。letsBlog.debugModeが有効なときだけ出力される。 */
  public debug(message: string, context?: Record<string, unknown>): void {
    this._log(LogLevel.Debug, message, context);
  }

  /** 通常の動作記録。 */
  public info(message: string, context?: Record<string, unknown>): void {
    this._log(LogLevel.Info, message, context);
  }

  /** 処理は継続するが注意が必要な事象。 */
  public warn(message: string, context?: Record<string, unknown>): void {
    this._log(LogLevel.Warn, message, context);
  }

  /** 処理が失敗した事象。 */
  public error(message: string, context?: Record<string, unknown>): void {
    this._log(LogLevel.Error, message, context);
  }

  /** 出力パネルを前面に表示する(エラー通知の「ログを表示」から呼ばれる)。 */
  public show(): void {
    this._resolveSink().show?.(true);
  }

  /** 出力先を破棄する。拡張の無効化時に呼ばれる。 */
  public dispose(): void {
    this._sink?.dispose?.();
    this._sink = undefined;
  }

  private _log(level: LogLevel, message: string, context?: Record<string, unknown>): void {
    if (level < this._minLevel) {
      return;
    }
    const timestamp = new Date().toISOString();
    const suffix = context && Object.keys(context).length > 0 ? ` ${formatContext(context)}` : '';
    this._resolveSink().appendLine(`[${timestamp}] [${LEVEL_LABELS[level]}] ${message}${suffix}`);
  }

  private _resolveSink(): LogSink {
    if (!this._sink) {
      this._sink = vscode.window.createOutputChannel("Let's Blog");
    }
    return this._sink;
  }
}

const SECRET_KEY_PATTERN = /(apikey|api_key|password|secret|token|authorization|credential)/i;

/**
 * ログ出力用にコンテキストを整形する。キー名が認証情報らしいものは値を伏せる。
 * 値の内容だけを見て機密性を判定するのは不確実なため、キー名で判定する。
 */
export function redactContext(context: Record<string, unknown>): Record<string, unknown> {
  const redacted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(context)) {
    redacted[key] = SECRET_KEY_PATTERN.test(key) ? '***' : value;
  }
  return redacted;
}

function formatContext(context: Record<string, unknown>): string {
  try {
    return JSON.stringify(redactContext(context));
  } catch {
    // 循環参照などでJSON化できない場合でもログ自体は落とさない。
    return '[unserializable context]';
  }
}

/** 拡張全体で共有するロガー。 */
export const logger = new Logger();
