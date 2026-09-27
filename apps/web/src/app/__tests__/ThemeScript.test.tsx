import { isValidElement } from 'react'
import { render } from '@testing-library/react'
import { ThemeScript, THEME_INIT_SCRIPT } from '../ThemeScript'

let insertedHtmlCallback: (() => React.ReactNode) | undefined

jest.mock('next/navigation', () => ({
  useServerInsertedHTML: (cb: () => React.ReactNode) => {
    insertedHtmlCallback = cb
  },
}))

/** ThemeSwitcher.resolveEffectiveTheme と同じ規則を、保存値と OS 設定から期待値として書き下したもの。 */
const expectedTheme = (stored: string | null, osDark: boolean): 'light' | 'dark' =>
  stored === null ? (osDark ? 'dark' : 'light') : (stored as 'light' | 'dark')

function runInlineScript(stored: string | null, osDark: boolean): string | null {
  localStorage.clear()
  if (stored !== null) localStorage.setItem('theme', stored)
  document.documentElement.removeAttribute('data-theme')
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: jest.fn().mockImplementation((query: string) => ({
      matches: query.includes('dark') ? osDark : !osDark,
    })),
  })
  new Function(THEME_INIT_SCRIPT)()
  return document.documentElement.getAttribute('data-theme')
}

describe('THEME_INIT_SCRIPT', () => {
  it.each([
    [null, true],
    [null, false],
    ['dark', false],
    ['light', true],
  ])('stored=%s osDark=%s resolves like resolveEffectiveTheme', (stored, osDark) => {
    expect(runInlineScript(stored, osDark)).toBe(expectedTheme(stored, osDark))
  })

  it('does not throw when localStorage is unavailable', () => {
    const spy = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    document.documentElement.removeAttribute('data-theme')
    expect(() => new Function(THEME_INIT_SCRIPT)()).not.toThrow()
    spy.mockRestore()
  })
})

describe('ThemeScript', () => {
  beforeEach(() => {
    insertedHtmlCallback = undefined
  })

  it('renders nothing itself (no script element in the React tree)', () => {
    const { container } = render(<ThemeScript />)
    expect(container.querySelector('script')).toBeNull()
  })

  it('registers a server-inserted callback returning the inline theme script', () => {
    render(<ThemeScript />)
    expect(insertedHtmlCallback).toBeDefined()
    const node = insertedHtmlCallback!()
    expect(isValidElement(node)).toBe(true)
    const el = node as React.ReactElement<{ dangerouslySetInnerHTML: { __html: string } }>
    expect(el.type).toBe('script')
    expect(el.props.dangerouslySetInnerHTML.__html).toBe(THEME_INIT_SCRIPT)
  })

  it('emits the script only on the first invocation of a render (Next calls it per flushed chunk)', () => {
    render(<ThemeScript />)
    const first = insertedHtmlCallback!()
    expect(isValidElement(first)).toBe(true)
    expect(insertedHtmlCallback!()).toBeNull()
    expect(insertedHtmlCallback!()).toBeNull()
  })
})
