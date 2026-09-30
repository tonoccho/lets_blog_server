import { render, screen, fireEvent, within, act } from '@testing-library/react'
import { ReactNode } from 'react'
import { SideNav, SideNavProvider, SideNavToggle } from '../SideNav'
import { I18nProvider } from '../I18nProvider'
import type { NavItem } from '@/lib/navigation'

let mockPathname = '/'
jest.mock('next/navigation', () => ({ usePathname: () => mockPathname }))

jest.mock('next/link', () => {
  const Link = ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  )
  Link.displayName = 'MockLink'
  return Link
})

const navItems: NavItem[] = [
  { href: '/', labelKey: 'dashboard', icon: 'Home' },
  { href: '/sites', labelKey: 'sites', icon: 'Globe' },
  { href: '/projects', labelKey: 'projects', icon: 'Folder' },
  { href: '/admin/roles', labelKey: 'roleManagement', icon: 'Shield', group: 'admin' },
]

function setup(items: NavItem[] = navItems, locale: 'ja' | 'en' = 'ja') {
  return render(
    <I18nProvider initialLocale={locale}>
      <SideNavProvider>
        <SideNavToggle />
        <SideNav navItems={items} />
      </SideNavProvider>
    </I18nProvider>
  )
}

beforeEach(() => {
  mockPathname = '/'
  window.localStorage.clear()
})

describe('SideNav', () => {
  it('lists regular and admin items directly, without an admin dropdown', () => {
    setup()
    const aside = screen.getByTestId('side-nav')
    expect(within(aside).getByRole('link', { name: 'ダッシュボード' })).toHaveAttribute('href', '/')
    expect(within(aside).getByRole('link', { name: 'ロール管理' })).toBeVisible()
    expect(within(aside).getByText('管理')).toBeInTheDocument()
    expect(screen.queryByLabelText('管理メニューを開く')).not.toBeInTheDocument()
  })

  it('shows no admin section when no admin item is passed', () => {
    setup(navItems.filter((i) => i.group !== 'admin'))
    expect(screen.queryByText('管理')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'ロール管理' })).not.toBeInTheDocument()
  })

  it('marks only the current page as selected (exact match for /)', () => {
    mockPathname = '/sites'
    setup()
    const aside = screen.getByTestId('side-nav')
    expect(within(aside).getByRole('link', { name: 'サイト' })).toHaveAttribute('aria-current', 'page')
    expect(within(aside).getByRole('link', { name: 'ダッシュボード' })).not.toHaveAttribute('aria-current')
  })

  it('keeps the parent selected on a nested path but not on a sibling prefix', () => {
    mockPathname = '/projects/12/edit'
    setup()
    const aside = screen.getByTestId('side-nav')
    expect(within(aside).getByRole('link', { name: 'プロジェクト' })).toHaveAttribute('aria-current', 'page')
    expect(within(aside).getByRole('link', { name: 'ダッシュボード' })).not.toHaveAttribute('aria-current')
    mockPathname = '/projects-old'
  })

  it('does not select anything for a path outside the menu, and tolerates a null pathname', () => {
    mockPathname = '/unknown'
    const { unmount } = setup()
    expect(document.querySelector('[aria-current]')).toBeNull()
    unmount()
    ;(mockPathname as string | null) = null
    setup()
    expect(document.querySelector('[aria-current]')).toBeNull()
  })

  it('collapses to icon-only, keeps accessible names, and persists the state', () => {
    const { unmount } = setup()
    const aside = screen.getByTestId('side-nav')
    expect(aside).toHaveAttribute('data-collapsed', 'false')
    fireEvent.click(within(aside).getByRole('button', { name: 'メニューを折りたたむ' }))
    expect(aside).toHaveAttribute('data-collapsed', 'true')
    expect(within(aside).getByRole('link', { name: 'ダッシュボード' })).toBeInTheDocument()
    expect(window.localStorage.getItem('sideNavCollapsed')).toBe('true')
    unmount()

    setup()
    expect(screen.getByTestId('side-nav')).toHaveAttribute('data-collapsed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'メニューを展開する' }))
    expect(screen.getByTestId('side-nav')).toHaveAttribute('data-collapsed', 'false')
    expect(window.localStorage.getItem('sideNavCollapsed')).toBe('false')
  })

  it('survives unreadable and unwritable storage', () => {
    const getItem = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    const setItem = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    setup()
    const aside = screen.getByTestId('side-nav')
    fireEvent.click(within(aside).getByRole('button', { name: 'メニューを折りたたむ' }))
    expect(aside).toHaveAttribute('data-collapsed', 'true')
    getItem.mockRestore()
    setItem.mockRestore()
    fireEvent.click(within(aside).getByRole('button', { name: 'メニューを展開する' }))
    expect(aside).toHaveAttribute('data-collapsed', 'false')
  })

  it('opens and closes the drawer from the toggle, and closes it with Escape', () => {
    setup()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'メニューを開く' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const dialog = screen.getByRole('dialog', { name: 'ナビゲーション' })
    expect(within(dialog).getByRole('link', { name: 'ロール管理' })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'メニューを閉じる' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'メニューを開く' }))
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    act(() => {
      fireEvent.keyDown(document, { key: 'Escape' })
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('does not register an Escape listener while the drawer is closed', () => {
    const add = jest.spyOn(document, 'addEventListener')
    setup()
    expect(add.mock.calls.filter(([type]) => type === 'keydown')).toHaveLength(0)
    add.mockRestore()
  })

  it('closes the drawer when a link is chosen or the backdrop is clicked', () => {
    setup()
    fireEvent.click(screen.getByRole('button', { name: 'メニューを開く' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('link', { name: 'サイト' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'メニューを開く' }))
    fireEvent.click(screen.getByTestId('side-nav-backdrop'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('renders every label in English when the locale is en', () => {
    setup(navItems, 'en')
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    const dialog = screen.getByRole('dialog', { name: 'Navigation' })
    expect(within(dialog).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Role Management' })).toBeInTheDocument()
    expect(screen.queryByText('ダッシュボード')).not.toBeInTheDocument()
  })

  it('throws when SideNavToggle is used outside the provider', () => {
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {})
    expect(() =>
      render(
        <I18nProvider initialLocale="ja">
          <SideNavToggle />
        </I18nProvider>
      )
    ).toThrow()
    spy.mockRestore()
  })
})
