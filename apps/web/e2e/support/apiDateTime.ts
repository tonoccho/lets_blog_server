/**
 * API の日時(オフセット無しのISO文字列。バックエンドの `LocalDateTime.toString()`)を
 * UTC として解釈し、エポックミリ秒で返す(#1257)。
 *
 * バックエンドはこの文字列をUTCの壁時計値として扱う(#1314)。`Date.parse` はゾーン無し文字列を
 * 実行ホストのローカルTZとして解釈する(ECMAScript仕様)ため、ホストがUTCでないと結果がずれる。
 * `Z` かオフセットが既にある文字列はそのまま解釈する。解釈できなければ NaN。
 */
const HAS_ZONE = /(Z|[+-]\d{2}:?\d{2})$/i;

export function parseApiDateTime(value: string): number {
  return Date.parse(HAS_ZONE.test(value) ? value : `${value}Z`);
}
