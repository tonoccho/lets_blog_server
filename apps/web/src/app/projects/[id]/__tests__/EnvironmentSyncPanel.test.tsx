import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { EnvironmentSyncPanel } from '../EnvironmentSyncPanel'
import type { Project, Site } from '@/lib/apiClient'
import { jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION, calledRequests, installFetchByPath } from '@/lib/__tests__/testUtils/webAdminRuntime'

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1836)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

const JOBS = 'POST /api/projects/1/environments/sync/jobs'

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION)
  installFetchByPath({ [JOBS]: () => jsonResponse({ id: 3, status: 'running' }) })
})

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

describe('EnvironmentSyncPanel 同期元の選択(未選択へ戻す)', () => {
  it('同期元を選んだあと「選択してください」へ戻しても、同期対象の選択は変わらない', () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)

    const themesCheckbox = screen.getByLabelText('テーマ') as HTMLInputElement
    fireEvent.click(themesCheckbox)
    const fromSelect = getSelect('同期元')
    fireEvent.change(fromSelect, { target: { value: 'production' } })
    fireEvent.change(fromSelect, { target: { value: '' } })

    expect(fromSelect.value).toBe('')
    expect(themesCheckbox.checked).toBe(true)
    expect((screen.getByLabelText('プラグイン') as HTMLInputElement).disabled).toBe(false)
  })
})

describe('EnvironmentSyncPanel(issue #1697: ジョブ受理の非同期)', () => {
  let confirmSpy: jest.SpyInstance

  beforeEach(() => {
    confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => {
    confirmSpy.mockRestore()
  })

  function fillAndSubmit() {
    fireEvent.change(getSelect('同期元'), { target: { value: 'production' } })
    fireEvent.change(getSelect('同期先'), { target: { value: 'local' } })
    fireEvent.click(screen.getByLabelText('DB'))
    return act(async () => {
      fireEvent.submit(screen.getByRole('button', { name: '同期する' }).closest('form') as HTMLFormElement)
    })
  }

  it('受理されたら、処理キューに追加された旨を示し、同期が終わったとは示さない', async () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)
    await fillAndSubmit()

    expect(await screen.findByText(/同期を要求しました。処理キューに追加されました/)).toBeInTheDocument()
    expect(screen.queryByText('同期しました。')).toBeNull()
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('本番環境からローカル環境へ'))
  })

  it('受理後も同期ボタンは押せる状態のままで、長時間の待機表示にならない', async () => {
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)
    await fillAndSubmit()

    await screen.findByText(/同期を要求しました/)
    expect(screen.getByRole('button', { name: '同期する' })).toBeEnabled()
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull()
  })

  it('エラーは赤字で示し、受理の表示は出さない', async () => {
    installFetchByPath({ [JOBS]: () => textResponse('失敗しました', 500) })
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)
    await fillAndSubmit()

    expect(await screen.findByText('APIエラー (500): 失敗しました')).toBeInTheDocument()
    expect(screen.queryByText(/同期を要求しました/)).toBeNull()
  })

  it('受理の往復の間だけ送信ボタンを無効にする', async () => {
    let release: (r: Response) => void = () => {}
    installFetchByPath({ [JOBS]: () => new Promise<Response>((resolve) => (release = resolve)) })
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)
    await fillAndSubmit()

    expect(await screen.findByRole('button', { name: /要求中/ })).toBeDisabled()
    await act(async () => {
      release(jsonResponse({ id: 3, status: 'running' }))
    })
    await waitFor(() => expect(screen.getByRole('button', { name: '同期する' })).toBeEnabled())
  })

  it('同期元・同期先を選ばずに送った場合も、確認の文言は空の環境名で組み立てて取り消せる', async () => {
    confirmSpy.mockReturnValue(false)
    const fetchMock = installFetchByPath({ [JOBS]: () => jsonResponse({ id: 3, status: 'running' }) })
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)

    // ブラウザの required の検証を通り抜けた送信を直接再現する
    await act(async () => {
      fireEvent.submit(screen.getByRole('button', { name: '同期する' }).closest('form') as HTMLFormElement)
    })

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('環境から環境へ、選択した内容()を同期します'))
    expect(calledRequests(fetchMock)).toEqual([])
  })

  it('確認ダイアログで取り消すと、同期を要求しない', async () => {
    confirmSpy.mockReturnValue(false)
    const fetchMock = installFetchByPath({ [JOBS]: () => jsonResponse({ id: 3, status: 'running' }) })
    render(<EnvironmentSyncPanel projectId={1} project={buildProject()} />)
    await fillAndSubmit()

    expect(calledRequests(fetchMock)).toEqual([])
    expect(screen.queryByText(/同期を要求しました/)).toBeNull()
  })
})
