/**
 * issue #1257: ホストのタイムゾーンに関係なく、jest は UTC で動く。
 * 日時を扱う単体テストの結果が、実行ホストのTZで変わらないようにする。
 * 固定は jest.config.ts が行う。
 */
describe('jest の実行タイムゾーン', () => {
  it('TZ が UTC に固定されている', () => {
    expect(process.env.TZ).toBe('UTC')
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('UTC')
  })

  it('ゾーン無しの日時文字列が UTC として解釈される', () => {
    expect(new Date('2026-09-15T21:33:54').toISOString()).toBe('2026-09-15T21:33:54.000Z')
    expect(new Date(2026, 8, 15, 21, 33, 54).getTimezoneOffset()).toBe(0)
  })
})
