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
jest.mock('@/lib/session', () => ({ getSession: () => Promise.resolve({ user: { role: 'user', email: 'u@example.com' } }) }))
jest.mock('../HeaderNav', () => ({ HeaderNav: () => <nav /> }))
jest.mock('../LogoutButton', () => ({ LogoutButton: () => null }))
jest.mock('../ThemeSwitcher', () => ({ ThemeSwitcher: () => null }))
jest.mock('../LanguageSwitcher', () => ({ LanguageSwitcher: () => null }))
jest.mock('../VscodeExtensionDownloadIconButton', () => ({ VscodeExtensionDownloadIconButton: () => null }))
jest.mock('../I18nProvider', () => ({ I18nProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../SessionProvider', () => ({ SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
jest.mock('../globals.css', () => ({}), { virtual: true })
jest.mock('../ThemeScript', () => ({ ThemeScript: () => null }))
jest.mock('../Footer', () => ({ Footer: () => <footer data-testid="footer" /> }), { virtual: true })

import RootLayout from '../layout'

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
})
