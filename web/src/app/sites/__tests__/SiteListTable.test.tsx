import { render, screen, fireEvent } from '@testing-library/react'
import { ReactNode } from 'react'
import { SiteListTable } from '../SiteListTable'
import type { Site, Project } from '@/lib/apiClient'

jest.mock('@/lib/formatDate', () => ({
  formatDateTime: (date: string) => '2024-01-01 10:00',
}))

jest.mock('../DeleteSiteButton', () => ({
  DeleteSiteButton: () => <div>Delete Button</div>,
}))

jest.mock('../CheckConnectionButton', () => ({
  CheckConnectionButton: () => <button>Check Connection</button>,
}))

jest.mock('next/link', () => {
  const Link = ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>
  Link.displayName = 'MockLink'
  return Link
})

const mockSites: Site[] = [
  {
    id: 1,
    siteKey: 'test-site-1',
    name: 'Test Site 1',
    baseUrl: 'https://test1.example.com',
    cmsType: 'WORDPRESS',
    managedWordpress: false,
    connectionCheckStatus: null,
    sshConfigured: false,
    createdAt: '2024-01-01T10:00:00Z',
    updatedAt: '2024-01-01T10:00:00Z',
  },
  {
    id: 2,
    siteKey: 'test-site-2',
    name: 'Test Site 2',
    baseUrl: 'https://test2.example.com',
    cmsType: 'WORDPRESS',
    managedWordpress: false,
    connectionCheckStatus: null,
    sshConfigured: false,
    createdAt: '2024-01-02T10:00:00Z',
    updatedAt: '2024-01-02T10:00:00Z',
  },
]

const mockProjects: Project[] = [
  {
    id: 1,
    name: 'Project 1',
    slug: 'project-1',
    localSite: mockSites[0],
    testSite: null,
    productionSite: null,
    masterEnvironment: 'production',
    githubRepository: null,
    createdAt: '2024-01-01T10:00:00Z',
    updatedAt: '2024-01-01T10:00:00Z',
  },
]

describe('SiteListTable', () => {
  it('renders sites table', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={false}
        timezone="Asia/Tokyo"
      />
    )
    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
    expect(screen.getByText('Test Site 2')).toBeInTheDocument()
  })

  it('displays empty state when no sites', () => {
    render(
      <SiteListTable
        sites={[]}
        projects={[]}
        isAdmin={false}
        timezone="Asia/Tokyo"
      />
    )
    expect(screen.getByText('登録済みサイトはありません')).toBeInTheDocument()
  })

  it('filters sites by search text', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={false}
        timezone="Asia/Tokyo"
      />
    )
    const searchInput = screen.getByPlaceholderText(/サイトキー・表示名・URLで検索/)
    fireEvent.change(searchInput, { target: { value: 'Test Site 1' } })
    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
    expect(screen.queryByText('Test Site 2')).not.toBeInTheDocument()
  })

  it('filters bound and unbound projects', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={false}
        timezone="Asia/Tokyo"
      />
    )
    const projectSelect = screen.getByDisplayValue(/プロジェクト紐付け: すべて/)
    fireEvent.change(projectSelect, { target: { value: 'BOUND' } })
    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
  })

  it('shows site count', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={false}
        timezone="Asia/Tokyo"
      />
    )
    expect(screen.getByText(/2件を表示.*全2件中/)).toBeInTheDocument()
  })

  it('renders admin columns when isAdmin is true', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={true}
        timezone="Asia/Tokyo"
      />
    )
    const editLinks = screen.getAllByText('管理')
    expect(editLinks.length).toBeGreaterThan(0)
  })
})
