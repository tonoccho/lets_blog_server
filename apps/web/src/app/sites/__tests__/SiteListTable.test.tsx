import { render, screen, fireEvent } from '@testing-library/react'
import { ReactNode } from 'react'
import { SiteListTable } from '../SiteListTable'
import type { Site, Project } from '@/lib/apiClient'

jest.mock('@/components/ViewerDateTime', () => ({
  ViewerDateTime: ({ iso, personalTimeZone }: { iso: string; personalTimeZone: string | null }) => (
    <span data-testid="viewer-datetime">{`VDT(${iso}|${personalTimeZone})`}</span>
  ),
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
  it('renders each site createdAt through ViewerDateTime with the timezone as personalTimeZone (issue #1367)', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
    )

    const cells = screen.getAllByTestId('viewer-datetime').map((el) => el.textContent)
    expect(cells).toEqual([`VDT(${mockSites[0].createdAt}|Asia/Tokyo)`, `VDT(${mockSites[1].createdAt}|Asia/Tokyo)`])
  })

  it('passes null timezone through as personalTimeZone (issue #1367)', () => {
    render(
      <SiteListTable sites={[mockSites[0]]} projects={mockProjects} isAdmin={false} timezone={null} adminPath="wp-admin" />
    )

    expect(screen.getByTestId('viewer-datetime')).toHaveTextContent(`VDT(${mockSites[0].createdAt}|null)`)
  })

  it('renders sites table', () => {
    render(
      <SiteListTable
        sites={mockSites}
        projects={mockProjects}
        isAdmin={false}
        timezone="Asia/Tokyo"
        adminPath="wp-admin"
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
        adminPath="wp-admin"
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
        adminPath="wp-admin"
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
        adminPath="wp-admin"
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
        adminPath="wp-admin"
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
        adminPath="wp-admin"
      />
    )
    const editLinks = screen.getAllByText('管理')
    expect(editLinks.length).toBeGreaterThan(0)
  })

  it('filters unbound projects only(issue #944: aria-label付きselectで絞り込む)', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
    )
    fireEvent.change(screen.getByLabelText('プロジェクト紐付け状況で絞り込む'), {
      target: { value: 'UNBOUND' },
    })
    expect(screen.queryByText('Test Site 1')).not.toBeInTheDocument()
    expect(screen.getByText('Test Site 2')).toBeInTheDocument()
  })

  it('CMS種別で絞り込める(issue #944: aria-label付きselect)', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
    )
    fireEvent.change(screen.getByLabelText('CMS種別で絞り込む'), { target: { value: 'WORDPRESS' } })
    expect(screen.getByText('Test Site 1')).toBeInTheDocument()
    expect(screen.getByText('Test Site 2')).toBeInTheDocument()
  })

  it('絞り込んだ結果が0件のとき「全n件」とだけ表示する', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
    )
    fireEvent.change(screen.getByPlaceholderText(/サイトキー・表示名・URLで検索/), {
      target: { value: '該当なし' },
    })
    expect(screen.getByText('全2件')).toBeInTheDocument()
  })

  it('表示名の列見出しをクリックすると並び替え、再クリックで昇順/降順が切り替わる', () => {
    render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
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
    render(<SiteListTable sites={mockSites} projects={projects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />)
    expect(screen.getAllByText('Project 1').length).toBe(2)
  })

  describe('リンク列(issue #1529)', () => {
    const renderTable = (adminPath = 'wp-admin') =>
      render(<SiteListTable sites={mockSites} projects={[]} isAdmin={false} timezone="Asia/Tokyo" adminPath={adminPath} />)

    it('公開URLの文字列をテキストとして表示しない', () => {
      renderTable()
      expect(screen.queryByText('https://test1.example.com')).not.toBeInTheDocument()
      expect(screen.queryByText('https://test2.example.com')).not.toBeInTheDocument()
    })

    it('サイトを開くリンクは公開URLを新しいタブで開く', () => {
      renderTable()
      const link = screen.getByRole('link', { name: 'Test Site 1 のサイトを開く' })
      expect(link).toHaveAttribute('href', 'https://test1.example.com')
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAttribute('rel', 'noreferrer')
      expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    })

    it('管理画面を開くリンクは <公開URL>/wp-admin を新しいタブで開く', () => {
      renderTable()
      const link = screen.getByRole('link', { name: 'Test Site 2 の管理画面を開く' })
      expect(link).toHaveAttribute('href', 'https://test2.example.com/wp-admin')
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAttribute('rel', 'noreferrer')
      expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    })

    it('両アイコンリンクに遷移先 URL を title として付ける(issue #1531)', () => {
      renderTable()
      expect(screen.getByRole('link', { name: 'Test Site 1 のサイトを開く' })).toHaveAttribute('title', 'https://test1.example.com')
      expect(screen.getByRole('link', { name: 'Test Site 2 の管理画面を開く' })).toHaveAttribute(
        'title',
        'https://test2.example.com/wp-admin'
      )
    })

    it('両アイコンリンクに可視のフォーカスリングのクラスが付く(issue #1531)', () => {
      renderTable()
      for (const name of ['Test Site 1 のサイトを開く', 'Test Site 1 の管理画面を開く']) {
        expect(screen.getByRole('link', { name })).toHaveClass(
          'focus-visible:outline-2',
          'focus-visible:outline-offset-2',
          'focus-visible:outline-blue-500'
        )
      }
    })

    it('管理画面パスが別オリジンを指して解決できないときは管理画面リンクだけ描画しない', () => {
      renderTable('https://evil.example.com/wp-admin')
      expect(screen.queryByRole('link', { name: /管理画面を開く/ })).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Test Site 1 のサイトを開く' })).toBeInTheDocument()
    })

    it('サイト個別の管理画面パスがあるサイトは <公開URL>/<個別パス> を開き、null のサイトはグローバル既定にフォールバックする(issue #1534)', () => {
      const sites: Site[] = [
        { ...mockSites[0], adminPath: 'secret-login' },
        { ...mockSites[1], adminPath: null },
      ]
      render(<SiteListTable sites={sites} projects={[]} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />)
      expect(screen.getByRole('link', { name: 'Test Site 1 の管理画面を開く' })).toHaveAttribute(
        'href',
        'https://test1.example.com/secret-login'
      )
      expect(screen.getByRole('link', { name: 'Test Site 2 の管理画面を開く' })).toHaveAttribute(
        'href',
        'https://test2.example.com/wp-admin'
      )
    })

    it('公開URLの一部で検索すると、そのサイトだけに絞り込まれる', () => {
      renderTable()
      fireEvent.change(screen.getByPlaceholderText('サイトキー・表示名・URLで検索'), { target: { value: 'test1' } })
      expect(screen.getByText('Test Site 1')).toBeInTheDocument()
      expect(screen.queryByText('Test Site 2')).not.toBeInTheDocument()
    })
  })
})
