import {
  formatDateTime,
  formatOperationLogDateTime,
  formatDateYYYYMMDD,
  localDateTimeToUtcIso,
} from '../formatDate'

describe('formatDateTime', () => {
  it('formats ISO string to Japanese locale', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, 'Asia/Tokyo')
    expect(result).toContain('2024')
    expect(result).toContain('1')
    expect(result).toContain('15')
  })

  it('uses browser timezone when timeZone is not specified', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso)
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })

  it('handles different timezones', () => {
    const iso = '2024-01-15T10:30:00Z'
    const resultTokyo = formatDateTime(iso, 'Asia/Tokyo')
    const resultUTC = formatDateTime(iso, 'UTC')
    expect(resultTokyo).not.toBe(resultUTC)
  })

  it('handles null timezone by using browser timezone', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, null)
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })

  it('formats dates correctly with explicit timezone', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, 'Asia/Tokyo')
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })

  it('respects user-specified timezone over browser timezone', () => {
    const iso = '2024-01-15T10:30:00Z'
    const resultUserTZ = formatDateTime(iso, 'UTC')
    const resultBrowserTZ = formatDateTime(iso)
    expect(resultUserTZ).toBeDefined()
    expect(resultBrowserTZ).toBeDefined()
  })
})

describe('formatOperationLogDateTime', () => {
  it('formats time in zero-padded 24-hour notation (HH:mm:ss)', () => {
    const iso = '2024-01-15T15:05:07Z'
    const result = formatOperationLogDateTime(iso, 'Asia/Tokyo')
    expect(result).toContain('00:05:07')
    expect(result).not.toMatch(/AM|PM|午前|午後/)
  })

  it('zero-pads single-digit hours instead of showing a bare digit', () => {
    const iso = '2024-01-15T15:05:07Z'
    const result = formatOperationLogDateTime(iso, 'Asia/Tokyo')
    expect(result).not.toMatch(/(^|\s)0:05:07/)
  })

  it('uses browser timezone when timeZone is not specified', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatOperationLogDateTime(iso)
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})

/**
 * issue #1236: バックエンド(Java LocalDateTime)はオフセット指定子を持たない日時文字列
 * ("2026-09-08T20:03:35")を返す。ECMAScript仕様では、オフセットの無い日時文字列は
 * `new Date(iso)` の実行環境のローカルタイムとして解釈されるため、SSR(コンテナ、TZ=UTC)と
 * ブラウザ(任意のTZ)で異なる瞬間になり、表示結果がテストを実行するプロセスのTZに依存して
 * しまっていた。
 *
 * ここで確かめたいのは「実行環境のTZが何であっても結果が変わらないこと」なので、
 * `TZ=UTC npx jest src/lib/__tests__/formatDate.test.ts` と
 * `TZ=Pacific/Auckland npx jest src/lib/__tests__/formatDate.test.ts` の
 * 両方で実行し、同じ期待値に一致することを確認する(1プロセス内での
 * `process.env.TZ` の再代入では、jest(Intl)がプロセス起動時のTZをキャッシュしており
 * 反映されないため、それでは検出できない。CLAUDE.md Test-First Implementation 参照)。
 */
describe('formatDateTime — オフセットなし入力の実行環境TZ非依存性(issue #1236)', () => {
  it('オフセット指定子の無い入力をUTCとして解釈し、実行環境TZに関わらず同一の文字列を返す', () => {
    const result = formatDateTime('2026-09-08T20:03:35', 'Pacific/Auckland')
    expect(result).toBe('2026/9/9 8:03:35')
  })

  it('オフセット付き入力(Z)の挙動は変えない', () => {
    const result = formatDateTime('2024-01-15T10:30:00Z', 'UTC')
    expect(result).toBe('2024/1/15 10:30:00')
  })

  it('オフセット付き入力(+09:00)の挙動は変えない', () => {
    const result = formatDateTime('2024-01-15T10:30:00+09:00', 'UTC')
    expect(result).toBe('2024/1/15 1:30:00')
  })

  it('空文字を渡しても例外を投げない', () => {
    expect(() => formatDateTime('', 'UTC')).not.toThrow()
  })

  it('パース不能な文字列を渡しても例外を投げない', () => {
    expect(() => formatDateTime('not-a-date', 'UTC')).not.toThrow()
    expect(formatDateTime('not-a-date', 'UTC')).toBe('Invalid Date')
  })
})

describe('formatOperationLogDateTime — オフセットなし入力の実行環境TZ非依存性(issue #1236)', () => {
  it('オフセット指定子の無い入力をUTCとして解釈し、実行環境TZに関わらず同一の文字列を返す', () => {
    const result = formatOperationLogDateTime('2026-09-08T20:03:35', 'Pacific/Auckland')
    expect(result).toBe('2026/09/09 08:03:35')
  })

  it('オフセット付き入力(Z)の挙動は変えない', () => {
    const result = formatOperationLogDateTime('2024-01-15T15:05:07Z', 'Asia/Tokyo')
    expect(result).toBe('2024/01/16 00:05:07')
  })

  it('空文字・パース不能な文字列を渡しても例外を投げない', () => {
    expect(() => formatOperationLogDateTime('', 'UTC')).not.toThrow()
    expect(() => formatOperationLogDateTime('not-a-date', 'UTC')).not.toThrow()
  })
})

/**
 * issue #1366(親issue #1261 分割B-2): `ArticlePlanSessionList.tsx` の旧
 * `formatSessionDate()` は `new Date(iso).getFullYear()/getMonth()/getDate()` で
 * 「解釈」と「取り出し」の両方を実行環境のローカルタイムに揃えていたため、実行環境のTZが
 * 変わっても出力が変わらなかった(#1279の実測: UTC/Pacific/Auckland/America/New_York
 * いずれも `2026-09-08T23:30:00` を `20260908` と表示する)。
 *
 * 一方、`Z` を付けてUTCとして解釈させながら取り出しを`getFullYear()`等のローカル取得の
 * ままにすると、**そこで初めて環境差が生まれて壊れる**(issue #1366 Problem実測:
 * `Pacific/Auckland` だけ `20260909` にずれる)。正しくは「解釈」(UTCとして正規化)と
 * 「取り出し」(`Intl.DateTimeFormat(..., { timeZone }).formatToParts()` で明示した
 * タイムゾーンに揃える)の両方の基準を一致させる必要がある。
 *
 * このdescribeの最初のテスト(`Pacific/Auckland`で日付が繰り上がる)が、その落とし穴を
 * 固定する(トラップの実測: `Z`付与+ローカル取得のままでは、テスト実行環境のTZが
 * UTCである限り`20260908`のままになり失敗する。実際に`timeZone`引数を使って正しく
 * 換算しない実装でもこのテストは失敗する)。
 */
describe('formatDateYYYYMMDD — 個人設定TZ・ブラウザTZに従ったYYYYMMDD組み立て(issue #1366)', () => {
  it('オフセット指定子の無い入力をUTCとして解釈し、タイムゾーンごとに正しい暦日(YYYYMMDD)を返す(Pacific/Aucklandで日付が繰り上がる、issue #1366の落とし穴を固定)', () => {
    const iso = '2026-09-08T23:30:00'
    expect(formatDateYYYYMMDD(iso, 'UTC')).toBe('20260908')
    expect(formatDateYYYYMMDD(iso, 'Pacific/Auckland')).toBe('20260909')
    expect(formatDateYYYYMMDD(iso, 'America/New_York')).toBe('20260908')
  })

  it('timeZoneが未指定でもInvalidにならず文字列を返す(ブラウザ既定TZへのフォールバック)', () => {
    const result = formatDateYYYYMMDD('2026-09-08T23:30:00')
    expect(typeof result).toBe('string')
    expect(result).toMatch(/^\d{8}$/)
  })

  it('オフセット付き入力(Z)の挙動を退行させない(指定タイムゾーンへ正しく換算する)', () => {
    expect(formatDateYYYYMMDD('2024-01-15T10:30:00Z', 'UTC')).toBe('20240115')
    expect(formatDateYYYYMMDD('2024-01-15T10:30:00Z', 'Asia/Tokyo')).toBe('20240115')
  })

  it('オフセット付き入力(+09:00)の挙動を退行させない', () => {
    // 2024-01-15T10:30:00+09:00 は 2024-01-15T01:30:00Z と同じ瞬間。
    expect(formatDateYYYYMMDD('2024-01-15T10:30:00+09:00', 'UTC')).toBe('20240115')
  })

  it('空文字を渡しても例外を投げない(旧実装と同じNaN文字列のまま、issue #1279 Requirement 3)', () => {
    expect(() => formatDateYYYYMMDD('', 'UTC')).not.toThrow()
    expect(formatDateYYYYMMDD('', 'UTC')).toBe('NaNNaNNaN')
  })

  it('パース不能な文字列を渡しても例外を投げない(Intl.DateTimeFormatへInvalid Dateを渡すとRangeErrorになるため、その手前でガードする)', () => {
    expect(() => formatDateYYYYMMDD('not-a-date', 'UTC')).not.toThrow()
    expect(formatDateYYYYMMDD('not-a-date', 'UTC')).toBe('NaNNaNNaN')
  })
})

describe('localDateTimeToUtcIso(issue #1138: 閲覧者TZの壁時計 -> バックエンドのUTC壁時計)', () => {
  it('Asia/Tokyo(+09:00)の入力をUTCへ換算し、オフセット指定子を付けない', () => {
    expect(localDateTimeToUtcIso('2026-09-10T09:30', 'Asia/Tokyo')).toBe('2026-09-10T00:30:00')
  })

  it('日付をまたぐ換算ができる', () => {
    expect(localDateTimeToUtcIso('2026-09-10T05:00', 'Asia/Tokyo')).toBe('2026-09-09T20:00:00')
    expect(localDateTimeToUtcIso('2026-09-10T22:00', 'America/New_York')).toBe('2026-09-11T02:00:00')
  })

  it('UTCならそのまま', () => {
    expect(localDateTimeToUtcIso('2026-09-10T09:30', 'UTC')).toBe('2026-09-10T09:30:00')
  })

  it('秒付きの入力も受け付ける', () => {
    expect(localDateTimeToUtcIso('2026-09-10T09:30:15', 'UTC')).toBe('2026-09-10T09:30:15')
  })

  it('endOfMinuteを指定すると、その分の最後の秒まで含める', () => {
    expect(localDateTimeToUtcIso('2026-09-10T09:30', 'UTC', { endOfMinute: true })).toBe('2026-09-10T09:30:59')
  })

  it('夏時間の切り替えを跨いでも、その時点のオフセットで換算する', () => {
    // America/New_York: 2026-01-15 は EST(-05:00)、2026-07-15 は EDT(-04:00)。
    expect(localDateTimeToUtcIso('2026-01-15T12:00', 'America/New_York')).toBe('2026-01-15T17:00:00')
    expect(localDateTimeToUtcIso('2026-07-15T12:00', 'America/New_York')).toBe('2026-07-15T16:00:00')
  })

  it('timeZoneがnullでも例外を投げず、ブラウザ既定TZで換算する', () => {
    expect(localDateTimeToUtcIso('2026-09-10T09:30', null)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  })

  it('空文字・パース不能な値はundefinedを返す(絞り込みなし)', () => {
    expect(localDateTimeToUtcIso('', 'UTC')).toBeUndefined()
    expect(localDateTimeToUtcIso(undefined, 'UTC')).toBeUndefined()
    expect(localDateTimeToUtcIso('not-a-date', 'UTC')).toBeUndefined()
  })
})
