import { render, screen } from '@testing-library/react'
import { ReactNode } from 'react'
import { Breadcrumb, type BreadcrumbItem } from '../Breadcrumb'

jest.mock('next/link', () => {
  const Link = ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>
  Link.displayName = 'MockLink'
  return Link
})

describe('Breadcrumb', () => {
  it('renders breadcrumb items', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home', href: '/' },
      { label: 'Settings' },
    ]
    render(<Breadcrumb items={items} />)
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })

  it('renders separators between items', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home', href: '/' },
      { label: 'Settings' },
    ]
    render(<Breadcrumb items={items} />)
    const separators = screen.getAllByText('/')
    expect(separators.length).toBe(1)
  })

  it('makes last item non-clickable', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home', href: '/' },
      { label: 'Settings', href: '/settings' },
    ]
    render(<Breadcrumb items={items} />)
    const homeLink = screen.getByText('Home')
    const settingsSpan = screen.getByText('Settings')
    expect(homeLink).toHaveAttribute('href', '/')
    expect(settingsSpan.tagName).toBe('SPAN')
  })

  it('applies correct styling to last item', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home', href: '/' },
      { label: 'Settings' },
    ]
    render(<Breadcrumb items={items} />)
    const settingsSpan = screen.getByText('Settings')
    expect(settingsSpan).toHaveClass('font-medium', 'text-neutral-900')
  })

  it('has correct aria-label', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home', href: '/' },
    ]
    render(<Breadcrumb items={items} />)
    const nav = screen.getByRole('navigation')
    expect(nav).toHaveAttribute('aria-label', 'パンくずリスト')
  })

  it('renders single item breadcrumb', () => {
    const items: BreadcrumbItem[] = [
      { label: 'Home' },
    ]
    render(<Breadcrumb items={items} />)
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.queryAllByText('/')).toHaveLength(0)
  })
})
