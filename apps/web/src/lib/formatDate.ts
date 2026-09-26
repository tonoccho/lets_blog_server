/**
 * 個人設定TZが未設定のときの、マウント前(サーバー描画時点)のプレースホルダー
 * (issue #1362、親issue #1261 分割A)。ブラウザTZはマウント後にしか分からず、サーバー
 * 描画と同じ値を先に出せないため、両者で同じ固定文字列を描いてハイドレーション不一致を
 * 避ける(前例: ThemeSwitcher.tsx:23-58 の mounted フラグ方式)。
 *
 * 元は`ConnectedServiceStatusPanel.tsx`と`SshKeyPairsPanel.tsx`がそれぞれ同じ値を
 * ローカル定数として重複して持っていたが、3つ目以降の利用先(`ImageGalleryGrid.tsx`
 * 等、issue #1363)が増えたためここへ共有化した(issue #1363 Requirement 2)。
 */
export const TIMEZONE_PENDING_PLACEHOLDER = "読み込み中…";

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

/**
 * 日時文字列を、指定タイムゾーン(未指定ならブラウザ既定TZ)での暦日として
 * `YYYYMMDD`(区切りなし)形式で返す(issue #1366、親issue #1261 分割B-2)。
 *
 * `ArticlePlanSessionList.tsx`の旧`formatSessionDate()`は
 * `new Date(iso).getFullYear()/getMonth()/getDate()`で日付を組み立てていた。この形は
 * 「解釈」(`new Date(iso)`)と「取り出し」(`getFullYear()`等)の両方が実行環境の
 * ローカルタイムに揃っているため、実行環境のTZが変わっても出力が変わらない(#1279の実測:
 * UTC/Pacific/Auckland/America/New_Yorkのいずれでも同じ文字列になる)。だが指定
 * タイムゾーンへ従わせる経路が無く、常に実行環境(SSRコンテナ=UTC、ブラウザ=閲覧者TZ)の
 * 暦日をそのまま出していた。
 *
 * ここで`Z`を付けてUTCとして解釈させながら、取り出しを`getFullYear()`等のローカル取得の
 * ままにすると、**そこで初めて環境差が生まれて壊れる**(issue #1366のProblem実測:
 * `Pacific/Auckland`だけ日付が繰り上がらず、UTCと同じ暦日のままになる)。「解釈」の基準
 * (UTCへの正規化)と「取り出し」の基準を必ず一致させる必要があるため、取り出しも
 * `Intl.DateTimeFormat(..., { timeZone }).formatToParts()`で明示したタイムゾーンに揃える。
 * `getFullYear()`等のローカル取得は使わない。
 *
 * オフセット付き入力・空文字・パース不能な文字列の挙動は退行させない(旧#1279
 * Requirement 3)。パース不能な結果(`Invalid Date`)をそのまま`Intl.DateTimeFormat`へ
 * 渡すと`RangeError`を投げる(実測)ため、その場合は例外を投げず、旧実装と同じ
 * `NaNNaNNaN`(`getFullYear()`等がNaNを返す挙動)を返す。
 */
export function formatDateYYYYMMDD(iso: string, timeZone?: string | null): string {
  const date = new Date(normalizeToUtcIfOffsetMissing(iso));
  if (Number.isNaN(date.getTime())) {
    const yyyy = date.getFullYear();
    const mm = String(date.getMonth() + 1).padStart(2, "0");
    const dd = String(date.getDate()).padStart(2, "0");
    return `${yyyy}${mm}${dd}`;
  }
  const tz = timeZone ?? getDefaultTimeZone();
  const parts = new Intl.DateTimeFormat("ja-JP", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
  return `${get("year")}${get("month")}${get("day")}`;
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

/**
 * 日時を UTC の `Z` 付き ISO-8601(秒まで。例 `2026-09-11T09:10:35Z`)へ整形する(issue #1260)。
 * 「コピー」で作る共有用トレースの日時に使い、共有先で時刻の基準が曖昧にならないようにする。
 * オフセット無しの入力は UTC として解釈する(`normalizeToUtcIfOffsetMissing`)。
 * パース不能な入力は例外を投げず、そのまま返す。
 */
export function formatUtcIso8601(iso: string): string {
  const date = new Date(normalizeToUtcIfOffsetMissing(iso));
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toISOString().slice(0, 19) + "Z";
}

/**
 * 閲覧者TZの壁時計(`<input type="datetime-local">` の値。例 `2026-09-10T09:30`)を、
 * バックエンドが受ける UTC の日時文字列(オフセット指定子なし。例 `2026-09-10T00:30:00`)へ
 * 換算する(issue #1138)。
 *
 * バックエンドの `LocalDateTime` はオフセット無しを UTC の壁時計として扱う
 * (`normalizeToUtcIfOffsetMissing` と同じ前提)。表示は閲覧者TZで行うので、入力も同じTZで
 * 受け、ここで UTC へ戻す。
 *
 * 換算は「入力をUTCとみなした瞬間 t0 の、指定TZでの壁時計」との差でオフセットを求め、
 * 求めた結果の時点でもう一度オフセットを取り直す(夏時間の境界をまたいでも、その時点の
 * オフセットで換算するため)。
 *
 * 空・パース不能な値は `undefined`(絞り込みなし)。
 *
 * @param options.endOfMinute `datetime-local` は分単位なので、終了側に使うときは
 *   その分の最後の秒(:59)まで含める。
 */
export function localDateTimeToUtcIso(
  local: string | undefined,
  timeZone?: string | null,
  options: { endOfMinute?: boolean } = {}
): string | undefined {
  if (!local) {
    return undefined;
  }
  const match = local.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) {
    return undefined;
  }
  const [, y, mo, d, h, mi, sec] = match;
  const seconds = sec !== undefined ? Number(sec) : options.endOfMinute ? 59 : 0;
  const wallAsUtc = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), seconds);
  const tz = timeZone ?? getDefaultTimeZone();
  const offsetAt = (instant: number): number => {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).formatToParts(new Date(instant));
    const get = (type: string) => Number(parts.find((part) => part.type === type)?.value);
    const shown = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
    return shown - Math.floor(instant / 1000) * 1000;
  };
  const first = wallAsUtc - offsetAt(wallAsUtc);
  const result = wallAsUtc - offsetAt(first);
  return new Date(result).toISOString().slice(0, 19);
}
