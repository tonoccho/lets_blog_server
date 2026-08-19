import { render, screen, within } from '@testing-library/react'
import { EnvironmentSyncPanel } from '../EnvironmentSyncPanel'
import type { Project, Site } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  syncEnvironmentAction: jest.fn(),
}))

function buildSite(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    name: 'site',
    siteKey: 'site',
    cmsType: 'WORDPRESS',
    baseUrl: 'https://example.com',
    createdAt: '',
    updatedAt: '',
    connectionCheckStatus: null,
    managedWordpress: true,
    ...overrides,
  } as Site
}

function buildProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: 'project',
    slug: 'project',
    localSite: buildSite({ id: 10, siteKey: 'local-site' }),
    testSite: buildSite({ id: 20, siteKey: 'test-site' }),
    productionSite: buildSite({ id: 30, siteKey: 'production-site' }),
    masterEnvironment: 'test',
    githubRepository: null,
    cssSelectorPrefix: null,
    defaultNegativePrompt: null,
    defaultQualityPrompt: null,
    defaultGeneratedImageWidth: null,
    defaultGeneratedImageHeight: null,
    defaultArticleImageLongEdgePx: null,
    createdAt: '',
    updatedAt: '',
    ...overrides,
  } as Project
}

function getSelect(labelText: string) {
  const label = screen.getByText(labelText).closest('label') as HTMLElement
  return within(label).getByRole('combobox') as HTMLSelectElement
}

describe('EnvironmentSyncPanel 同期元/同期先の選択肢', () => {
  it('同期元には本番環境を選択できる', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)

    const fromSelect = getSelect('同期元')
    const optionLabels = Array.from(fromSelect.options).map((o) => o.textContent)
    expect(optionLabels).toContain('本番')
  })

  it('同期先には本番環境を選択できない', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)

    const toSelect = getSelect('同期先')
    const optionLabels = Array.from(toSelect.options).map((o) => o.textContent)
    expect(optionLabels).not.toContain('本番')
    expect(optionLabels).toContain('ローカル')
    expect(optionLabels).toContain('テスト')
  })
})
