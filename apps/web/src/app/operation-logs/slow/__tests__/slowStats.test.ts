import { nextDirection, defaultSlowPeriod, buildSlowQuery } from '../slowStats'

describe('nextDirection(issue #1471)', () => {
  it('別の列を選ぶと降順から始める', () => {
    expect(nextDirection('p95', 'desc', 'count')).toBe('desc')
  })

  it('いま並べている列をもう一度選ぶと向きが反転する', () => {
    expect(nextDirection('p95', 'desc', 'p95')).toBe('asc')
    expect(nextDirection('p95', 'asc', 'p95')).toBe('desc')
  })
})

describe('defaultSlowPeriod(issue #1471)', () => {
  it('現在から24時間前までをUTCのISO日時(秒まで、Zなし)で返す', () => {
    const now = new Date('2026-10-02T12:34:56.789Z')
    expect(defaultSlowPeriod(now)).toEqual({ startDate: '2026-10-01T12:34:56', endDate: '2026-10-02T12:34:56' })
  })
})

describe('buildSlowQuery(issue #1471)', () => {
  it('値のあるものだけクエリにする', () => {
    const q = buildSlowQuery({ startDate: '2026-10-01T09:00', routeSort: 'count', routeDir: 'asc', trace: undefined })
    const params = new URLSearchParams(q)
    expect(params.get('startDate')).toBe('2026-10-01T09:00')
    expect(params.get('routeSort')).toBe('count')
    expect(params.get('routeDir')).toBe('asc')
    expect(params.has('trace')).toBe(false)
    expect(params.has('endDate')).toBe(false)
  })
})
