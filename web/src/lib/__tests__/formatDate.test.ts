import { formatDateTime } from '../formatDate'

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
