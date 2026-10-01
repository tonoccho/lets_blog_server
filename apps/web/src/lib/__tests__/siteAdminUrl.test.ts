import { resolveSiteAdminUrl } from '../siteAdminUrl'

describe('resolveSiteAdminUrl', () => {
  it('baseUrl とパスから管理画面の絶対 URL を組み立てる', () => {
    expect(resolveSiteAdminUrl('https://example.com', 'wp-admin')).toBe('https://example.com/wp-admin')
  })

  it('baseUrl の末尾スラッシュが重複しない', () => {
    expect(resolveSiteAdminUrl('https://example.com/', 'wp-admin')).toBe('https://example.com/wp-admin')
  })

  it('パスの先頭スラッシュを取り除く', () => {
    expect(resolveSiteAdminUrl('https://example.com', '/custom-admin')).toBe('https://example.com/custom-admin')
  })

  it('サブパス付き baseUrl の配下に解決する', () => {
    expect(resolveSiteAdminUrl('https://example.com/blog', '/wp-admin')).toBe('https://example.com/blog/wp-admin')
  })

  it('別オリジンに解決されるパスは null', () => {
    expect(resolveSiteAdminUrl('https://example.com', 'https://evil.example.com/x')).toBeNull()
  })

  it('baseUrl が URL として解釈できないときは null', () => {
    expect(resolveSiteAdminUrl('not a url', 'wp-admin')).toBeNull()
  })

  it('パスが URL として解釈できないときは null', () => {
    expect(resolveSiteAdminUrl('https://example.com', 'http://[bad')).toBeNull()
  })
})
