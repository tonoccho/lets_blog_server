import { render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'

const notFound = jest.fn(() => {
  throw new Error('NEXT_NOT_FOUND')
})
jest.mock('next/navigation', () => ({ notFound: () => notFound() }))

const getProject = jest.fn()
const listProjectUsers = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  getProject: (...args: unknown[]) => getProject(...args),
  listSites: jest.fn().mockResolvedValue([]),
  listProjectUsers: (...args: unknown[]) => listProjectUsers(...args),
  listUsers: jest.fn().mockResolvedValue([]),
  listCategoryComparison: jest.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: 'test' }),
  getProjectGithubTokenStatus: jest.fn().mockResolvedValue({ configured: false }),
  getProjectBraveSearchApiKeyStatus: jest.fn().mockResolvedValue({ configured: false }),
  getProjectImageSettings: jest.fn().mockResolvedValue({}),
  getSiteAdminPath: jest.fn().mockResolvedValue({ path: 'wp-admin' }),
}))
jest.mock('@/lib/session', () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue(null),
}))
jest.mock('@/components/Breadcrumb', () => ({ Breadcrumb: () => <nav /> }))
jest.mock('@/components/Tabs', () => ({
  Tabs: ({ tabs, defaultTabId }: { tabs: { id: string; label: string; content: ReactNode }[]; defaultTabId?: string }) => (
    <div data-default-tab={defaultTabId ?? ''}>
      {tabs.map((t) => <button key={t.id}>{t.label}</button>)}
      {/* 既定で開くのは先頭タブ(概要)。本物の Tabs と同じく先頭タブの中身だけを描く。 */}
      <section data-testid="active-tab">{tabs[0].content}</section>
      {/* 「AI・アセット」タブ(issue #1408: ?imageJob の受け渡しを見る)。 */}
      <section data-testid="ai-models-tab">{tabs.find((t) => t.id === 'ai-models')?.content}</section>
    </div>
  ),
}))
// 子コンポーネントは自前のテストがある。ここは page.tsx の組み立てだけを見る。
jest.mock('../ProjectSectionNav', () => ({ ProjectSectionNav: () => null }))
jest.mock('../EnvironmentSlot', () => ({ EnvironmentSlot: () => null }))
jest.mock('../MasterEnvironmentSelector', () => ({ MasterEnvironmentSelector: () => null }))
jest.mock('../ProjectGithubRepositoryForm', () => ({ ProjectGithubRepositoryForm: () => null }))
jest.mock('../ProjectApiKeysForm', () => ({ ProjectApiKeysForm: () => null }))
jest.mock('../EnvironmentSyncPanel', () => ({ EnvironmentSyncPanel: () => null }))
jest.mock('../BulkManagementPanel', () => ({ BulkManagementPanel: () => null }))
jest.mock('../GarbageCollectionPanel', () => ({ GarbageCollectionPanel: () => null }))
jest.mock('../ProjectAiModelsPanel', () => ({ ProjectAiModelsPanel: () => null }))
jest.mock('../ProjectAssetGenerationPanel', () => ({
  ProjectAssetGenerationPanel: ({ imageJobId }: { imageJobId?: number }) => (
    <div data-testid="asset-panel" data-image-job-id={imageJobId ?? ''} />
  ),
}))
jest.mock('../ProjectImageGenerationPromptDefaultsForm', () => ({ ProjectImageGenerationPromptDefaultsForm: () => null }))
jest.mock('../ProjectImageGenerationSizeDefaultsForm', () => ({ ProjectImageGenerationSizeDefaultsForm: () => null }))
jest.mock('../ProjectArticleImageResizeDefaultForm', () => ({ ProjectArticleImageResizeDefaultForm: () => null }))
jest.mock('../ProjectImageContentFilterSettingsForm', () => ({ ProjectImageContentFilterSettingsForm: () => null }))
jest.mock('../ProjectNameForm', () => ({ ProjectNameForm: () => null }))
jest.mock('../DeleteProjectButton', () => ({ DeleteProjectButton: () => null }))
jest.mock('../ProjectUserManager', () => ({ ProjectUserManager: () => null }))
jest.mock('../AddProjectUserModal', () => ({ AddProjectUserModal: () => <form data-testid="add-member-form" /> }))

import ProjectDetailPage from '../(detail)/page'

describe('プロジェクト詳細画面 page.tsx(issue #1475: (detail) ルートグループへ移しても最終表示は同じ)', () => {
  beforeEach(() => {
    notFound.mockClear()
    getProject.mockReset()
    listProjectUsers.mockReset()
    listProjectUsers.mockResolvedValue([])
  })

  it('取得したプロジェクトの名前と一括管理タブを描く', async () => {
    getProject.mockResolvedValue({ id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null })
    render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
    expect(screen.getByRole('heading', { name: 'サンプル案件' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '一括管理' })).toBeInTheDocument()
    expect(notFound).not.toHaveBeenCalled()
  })

  it('概要タブに SNS 告知の設定画面へのリンクを出す(issue #1574)', async () => {
    getProject.mockResolvedValue({ id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null })
    render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
    const link = within(screen.getByTestId('active-tab')).getByRole('link', { name: 'SNS 告知の設定' })
    expect(link).toHaveAttribute('href', '/projects/7/settings/sns')
  })

  it('プロジェクトが取得できなければ notFound() になる(loading UI のまま止まらない)', async () => {
    getProject.mockRejectedValue(new Error('404'))
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
    await expect(ProjectDetailPage({ params: Promise.resolve({ id: '9' }) })).rejects.toThrow('NEXT_NOT_FOUND')
    expect(notFound).toHaveBeenCalledTimes(1)
  })

  describe('概要タブのメンバー追加の案内(issue #1069)', () => {
    const site = { id: 1, siteKey: 'k', name: 'n', baseUrl: 'http://x' }
    const member = { userId: 1, email: 'a@example.com', displayName: 'A', wpRole: 'author' }
    const baseProject = { id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null }
    const open = async () => render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))

    beforeEach(() => {
      jest.spyOn(console, 'error').mockImplementation(() => undefined)
    })

    it.each([
      ['localSite', { localSite: site }],
      ['testSite', { testSite: site }],
      ['productionSite', { productionSite: site }],
    ])('%s が紐付いていてメンバーが0人なら、メンバーが居ない旨とメンバー追加の操作を示す', async (_name, bound) => {
      getProject.mockResolvedValue({ ...baseProject, ...bound })
      await open()
      const tab = within(screen.getByTestId('active-tab'))
      expect(tab.getByText(/メンバーがいません/)).toBeInTheDocument()
      expect(tab.getByTestId('add-member-form')).toBeInTheDocument()
      expect(tab.queryByText(/先にサイトを紐付け/)).not.toBeInTheDocument()
    })

    it('サイトが1つも紐付いていなければ、先にサイトを紐付けるよう促し、メンバー追加は促さない', async () => {
      getProject.mockResolvedValue(baseProject)
      await open()
      const tab = within(screen.getByTestId('active-tab'))
      expect(tab.getByText(/先にサイトを紐付け/)).toBeInTheDocument()
      expect(tab.queryByText(/メンバーがいません/)).not.toBeInTheDocument()
      expect(tab.queryByTestId('add-member-form')).not.toBeInTheDocument()
    })

    it('メンバーが1人以上居れば、どの案内も出さない', async () => {
      getProject.mockResolvedValue({ ...baseProject, testSite: site })
      listProjectUsers.mockResolvedValue([member])
      await open()
      const tab = within(screen.getByTestId('active-tab'))
      expect(tab.queryByText(/メンバーがいません/)).not.toBeInTheDocument()
      expect(tab.queryByText(/先にサイトを紐付け/)).not.toBeInTheDocument()
      expect(tab.queryByText(/取得できませんでした/)).not.toBeInTheDocument()
    })

    it('サイト未紐付けでもメンバーが居れば、サイト紐付けの案内は出さない', async () => {
      getProject.mockResolvedValue(baseProject)
      listProjectUsers.mockResolvedValue([member])
      await open()
      expect(within(screen.getByTestId('active-tab')).queryByText(/先にサイトを紐付け/)).not.toBeInTheDocument()
    })

    it.each([
      ['サイト紐付け済み', { testSite: site }],
      ['サイト未紐付け', {}],
    ])('メンバー一覧の取得に失敗したら(%s)、0人の案内ではなく取得できなかった旨を出す', async (_name, bound) => {
      getProject.mockResolvedValue({ ...baseProject, ...bound })
      listProjectUsers.mockRejectedValue(new Error('503'))
      await open()
      const tab = within(screen.getByTestId('active-tab'))
      expect(tab.getByText(/メンバー情報を取得できませんでした/)).toBeInTheDocument()
      expect(tab.queryByText(/メンバーがいません/)).not.toBeInTheDocument()
      expect(tab.queryByText(/先にサイトを紐付け/)).not.toBeInTheDocument()
      expect(tab.queryByTestId('add-member-form')).not.toBeInTheDocument()
      expect(console.error).toHaveBeenCalled()
    })
  })

  it('?tab=members のとき「メンバー」タブを初期選択にする(ダッシュボードのメンバーウィジェットからの遷移先, issue #1502)', async () => {
    getProject.mockResolvedValue({ id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null })
    const { container } = render(
      await ProjectDetailPage({ params: Promise.resolve({ id: '7' }), searchParams: Promise.resolve({ tab: 'members' }) }),
    )
    expect(container.querySelector('[data-default-tab]')).toHaveAttribute('data-default-tab', 'members')
  })

  it('tab の指定がなければ初期選択を指定しない', async () => {
    getProject.mockResolvedValue({ id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null })
    const { container } = render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
    expect(container.querySelector('[data-default-tab]')).toHaveAttribute('data-default-tab', '')
  })

  describe('?imageJob (issue #1408)', () => {
    const project = { id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null }

    it.each([
      ['42', '42'],
      ['0', ''],
      ['-3', ''],
      ['abc', ''],
      ['4.5', ''],
      ['', ''],
    ])('imageJob=%s のとき、生成結果パネルへ渡すジョブIDは「%s」', async (value, expected) => {
      getProject.mockResolvedValue(project)
      const { container } = render(
        await ProjectDetailPage({
          params: Promise.resolve({ id: '7' }),
          searchParams: Promise.resolve({ tab: 'ai-models', imageJob: value }),
        }),
      )
      expect(container.querySelector('[data-testid="asset-panel"]')).toHaveAttribute('data-image-job-id', expected)
    })

    it('imageJob の指定がなければジョブIDを渡さない', async () => {
      getProject.mockResolvedValue(project)
      const { container } = render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
      expect(container.querySelector('[data-testid="asset-panel"]')).toHaveAttribute('data-image-job-id', '')
    })
  })
})
