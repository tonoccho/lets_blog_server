/**
 * @jest-environment node
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

jest.mock('next/font/google', () => ({
  Geist: () => ({ variable: 'geist' }),
  Geist_Mono: () => ({ variable: 'geist-mono' }),
}))
jest.mock('next/navigation', () => ({ useServerInsertedHTML: jest.fn(), usePathname: () => '/' }))

const getSession = jest.fn()
jest.mock('@/lib/session', () => ({ getSession: () => getSession() }))

jest.mock('../SideNav', () => ({
  SideNavProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SideNavToggle: () => null,
  SideNav: ({ navItems }: { navItems: unknown[] }) => <nav data-count={navItems.length} />,
}))
jest.mock('../Footer', () => ({ Footer: () => <footer /> }))
jest.mock('../InfoRail', () => ({ InfoRail: () => <aside data-testid="info-rail" /> }))
jest.mock('../LogoutButton', () => ({ LogoutButton: () => null }))
jest.mock('../ThemeSwitcher', () => ({ ThemeSwitcher: () => null }))
jest.mock('../LanguageSwitcher', () => ({ LanguageSwitcher: () => null }))
jest.mock('../DownloadMenu', () => ({ DownloadMenu: () => null }))
// useI18n も返す。子コンポーネントのモックが負荷時に外れても(#1516)、実物の子が useI18n を
// 呼んで落ちないようにする。
jest.mock('../I18nProvider', () => ({
  I18nProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useI18n: () => ({ t: (key: string) => key, locale: 'ja' }),
}))
jest.mock('../SessionProvider', () => ({ SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../globals.css', () => ({}), { virtual: true })
jest.mock('../ThemeScript', () => ({ ThemeScript: () => <i data-testid="theme-script" /> }))

import RootLayout from '../layout'

async function render(session: unknown): Promise<string> {
  getSession.mockResolvedValue(session)
  const element = (await RootLayout({ children: <p>child</p> })) as ReactElement
  return renderToStaticMarkup(element)
}

describe('RootLayout theme script placement (#1238)', () => {
  it('renders ThemeScript as a body child and no <script> in the React tree', async () => {
    const html = await render(null)
    expect(html.indexOf('<body')).toBeLessThan(html.indexOf('data-testid="theme-script"'))
    expect(html).not.toContain('<script')
    expect(html).toContain('child')
    expect(html).not.toContain('<nav')
  })

  it('shows the nav for a regular user', async () => {
    const html = await render({ user: { role: 'user', email: 'u@example.com' } })
    expect(html).toContain('u@example.com')
    expect(html).toContain('<nav')
  })

  it('adds admin nav items for an admin', async () => {
    const user = await render({ user: { role: 'user', email: 'a@example.com' } })
    const admin = await render({ user: { role: 'admin', email: 'a@example.com' } })
    const count = (h: string) => Number(/data-count="(\d+)"/.exec(h)?.[1])
    expect(count(admin)).toBeGreaterThan(count(user))
  })
})
