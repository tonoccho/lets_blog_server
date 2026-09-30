import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'

const notFound = jest.fn(() => {
  throw new Error('NEXT_NOT_FOUND')
})
jest.mock('next/navigation', () => ({ notFound: () => notFound() }))

const getProject = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  getProject: (...args: unknown[]) => getProject(...args),
  listSites: jest.fn().mockResolvedValue([]),
  listProjectUsers: jest.fn().mockResolvedValue([]),
  listUsers: jest.fn().mockResolvedValue([]),
  listCategoryComparison: jest.fn().mockResolvedValue({ items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: 'test' }),
  getProjectGithubTokenStatus: jest.fn().mockResolvedValue({ configured: false }),
  getProjectBraveSearchApiKeyStatus: jest.fn().mockResolvedValue({ configured: false }),
  getProjectImageSettings: jest.fn().mockResolvedValue({}),
}))
jest.mock('@/lib/session', () => ({
  requireAdminSession: jest.fn().mockResolvedValue(undefined),
  getViewerTimeZone: jest.fn().mockResolvedValue(null),
}))
jest.mock('@/components/Breadcrumb', () => ({ Breadcrumb: () => <nav /> }))
jest.mock('@/components/Tabs', () => ({
  Tabs: ({ tabs, defaultTabId }: { tabs: { id: string; label: string; content: ReactNode }[]; defaultTabId?: string }) => (
    <div data-default-tab={defaultTabId ?? ''}>{tabs.map((t) => <button key={t.id}>{t.label}</button>)}</div>
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
jest.mock('../ProjectAssetGenerationPanel', () => ({ ProjectAssetGenerationPanel: () => null }))
jest.mock('../ProjectImageGenerationPromptDefaultsForm', () => ({ ProjectImageGenerationPromptDefaultsForm: () => null }))
jest.mock('../ProjectImageGenerationSizeDefaultsForm', () => ({ ProjectImageGenerationSizeDefaultsForm: () => null }))
jest.mock('../ProjectArticleImageResizeDefaultForm', () => ({ ProjectArticleImageResizeDefaultForm: () => null }))
jest.mock('../ProjectImageContentFilterSettingsForm', () => ({ ProjectImageContentFilterSettingsForm: () => null }))
jest.mock('../ProjectNameForm', () => ({ ProjectNameForm: () => null }))
jest.mock('../DeleteProjectButton', () => ({ DeleteProjectButton: () => null }))
jest.mock('../ProjectUserManager', () => ({ ProjectUserManager: () => null }))
jest.mock('../AddProjectUserModal', () => ({ AddProjectUserModal: () => null }))

import ProjectDetailPage from '../(detail)/page'

describe('プロジェクト詳細画面 page.tsx(issue #1475: (detail) ルートグループへ移しても最終表示は同じ)', () => {
  beforeEach(() => {
    notFound.mockClear()
    getProject.mockReset()
  })

  it('取得したプロジェクトの名前と一括管理タブを描く', async () => {
    getProject.mockResolvedValue({ id: 7, name: 'サンプル案件', slug: 'sample', localSite: null, testSite: null, productionSite: null })
    render(await ProjectDetailPage({ params: Promise.resolve({ id: '7' }) }))
    expect(screen.getByRole('heading', { name: 'サンプル案件' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '一括管理' })).toBeInTheDocument()
    expect(notFound).not.toHaveBeenCalled()
  })

  it('プロジェクトが取得できなければ notFound() になる(loading UI のまま止まらない)', async () => {
    getProject.mockRejectedValue(new Error('404'))
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
    await expect(ProjectDetailPage({ params: Promise.resolve({ id: '9' }) })).rejects.toThrow('NEXT_NOT_FOUND')
    expect(notFound).toHaveBeenCalledTimes(1)
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
})
