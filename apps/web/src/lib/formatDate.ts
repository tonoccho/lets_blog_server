function getDefaultTimeZone(): string {
  if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone;
    } catch {
      return "UTC";
    }
  }
  return "UTC";
}

/**
 * 日時文字列の時刻部分がオフセット指定子(`Z` または `±HH:MM`)を持つかどうかを判定する。
 *
 * 判定は "T" 以降の時刻部分だけを見る。日付部分の `-` 区切り(`YYYY-MM-DD`)を
 * オフセット(`-HH:MM`)と取り違えないようにするため(issue #1236)。
 */
function hasOffsetDesignator(iso: string): boolean {
  const timePart = iso.includes("T") ? iso.slice(iso.indexOf("T") + 1) : iso;
  return /[Zz]$/.test(timePart) || /[+-]\d{2}:?\d{2}$/.test(timePart);
}

/**
 * オフセット指定子の無い日時文字列をUTCとして解釈させる(issue #1236)。
 *
 * バックエンド(Java `LocalDateTime`)はオフセット指定子を持たない日時文字列
 * (例: `"2026-09-08T20:03:35"`)を返す。ECMAScript仕様では、オフセットの無い日時文字列は
 * `new Date(iso)` の**実行環境のローカルタイム**として解釈されるため、そのままでは
 * SSR(コンテナ)とブラウザで異なる瞬間になり、表示結果が実行環境のTZに依存してしまう。
 *
 * この正規化(「オフセット無し = UTC」)が正しいのは、バックエンドの全コンテナが
 * UTCで動作しているという前提があるからである(`docker-compose.yml` の対象サービスに
 * `TZ` の指定が無く、実測でもコンテナTZ=UTC)。コンテナがUTC以外で動作するようになると、
 * この前提は崩れる。恒久的な解決はバックエンドの日時契約をオフセット付き
 * (`Instant` / `OffsetDateTime`)に是正することで、別issue(#1237)で追跡する。
 *
 * 既にオフセットを持つ入力・空文字・パース不能な文字列はそのまま返す(`new Date()` に
 * そのまま委ね、従来通り例外を投げずに `Invalid Date` として扱わせる)。
 */
function normalizeToUtcIfOffsetMissing(iso: string): string {
  if (!iso || hasOffsetDesignator(iso)) {
    return iso;
  }
  return `${iso}Z`;
}

export function formatDateTime(iso: string, timeZone?: string | null): string {
  const tz = timeZone ?? getDefaultTimeZone();
  return new Date(normalizeToUtcIfOffsetMissing(iso)).toLocaleString("ja-JP", { timeZone: tz });
}

/** 操作ログの日時表示を24時間表記(HH:mm:ss、ゼロ埋め)に統一する(issue #282)。 */
export function formatOperationLogDateTime(iso: string, timeZone?: string | null): string {
  const tz = timeZone ?? getDefaultTimeZone();
  return new Date(normalizeToUtcIfOffsetMissing(iso)).toLocaleString("ja-JP", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}
