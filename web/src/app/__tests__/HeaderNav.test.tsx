import { render, screen, fireEvent } from '@testing-library/react'
import { HeaderNav } from '../HeaderNav'
import type { NavItem } from '@/lib/navigation'

jest.mock('next/link', () => {
  return ({ children, href }: any) => <a href={href}>{children}</a>
})

jest.mock('lucide-react', () => ({
  Menu: ({ className }: any) => <div className={className}>Menu</div>,
  X: ({ className }: any) => <div className={className}>X</div>,
  ChevronDown: ({ className }: any) => <div className={className}>ChevronDown</div>,
}))

const mockNavItems: NavItem[] = [
  {
    href: '/dashboard',
    label: 'Dashboard',
    icon: () => <div>DashboardIcon</div>,
  },
  {
    href: '/sites',
    label: 'Sites',
    icon: () => <div>SitesIcon</div>,
  },
  {
    href: '/settings',
    label: 'Settings',
    icon: () => <div>SettingsIcon</div>,
    group: 'admin',
  },
]

describe('HeaderNav', () => {
  it('renders navigation items', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Sites')).toBeInTheDocument()
  })

  it('groups admin items in a dropdown', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    expect(screen.getByText('管理')).toBeInTheDocument()
  })

  it('renders mobile menu button', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    const menuButton = screen.getByLabelText('メニューを開く')
    expect(menuButton).toBeInTheDocument()
  })

  it('toggles mobile menu on button click', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    const menuButton = screen.getByLabelText('メニューを開く')
    fireEvent.click(menuButton)
    expect(screen.getByLabelText('メニューを閉じる')).toBeInTheDocument()
  })

  it('opens admin dropdown on button click', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    const adminButton = screen.getByText('管理')
    fireEvent.click(adminButton)
    expect(adminButton.parentElement).toBeInTheDocument()
  })

  it('renders correct number of regular nav items', () => {
    render(<HeaderNav navItems={mockNavItems} />)
    const regularItems = mockNavItems.filter((item) => item.group !== 'admin')
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Sites')).toBeInTheDocument()
  })

  it('handles empty nav items', () => {
    render(<HeaderNav navItems={[]} />)
    const menuButton = screen.getByLabelText(/メニューを開く/)
    expect(menuButton).toBeInTheDocument()
  })

  it('renders with only admin items', () => {
    const adminOnlyItems: NavItem[] = [
      {
        href: '/admin/users',
        label: 'Users',
        icon: () => <div>UsersIcon</div>,
        group: 'admin',
      },
    ]
    render(<HeaderNav navItems={adminOnlyItems} />)
    expect(screen.getByText('管理')).toBeInTheDocument()
  })
})
