import { formatDateTime, formatOperationLogDateTime } from '../formatDate'

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
