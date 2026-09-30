/**
 * @jest-environment jsdom
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

jest.mock('next/font/google', () => ({
  Geist: () => ({ variable: 'geist' }),
  Geist_Mono: () => ({ variable: 'geist-mono' }),
}))
jest.mock('next/navigation', () => ({ useServerInsertedHTML: jest.fn(), usePathname: () => '/' }))
jest.mock('@/lib/session', () => ({ getSession: jest.fn() }))
jest.mock('../SideNav', () => ({
  SideNavProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SideNavToggle: () => <i data-testid="side-nav-toggle" />,
  SideNav: () => <aside data-testid="side-nav" />,
}))
jest.mock('../LogoutButton', () => ({ LogoutButton: () => <button data-testid="logout">logout</button> }))
jest.mock('../ThemeSwitcher', () => ({ ThemeSwitcher: () => <i data-testid="theme" /> }))
jest.mock('../LanguageSwitcher', () => ({ LanguageSwitcher: () => <i data-testid="lang" /> }))
jest.mock('../DownloadMenu', () => ({ DownloadMenu: () => <div data-testid="download-menu" /> }))
jest.mock('../I18nProvider', () => ({ I18nProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../SessionProvider', () => ({ SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../globals.css', () => ({}), { virtual: true })
jest.mock('../ThemeScript', () => ({ ThemeScript: () => null }))
jest.mock('../Footer', () => ({ Footer: () => <footer /> }), { virtual: true })

import { getSession } from '@/lib/session'
import RootLayout from '../layout'

async function renderHeader(session: unknown) {
  ;(getSession as jest.Mock).mockResolvedValue(session)
  const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
  const doc = new DOMParser().parseFromString(html, 'text/html')
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
    expect(download?.querySelector('[data-testid="download-menu"]')).not.toBeNull()
    expect(logo?.contains(account as Node)).toBe(false)
    expect(account?.contains(download as Node)).toBe(false)
  })

  it('keeps the email and logout together in the account group, with language and theme switchers outside', async () => {
    const header = await renderHeader({ user: { role: 'user', email: 'u@example.com' } })
    const account = header.querySelector('[data-testid="header-account"]') as HTMLElement
    expect(account.textContent).toContain('u@example.com')
    expect(account.querySelector('[data-testid="logout"]')).not.toBeNull()
    expect(account.querySelector('[data-testid="lang"]')).toBeNull()
    expect(account.querySelector('[data-testid="theme"]')).toBeNull()
    expect(header.querySelector('[data-testid="lang"]')).not.toBeNull()
    expect(header.querySelector('[data-testid="theme"]')).not.toBeNull()
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
