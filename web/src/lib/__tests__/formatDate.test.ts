import { formatDateTime } from '../formatDate'

describe('formatDateTime', () => {
  it('formats ISO string to Japanese locale', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, 'Asia/Tokyo')
    expect(result).toContain('2024')
    expect(result).toContain('1')
    expect(result).toContain('15')
  })

  it('uses Tokyo timezone by default', () => {
    const iso = '2024-01-15T10:30:00Z'
    const resultDefault = formatDateTime(iso)
    const resultTokyo = formatDateTime(iso, 'Asia/Tokyo')
    expect(resultDefault).toBe(resultTokyo)
  })

  it('handles different timezones', () => {
    const iso = '2024-01-15T10:30:00Z'
    const resultTokyo = formatDateTime(iso, 'Asia/Tokyo')
    const resultUTC = formatDateTime(iso, 'UTC')
    expect(resultTokyo).not.toBe(resultUTC)
  })

  it('handles null timezone', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, null)
    const resultDefault = formatDateTime(iso, 'Asia/Tokyo')
    expect(result).toBe(resultDefault)
  })

  it('formats dates correctly', () => {
    const iso = '2024-01-15T10:30:00Z'
    const result = formatDateTime(iso, 'Asia/Tokyo')
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})
