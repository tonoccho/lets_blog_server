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
      {/* 全タブの中身を id ごとに描く(タブの振り分けを見る, issue #1669)。 */}
      {tabs.map((t) => <section key={t.id} data-testid={`tab-${t.id}`}>{t.content}</section>)}
    </div>
  ),
}))
// 子コンポーネントは自前のテストがある。ここは page.tsx の組み立てだけを見る。
jest.mock('../ProjectSectionNav', () => ({ ProjectSectionNav: () => null }))
jest.mock('../EnvironmentSlot', () => ({ EnvironmentSlot: () => null }))
jest.mock('../MasterEnvironmentSelector', () => ({ MasterEnvironmentSelector: () => null }))
jest.mock('../ProjectGithubRepositoryForm', () => ({ ProjectGithubRepositoryForm: () => null }))
jest.mock('../ProjectApiKeysForm', () => ({ ProjectApiKeysForm: () => null }))
jest.mock('../EnvironmentSyncPanel', () => ({ EnvironmentSyncPanel: () => <div data-testid="sync-panel" /> }))
jest.mock('../BulkManagementPanel', () => ({ BulkManagementPanel: () => <div data-testid="bulk-panel" /> }))
jest.mock('../GarbageCollectionPanel', () => ({ GarbageCollectionPanel: () => <div data-testid="gc-panel" /> }))
jest.mock('../ProjectAiModelsPanel', () => ({ ProjectAiModelsPanel: () => <div data-testid="ai-models-panel" /> }))
jest.mock('../AiConnectionSection', () => ({
  AiConnectionSection: ({ provider }: { provider: string }) => <div data-testid={`connection-${provider}`} />,
}))
jest.mock('../ChatGptConnectionSection', () => ({ ChatGptConnectionSection: () => <div data-testid="connection-CHATGPT" /> }))
jest.mock('../ClaudeConnectionSection', () => ({ ClaudeConnectionSection: () => <div data-testid="connection-CLAUDE" /> }))
jest.mock('../ProjectAssetGenerationPanel', () => ({
  ProjectAssetGenerationPanel: ({ imageJobId }: { imageJobId?: number }) => (
    <div data-testid="asset-panel" data-image-job-id={imageJobId ?? ''} />
  ),
}))
jest.mock('../ProjectImageGenerationPromptDefaultsForm', () => ({ ProjectImageGenerationPromptDefaultsForm: () => null }))
jest.mock('../ProjectImageGenerationSizeDefaultsForm', () => ({ ProjectImageGenerationSizeDefaultsForm: () => null }))
jest.mock('../ProjectArticleImageResizeDefaultForm', () => ({ ProjectArticleImageResizeDefaultForm: () => null }))
jest.mock('../ProjectImageContentFilterSettingsForm', () => ({ ProjectImageContentFilterSettingsForm: () => null }))
jest.mock('../ProjectNameForm', () => ({ ProjectNameForm: () => <div data-testid="name-form" /> }))
jest.mock('../DeleteProjectButton', () => ({ DeleteProjectButton: () => <button>プロジェクトを削除</button> }))
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
    expect(screen.getByRole('button', { name: 'メンテナンス' })).toBeInTheDocument()
    expect(notFound).not.toHaveBeenCalled()
  })

  describe('タブの再編(issue #1669)', () => {
    const project = { id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null }
    const open = async () => {
      getProject.mockResolvedValue(project)
      return render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
    }

    it('タブは「概要」「設定」「AI・アセット」「メンバー」「メンテナンス」の順で、id は overview / settings / ai-models / members / maintenance', async () => {
      await open()
      expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
        '概要',
        '設定',
        'AI・アセット',
        'メンバー',
        'メンテナンス',
        'プロジェクトを削除',
      ])
      for (const id of ['overview', 'settings', 'ai-models', 'members', 'maintenance']) {
        expect(screen.getByTestId(`tab-${id}`)).toBeInTheDocument()
      }
    })

    it('メンテナンスタブに一括管理・ガベージコレクション・プロジェクト削除を置き、他のタブには削除ボタンが無い', async () => {
      await open()
      const tab = within(screen.getByTestId('tab-maintenance'))
      expect(tab.getByTestId('bulk-panel')).toBeInTheDocument()
      expect(tab.getByTestId('gc-panel')).toBeInTheDocument()
      expect(tab.getByRole('button', { name: 'プロジェクトを削除' })).toBeInTheDocument()
      for (const id of ['overview', 'settings', 'ai-models', 'members']) {
        expect(within(screen.getByTestId(`tab-${id}`)).queryByRole('button', { name: 'プロジェクトを削除' })).toBeNull()
      }
      expect(screen.getAllByRole('button', { name: 'プロジェクトを削除' })).toHaveLength(1)
    })

    it('設定タブに全般(プロジェクト名・環境同期)・GitHub/APIキー・4つのAI接続を置き、AI・アセットタブには接続を置かない', async () => {
      await open()
      const settings = within(screen.getByTestId('tab-settings'))
      expect(settings.getByTestId('name-form')).toBeInTheDocument()
      expect(settings.getByTestId('sync-panel')).toBeInTheDocument()
      for (const key of ['OLLAMA', 'COMFYUI', 'CHATGPT', 'CLAUDE']) {
        expect(settings.getByTestId(`connection-${key}`)).toBeInTheDocument()
      }
      const ai = within(screen.getByTestId('tab-ai-models'))
      expect(ai.getByTestId('ai-models-panel')).toBeInTheDocument()
      expect(ai.queryByTestId(/^connection-/)).toBeNull()
      // 概要には名前フォームも環境同期も置かない
      const overview = within(screen.getByTestId('tab-overview'))
      expect(overview.queryByTestId('name-form')).toBeNull()
      expect(overview.queryByTestId('sync-panel')).toBeNull()
    })

    it('設定タブに「モデル設定」の案内カードを置かない(issue #1670)', async () => {
      await open()
      const settings = within(screen.getByTestId('tab-settings'))
      expect(settings.queryByRole('heading', { name: 'モデル設定' })).toBeNull()
      expect(settings.queryByText(/AI・アセット」タブから切り替えられます/)).toBeNull()
    })

    it('設定タブに SNS 告知・Google Analytics・Google AdSense の設定ページへのリンクを置き、概要タブには SNS 告知のカードを置かない(issue #1574 の移動)', async () => {
      await open()
      const settings = within(screen.getByTestId('tab-settings'))
      expect(settings.getByRole('link', { name: 'SNS 告知の設定' })).toHaveAttribute('href', '/projects/7/settings/sns')
      expect(settings.getByRole('link', { name: 'Google Analytics の設定' })).toHaveAttribute(
        'href',
        '/projects/7/settings/google-analytics',
      )
      expect(settings.getByRole('link', { name: 'Google AdSense の設定' })).toHaveAttribute('href', '/projects/7/settings/adsense')
      expect(within(screen.getByTestId('tab-overview')).queryByRole('link', { name: 'SNS 告知の設定' })).toBeNull()
    })

    it.each([
      ['overview', 'overview'],
      ['settings', 'settings'],
      ['ai-models', 'ai-models'],
      ['members', 'members'],
      ['maintenance', 'maintenance'],
      ['bulk-management', 'maintenance'],
      ['garbage-collection', 'maintenance'],
      ['unknown-tab', 'unknown-tab'],
    ])('?tab=%s のとき初期選択のタブは「%s」', async (query, expected) => {
      getProject.mockResolvedValue(project)
      const { container } = render(
        await ProjectDetailPage({ params: Promise.resolve({ id: '7' }), searchParams: Promise.resolve({ tab: query }) }),
      )
      expect(container.querySelector('[data-default-tab]')).toHaveAttribute('data-default-tab', expected)
    })
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
    ])('%s が紐付いていてメンバーが0人なら、メンバーが居ない旨とメンバータブへのリンクを示し、メンバー追加の操作は置かない(issue #1670)', async (_name, bound) => {
      getProject.mockResolvedValue({ ...baseProject, ...bound })
      await open()
      const tab = within(screen.getByTestId('active-tab'))
      expect(tab.getByText(/メンバーがいません/)).toBeInTheDocument()
      expect(tab.queryByTestId('add-member-form')).not.toBeInTheDocument()
      expect(tab.getByRole('link', { name: 'メンバータブ' })).toHaveAttribute('href', '/projects/7?tab=members')
      expect(tab.queryByText(/先にサイトを紐付け/)).not.toBeInTheDocument()
    })

    it('メンバータブにはメンバー追加のフォームを置く', async () => {
      getProject.mockResolvedValue({ ...baseProject, testSite: site })
      await open()
      expect(within(screen.getByTestId('tab-members')).getByTestId('add-member-form')).toBeInTheDocument()
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
