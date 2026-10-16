import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { SiteListTable } from '../SiteListTable'
import type { Site, Project } from '@/lib/apiClient'

/**
 * 自前の ViewerDateTime・DeleteSiteButton・CheckConnectionButton・Server Action・formatDate は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
 */
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))

import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'
import { installFetch, jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime'

/** 削除 API(gateway の向こう)に届いた、削除対象のサイトID。 */
const deleted: number[] = []

beforeEach(() => {
  deleted.length = 0
  resetServerRuntime(ADMIN_SESSION)
  installFetch({
    'DELETE /api/sites/:id': (_url, _init, [id]) => {
      deleted.push(Number(id))
      return { ...jsonResponse(null, 204), text: async () => '' } as unknown as Response
    },
  })
})

/** 登録日の列(0 始まりで 5 番目)の表示を、行ごとに取り出す。 */
const createdCells = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('tbody tr')).map((row) => (row.querySelectorAll('td')[5]?.textContent ?? '').trim())
const siteKeys = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('tbody tr')).map((row) => (row.querySelector('td')?.textContent ?? '').trim())

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
  it('renders each site createdAt formatted in the personal timezone (issue #1367)', () => {
    const { container } = render(
      <SiteListTable sites={mockSites} projects={mockProjects} isAdmin={false} timezone="Asia/Tokyo" adminPath="wp-admin" />
    )

    expect(createdCells(container)).toEqual(['2024/1/1 19:00:00', '2024/1/2 19:00:00'])
  })

  it('falls back to the browser timezone (UTC in tests) when the personal timezone is null (issue #1367)', () => {
    const { container } = render(
      <SiteListTable sites={[mockSites[0]]} projects={mockProjects} isAdmin={false} timezone={null} adminPath="wp-admin" />
    )

    expect(createdCells(container)).toEqual(['2024/1/1 10:00:00'])
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
    expect(nameHeader).toHaveTextContent('表示名 ↑')
    fireEvent.click(nameHeader)
    expect(nameHeader).toHaveTextContent('表示名 ↓')

    const createdHeader = screen.getByText('登録日').closest('th') as HTMLElement
    fireEvent.click(createdHeader)
    expect(createdHeader).toHaveTextContent('登録日 ↑')
    expect(nameHeader).not.toHaveTextContent('↓')

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

  describe('並び替え', () => {
    const rows = (sites: Site[]) => {
      const { container } = render(
        <SiteListTable sites={sites} projects={[]} isAdmin={false} timezone="UTC" adminPath="wp-admin" />
      )
      return container
    }
    const a = { ...mockSites[0], siteKey: 'a', name: 'B名', createdAt: '2024-03-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' }
    const b = { ...mockSites[1], siteKey: 'b', name: 'A名', createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-05-01T00:00:00Z' }

    it('表示名の昇順・降順で並べ替える', () => {
      const container = rows([a, b])
      expect(siteKeys(container)).toEqual(['a', 'b'])
      fireEvent.click(screen.getByText('表示名').closest('th') as HTMLElement)
      expect(siteKeys(container)).toEqual(['b', 'a'])
      fireEvent.click(screen.getByText(/表示名/).closest('th') as HTMLElement)
      expect(siteKeys(container)).toEqual(['a', 'b'])
    })

    it('登録日の昇順・降順で並べ替える', () => {
      const container = rows([a, b])
      fireEvent.click(screen.getByText('登録日').closest('th') as HTMLElement)
      expect(siteKeys(container)).toEqual(['b', 'a'])
      fireEvent.click(screen.getByText(/登録日/).closest('th') as HTMLElement)
      expect(siteKeys(container)).toEqual(['a', 'b'])
      fireEvent.click(screen.getByText(/登録日/).closest('th') as HTMLElement)
      expect(siteKeys(container)).toEqual(['b', 'a'])
    })
  })

  describe('自動構築・環境の表示', () => {
    it('自動構築されたサイトに「自動構築」を表示する', () => {
      render(
        <SiteListTable
          sites={[{ ...mockSites[0], managedWordpress: true }]}
          projects={[]}
          isAdmin={false}
          timezone="UTC"
          adminPath="wp-admin"
        />
      )
      expect(screen.getByText('自動構築')).toBeInTheDocument()
    })

    it('ローカル環境に紐付いたサイトにはローカルと本番のバッジを区別して出す', () => {
      const projects: Project[] = [{ ...mockProjects[0], localSite: mockSites[0], productionSite: mockSites[1] }]
      render(<SiteListTable sites={mockSites} projects={projects} isAdmin={false} timezone="UTC" adminPath="wp-admin" />)
      expect(screen.getByText('ローカル')).toBeInTheDocument()
      expect(screen.getByText('本番')).toBeInTheDocument()
    })

    it('CMS種別で絞り込むと、別のCMS種別のサイトは除外される(WORDPRESS 以外の種別は灰緑のバッジ)', () => {
      const other = { ...mockSites[1], cmsType: 'OTHER' } as unknown as Site
      render(<SiteListTable sites={[mockSites[0], other]} projects={[]} isAdmin={false} timezone="UTC" adminPath="wp-admin" />)
      expect(screen.getByText('OTHER')).toHaveClass('bg-green-100')
      fireEvent.change(screen.getByLabelText('CMS種別で絞り込む'), { target: { value: 'WORDPRESS' } })
      expect(screen.queryByText('Test Site 2')).not.toBeInTheDocument()
      expect(screen.getByText('Test Site 1')).toBeInTheDocument()
    })

    it('絞り込みに一致するサイトが無いとき「条件に一致するサイトはありません」を表示する', () => {
      render(<SiteListTable sites={mockSites} projects={[]} isAdmin={false} timezone="UTC" adminPath="wp-admin" />)
      fireEvent.change(screen.getByPlaceholderText(/サイトキー・表示名・URLで検索/), { target: { value: '該当なし' } })
      expect(screen.getByText('条件に一致するサイトはありません')).toBeInTheDocument()
    })
  })

  describe('操作列', () => {
    it('管理者でなければ管理リンクと削除ボタンを出さない', () => {
      render(<SiteListTable sites={mockSites} projects={[]} isAdmin={false} timezone="UTC" adminPath="wp-admin" />)
      expect(screen.queryByText('管理')).toBeNull()
      expect(screen.queryByRole('button', { name: '削除' })).toBeNull()
      expect(screen.getAllByRole('button', { name: '疎通確認' })).toHaveLength(2)
    })

    it('管理者には編集画面への管理リンクを出す', () => {
      render(<SiteListTable sites={mockSites} projects={[]} isAdmin={true} timezone="UTC" adminPath="wp-admin" />)
      expect(screen.getAllByText('管理')[0]).toHaveAttribute('href', '/sites/1/edit')
    })

    it('削除を確認したらそのサイトを削除する', async () => {
      const confirm = jest.spyOn(window, 'confirm').mockReturnValue(true)
      const { container } = render(
        <SiteListTable sites={mockSites} projects={[]} isAdmin={true} timezone="UTC" adminPath="wp-admin" />
      )
      const row = container.querySelectorAll('tbody tr')[1] as HTMLElement
      fireEvent.click(within(row).getByRole('button', { name: '削除' }))
      await waitFor(() => expect(deleted).toEqual([2]))
      expect(confirm).toHaveBeenCalledWith(expect.stringContaining('登録情報のみ削除され'))
      confirm.mockRestore()
    })

    it('自動構築されたサイトの削除では、構築物も削除される旨を確認し、取り消したら削除しない', () => {
      const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
      render(
        <SiteListTable
          sites={[{ ...mockSites[0], managedWordpress: true }]}
          projects={[]}
          isAdmin={true}
          timezone="UTC"
          adminPath="wp-admin"
        />
      )
      fireEvent.click(screen.getByRole('button', { name: '削除' }))
      expect(confirm).toHaveBeenCalledWith(expect.stringContaining('自動構築されたWordPressインスタンス'))
      expect(deleted).toEqual([])
      confirm.mockRestore()
    })
  })
})
