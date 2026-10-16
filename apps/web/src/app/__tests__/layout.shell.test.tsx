/**
 * @jest-environment node
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

jest.mock('server-only', () => ({}))
// 自前の @/lib/session・@/lib/auth・子コンポーネント(SideNav / InfoRail / Footer など)は差し替えない。
// 差し替えるのは Next.js の実行基盤(next/font・next/navigation・CSS・next-auth のセッション取得)だけ(#1832)。
jest.mock('next/font/google', () => ({
  Geist: () => ({ variable: 'geist' }),
  Geist_Mono: () => ({ variable: 'geist-mono' }),
}))
jest.mock('next/navigation', () => ({ useServerInsertedHTML: jest.fn(), usePathname: () => '/' }))
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('../globals.css', () => ({}), { virtual: true })

import { getServerSessionMock } from '@/lib/__tests__/testUtils/serverRuntime'
import RootLayout from '../layout'

beforeEach(() => {
  getServerSessionMock.mockResolvedValue({ user: { role: 'user', email: 'u@example.com' } })
})

describe('RootLayout three-tier full-width shell (#1487)', () => {
  it('has header, main, footer in order with no max-w-* cap and keeps px-4', async () => {
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    const h = html.indexOf('<header')
    const m = html.indexOf('<main')
    const f = html.indexOf('<footer')
    expect(h).toBeGreaterThan(-1)
    expect(m).toBeGreaterThan(h)
    expect(f).toBeGreaterThan(html.indexOf('</main>'))
    expect(html.slice(h, html.indexOf('</header>'))).not.toMatch(/max-w-/)
    const mainTag = html.slice(m, html.indexOf('>', m))
    expect(mainTag).not.toMatch(/max-w-|mx-auto/)
    expect(mainTag).toContain('px-4')
    expect(mainTag).toContain('flex-1')
    expect(html.slice(h, html.indexOf('</header>'))).toContain('px-4')
  })

  it('places the side menu left of main inside the middle row, with no horizontal header nav', async () => {
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    const header = html.slice(html.indexOf('<header'), html.indexOf('</header>'))
    expect(header).not.toContain('<nav')
    const aside = html.indexOf('data-testid="side-nav"')
    expect(aside).toBeGreaterThan(html.indexOf('</header>'))
    expect(aside).toBeLessThan(html.indexOf('<main'))
    expect(html.indexOf('<main')).toBeLessThan(html.indexOf('<footer'))
  })
})
