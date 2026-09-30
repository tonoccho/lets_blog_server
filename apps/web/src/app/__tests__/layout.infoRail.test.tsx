/**
 * @jest-environment node
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

let mockSession: unknown = { user: { role: 'user', email: 'u@example.com' } }

jest.mock('next/font/google', () => ({
  Geist: () => ({ variable: 'geist' }),
  Geist_Mono: () => ({ variable: 'geist-mono' }),
}))
jest.mock('next/navigation', () => ({ useServerInsertedHTML: jest.fn(), usePathname: () => '/' }))
jest.mock('@/lib/session', () => ({ getSession: () => Promise.resolve(mockSession) }))
jest.mock('../SideNav', () => ({
  SideNavProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SideNavToggle: () => null,
  SideNav: () => <aside data-testid="side-nav" />,
}))
jest.mock('../InfoRail', () => ({ InfoRail: () => <aside data-testid="info-rail" /> }))
jest.mock('../LogoutButton', () => ({ LogoutButton: () => null }))
jest.mock('../ThemeSwitcher', () => ({ ThemeSwitcher: () => null }))
jest.mock('../LanguageSwitcher', () => ({ LanguageSwitcher: () => null }))
jest.mock('../DownloadMenu', () => ({ DownloadMenu: () => null }))
jest.mock('../I18nProvider', () => ({ I18nProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../SessionProvider', () => ({ SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../globals.css', () => ({}), { virtual: true })
jest.mock('../ThemeScript', () => ({ ThemeScript: () => null }))
jest.mock('../Footer', () => ({ Footer: () => <footer data-testid="footer" /> }), { virtual: true })

import RootLayout from '../layout'

describe('RootLayout info rail (#1489)', () => {
  it('places the rail right of main, inside the middle row, when logged in', async () => {
    mockSession = { user: { role: 'user', email: 'u@example.com' } }
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    const rail = html.indexOf('data-testid="info-rail"')
    expect(rail).toBeGreaterThan(html.indexOf('</main>'))
    expect(rail).toBeLessThan(html.indexOf('<footer'))
  })

  it('does not render the rail when logged out', async () => {
    mockSession = null
    const html = renderToStaticMarkup((await RootLayout({ children: <p>c</p> })) as ReactElement)
    expect(html).not.toContain('info-rail')
    expect(html).toContain('<main')
  })
})
