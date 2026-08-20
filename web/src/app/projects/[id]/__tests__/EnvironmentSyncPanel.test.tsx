import { fireEvent, render, screen, within } from '@testing-library/react'
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
    sshConfigured: false,
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

describe('EnvironmentSyncPanel SSH管理サイト(issue #511)', () => {
  function buildProjectWithSshProduction(): Project {
    return buildProject({
      productionSite: buildSite({ id: 30, siteKey: 'production-site', managedWordpress: false, sshConfigured: true }),
    })
  }

  it('SSH管理の本番環境も同期元に選択できる', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProjectWithSshProduction()} />)

    const fromSelect = getSelect('同期元')
    const optionLabels = Array.from(fromSelect.options).map((o) => o.textContent)
    expect(optionLabels).toContain('本番')
  })

  it('SSH管理・非managedの環境は同期先に表示されない', () => {
    render(
      <EnvironmentSyncPanel
        projectId={1}
        project={buildProject({
          testSite: buildSite({ id: 20, siteKey: 'test-site', managedWordpress: false, sshConfigured: true }),
        })}
      />
    )

    const toSelect = getSelect('同期先')
    const optionLabels = Array.from(toSelect.options).map((o) => o.textContent)
    expect(optionLabels).not.toContain('テスト')
  })

  it('SSH管理サイトを同期元に選ぶとプラグイン以外の同期対象は無効化されない', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProjectWithSshProduction()} />)

    const fromSelect = getSelect('同期元')
    fireEvent.change(fromSelect, { target: { value: 'production' } })

    const themesCheckbox = screen.getByLabelText('テーマ') as HTMLInputElement
    const pluginsCheckbox = screen.getByLabelText('プラグイン') as HTMLInputElement
    const mediaCheckbox = screen.getByLabelText('メディア') as HTMLInputElement
    const dbCheckbox = screen.getByLabelText('DB') as HTMLInputElement
    expect(themesCheckbox.disabled).toBe(false)
    expect(pluginsCheckbox.disabled).toBe(true)
    expect(mediaCheckbox.disabled).toBe(false)
    expect(dbCheckbox.disabled).toBe(false)
  })

  it('SSH管理サイトを同期元に選ぶとチェック済みだったプラグインは解除される', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProjectWithSshProduction()} />)

    const pluginsCheckbox = screen.getByLabelText('プラグイン') as HTMLInputElement
    fireEvent.click(pluginsCheckbox)
    expect(pluginsCheckbox.checked).toBe(true)

    const fromSelect = getSelect('同期元')
    fireEvent.change(fromSelect, { target: { value: 'production' } })

    expect(pluginsCheckbox.checked).toBe(false)
  })

  it('SSH管理サイトを同期元に選んでもチェック済みだったテーマは解除されない', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProjectWithSshProduction()} />)

    const themesCheckbox = screen.getByLabelText('テーマ') as HTMLInputElement
    fireEvent.click(themesCheckbox)
    expect(themesCheckbox.checked).toBe(true)

    const fromSelect = getSelect('同期元')
    fireEvent.change(fromSelect, { target: { value: 'production' } })

    expect(themesCheckbox.checked).toBe(true)
  })
})
