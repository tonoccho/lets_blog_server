import { render, screen } from '@testing-library/react'
import { Footer } from '../Footer'
import { I18nProvider } from '../I18nProvider'
import ja from '../../../messages/ja.json'
import en from '../../../messages/en.json'

describe('Footer (#1487)', () => {
  afterEach(() => window.localStorage.clear())

  it('renders a <footer> with the fixed copyright from messages (ja)', () => {
    render(<I18nProvider><Footer /></I18nProvider>)
    const footer = screen.getByRole('contentinfo')
    expect(footer).toHaveTextContent("© 2025-2026 Let's Blog Server")
    expect(footer).toHaveTextContent((ja as { footer: { copyright: string } }).footer.copyright)
  })

  it('renders the en copyright when the locale is en', () => {
    window.localStorage.setItem('locale', 'en')
    render(<I18nProvider><Footer /></I18nProvider>)
    expect(screen.getByRole('contentinfo')).toHaveTextContent(
      (en as { footer: { copyright: string } }).footer.copyright
    )
  })

  it('keeps AA contrast classes for light and dark themes and has no width cap', () => {
    render(<I18nProvider><Footer /></I18nProvider>)
    const cls = screen.getByRole('contentinfo').className
    expect(cls).toContain('text-neutral-600')
    expect(cls).toContain('dark:text-neutral-400')
    expect(cls).not.toMatch(/max-w-/)
  })
})
