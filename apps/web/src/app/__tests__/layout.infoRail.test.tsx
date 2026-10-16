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

describe('RootLayout info rail (#1489)', () => {
  it('places the rail right of main, inside the middle row, when logged in', async () => {
    getServerSessionMock.mockResolvedValue({ user: { role: 'user', email: 'u@example.com' } })
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    const rail = html.indexOf('data-testid="info-rail"')
    expect(rail).toBeGreaterThan(html.indexOf('</main>'))
    expect(rail).toBeLessThan(html.indexOf('<footer'))
  })

  it('does not render the rail when logged out', async () => {
    getServerSessionMock.mockResolvedValue(null)
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    expect(html).not.toContain('info-rail')
    expect(html).toContain('<main')
  })
})
