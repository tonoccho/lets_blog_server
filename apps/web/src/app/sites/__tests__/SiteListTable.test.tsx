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

  it('filters unbound projects only(issue #944: aria-label付きselectで絞り込む)', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" />
    )
    fireEvent.change(screen.getByLabelText('プロジェクト紐付け状況で絞り込む'), {
      target: { value: 'UNBOUND' },
    })
    expect(screen.queryByText('Test Site 1')).not.toBeInTheDocument()
    expect(screen.getByText('Test Site 2')).toBeInTheDocument()
  })

  it('CMS種別で絞り込める(issue #944: aria-label付きselect)', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" />
    )
    fireEvent.change(screen.getByLabelText('CMS種別で絞り込む'), { target: { value: 'WORDPRESS' } })
    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
    expect(screen.getByText('Test Site 2')).toBeInTheDocument()
  })

  it('絞り込んだ結果が0件のとき「全n件」とだけ表示する', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" />
    )
    fireEvent.change(screen.getByPlaceholderText(/サイトキー・表示名・URLで検索/), {
      target: { value: '該当なし' },
    })
    expect(screen.getByText('全2件')).toBeInTheDocument()
  })

  it('表示名の列見出しをクリックすると並び替え、再クリックで昇順/降順が切り替わる', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" />
    )
    const nameHeader = screen.getByText('表示名').closest('th') as HTMLElement
    fireEvent.click(nameHeader)
    fireEvent.click(nameHeader)

    const createdHeader = screen.getByText('登録日').closest('th') as HTMLElement
    fireEvent.click(createdHeader)

    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
  })

  it('テスト環境・本番環境に紐付いたサイトのプロジェクト表示も出る', () => {
    const projects: Project[] = [
      {
        ...mockProjects[0],
        localSite: null,
        testSite: mockSites[0],
        productionSite: mockSites[1],
      },
    ]
    render(<SiteListTable sites={mockSites} projects={projects} isAdmin={false} timezone="Asia/Tokyo" />)
    expect(screen.getAllByText('Project 1').length).toBe(2)
  })
})
