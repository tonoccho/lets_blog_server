/**
 * @jest-environment node
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'
import { JSDOM } from 'jsdom'

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

async function renderHeader(session: unknown) {
  getServerSessionMock.mockResolvedValue(session)
  const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
  const doc = new JSDOM(html).window.document
  return doc.querySelector('header') as HTMLElement
}

describe('RootLayout header groups (#1490)', () => {
  it('shows logo, account and download menu as three separate groups', async () => {
    const header = await renderHeader({ user: { role: 'user', email: 'u@example.com' } })
    const logo = header.querySelector('[data-testid="header-logo"]')
    const account = header.querySelector('[data-testid="header-account"]')
    const download = header.querySelector('[data-testid="header-download"]')
    expect(logo).not.toBeNull()
    expect(account).not.toBeNull()
    expect(download).not.toBeNull()
    expect(logo?.textContent).toContain("Let's Blog Server")
    expect(download?.querySelector('button[aria-label="ダウンロード"]')).not.toBeNull()
    expect(logo?.querySelector('button[aria-label="メニューを開く"]')).not.toBeNull()
    expect(logo?.contains(account as Node)).toBe(false)
    expect(account?.contains(download as Node)).toBe(false)
  })

  it('keeps the email and logout together in the account group, with language and theme switchers outside', async () => {
    const header = await renderHeader({ user: { role: 'user', email: 'u@example.com' } })
    const account = header.querySelector('[data-testid="header-account"]') as HTMLElement
    expect(account.textContent).toContain('u@example.com')
    expect(account.querySelector('button[aria-label="ログアウト"]')).not.toBeNull()
    expect(account.querySelector('[data-testid="language-switcher"]')).toBeNull()
    expect(account.querySelector('[data-testid="theme-switcher"]')).toBeNull()
    expect(header.querySelector('[data-testid="language-switcher"] select[aria-label="言語選択"]')).not.toBeNull()
    expect(header.querySelector('[data-testid="theme-switcher"]')).not.toBeNull()
  })

  it('wraps rather than overflowing so the groups stay usable at narrow widths', async () => {
    const header = await renderHeader({ user: { role: 'user', email: 'u@example.com' } })
    expect(header.innerHTML).toMatch(/flex-wrap/)
  })

  it('shows only the logo group when logged out', async () => {
    const header = await renderHeader(null)
    expect(header.querySelector('[data-testid="header-logo"]')).not.toBeNull()
    expect(header.querySelector('[data-testid="header-account"]')).toBeNull()
    expect(header.querySelector('[data-testid="header-download"]')).toBeNull()
  })
})
