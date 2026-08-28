import { render, screen, fireEvent } from '@testing-library/react'
import { ReactNode } from 'react'
import { HeaderNav } from '../HeaderNav'
import { I18nProvider } from '../I18nProvider'
import type { NavItem } from '@/lib/navigation'

function renderWithI18n(ui: React.ReactElement) {
  return render(<I18nProvider>{ui}</I18nProvider>)
}

jest.mock('next/link', () => {
  const Link = ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>
  Link.displayName = 'MockLink'
  return Link
})

jest.mock('lucide-react', () => ({
  Menu: ({ className }: { className: string }) => <div className={className}>Menu</div>,
  X: ({ className }: { className: string }) => <div className={className}>X</div>,
  ChevronDown: ({ className }: { className: string }) => <div className={className}>ChevronDown</div>,
  Home: ({ className }: { className: string }) => <div className={className}>Home</div>,
  Folder: ({ className }: { className: string }) => <div className={className}>Folder</div>,
  Globe: ({ className }: { className: string }) => <div className={className}>Globe</div>,
  Users: ({ className }: { className: string }) => <div className={className}>Users</div>,
  FileText: ({ className }: { className: string }) => <div className={className}>FileText</div>,
  Shield: ({ className }: { className: string }) => <div className={className}>Shield</div>,
  Images: ({ className }: { className: string }) => <div className={className}>Images</div>,
  DatabaseBackup: ({ className }: { className: string }) => <div className={className}>DatabaseBackup</div>,
  History: ({ className }: { className: string }) => <div className={className}>History</div>,
  Settings: ({ className }: { className: string }) => <div className={className}>Settings</div>,
  KeyRound: ({ className }: { className: string }) => <div className={className}>KeyRound</div>,
}))

const mockNavItems: NavItem[] = [
  {
    href: '/dashboard',
    label: 'Dashboard',
    icon: 'Home',
  },
  {
    href: '/sites',
    label: 'Sites',
    icon: 'Globe',
  },
  {
    href: '/settings',
    label: 'Settings',
    icon: 'Settings',
    group: 'admin',
  },
]

describe('HeaderNav', () => {
  it('renders navigation items', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Sites')).toBeInTheDocument()
  })

  it('groups admin items in a dropdown', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    expect(screen.getByText('管理')).toBeInTheDocument()
  })

  it('renders mobile menu button', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    const menuButton = screen.getByLabelText('メニューを開く')
    expect(menuButton).toBeInTheDocument()
  })

  it('toggles mobile menu on button click', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    const menuButton = screen.getByLabelText('メニューを開く')
    fireEvent.click(menuButton)
    expect(screen.getByLabelText('メニューを閉じる')).toBeInTheDocument()
  })

  it('opens admin dropdown on button click', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    const adminButton = screen.getByText('管理')
    fireEvent.click(adminButton)
    expect(adminButton.parentElement).toBeInTheDocument()
  })

  it('renders correct number of regular nav items', () => {
    renderWithI18n(<HeaderNav navItems={mockNavItems} />)
    const regularItems = mockNavItems.filter((item) => item.group !== 'admin')
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Sites')).toBeInTheDocument()
  })

  it('handles empty nav items', () => {
    renderWithI18n(<HeaderNav navItems={[]} />)
    const menuButton = screen.getByLabelText(/メニューを開く/)
    expect(menuButton).toBeInTheDocument()
  })

  it('renders with only admin items', () => {
    const adminOnlyItems: NavItem[] = [
      {
        href: '/admin/users',
        label: 'Users',
        icon: 'Users',
        group: 'admin',
      },
    ]
    renderWithI18n(<HeaderNav navItems={adminOnlyItems} />)
    expect(screen.getByText('管理')).toBeInTheDocument()
  })
})
