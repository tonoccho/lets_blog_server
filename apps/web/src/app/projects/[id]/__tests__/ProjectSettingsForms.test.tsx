import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ProjectNameForm } from '../ProjectNameForm'
import { MasterEnvironmentSelector } from '../MasterEnvironmentSelector'
import { ProjectGithubRepositoryForm } from '../ProjectGithubRepositoryForm'
import { ProjectApiKeysForm } from '../ProjectApiKeysForm'
import { AddProjectUserModal } from '../AddProjectUserModal'
import { DeleteProjectButton } from '../DeleteProjectButton'
import type { AppUser, Project } from '@/lib/apiClient'
import { jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION, calledRequests, installFetchByPath } from '@/lib/__tests__/testUtils/webAdminRuntime'
import { bodies } from './projectCoreRuntime'

/**
 * プロジェクト詳細の基本設定・メンバー追加・APIキーの各フォームと、プロジェクト削除ボタン(#1836)。
 *
 * 自前の `../actions`(と `../../actions`)・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と
 * 外部との通信(`fetch`)だけを代替する。送信の往復の間の表示(「保存中…」など)は、応答を保留して確かめる。
 * `redirect` は、呼ばれた先を記録するだけにする(実物は例外を投げ、クライアントから呼んだ Server Action の
 * 呼び出し基盤が捕まえて遷移に変える。その基盤は jest 上に無い)。
 */
const redirectMock = jest.fn()
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => redirectMock(path),
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

function pendingResponse(): { promise: Promise<Response>; release: (r: Response) => void } {
  let release: (r: Response) => void = () => {}
  const promise = new Promise<Response>((resolve) => (release = resolve))
  return { promise, release }
}

async function submit(form: HTMLElement | null) {
  await act(async () => {
    fireEvent.submit(form as HTMLFormElement)
  })
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION)
  redirectMock.mockClear()
})

describe('ProjectNameForm', () => {
  const UPDATE = 'PUT /api/projects/7'

  it('名前を保存すると、保存したことを示す', async () => {
    const fetchMock = installFetchByPath({ [UPDATE]: () => jsonResponse({}) })
    render(<ProjectNameForm projectId={7} name="旧名" />)

    fireEvent.change(screen.getByLabelText('プロジェクト名'), { target: { value: ' 新名 ' } })
    await submit(screen.getByRole('button', { name: '名前を保存' }).closest('form'))

    expect(await screen.findByText('保存しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, UPDATE)).toEqual([{ name: '新名' }])
  })

  it('往復の間は「保存中…」を示して押せなくする', async () => {
    const { promise, release } = pendingResponse()
    installFetchByPath({ [UPDATE]: () => promise })
    render(<ProjectNameForm projectId={7} name="旧名" />)

    await submit(screen.getByRole('button', { name: '名前を保存' }).closest('form'))

    expect(await screen.findByRole('button', { name: '保存中…' })).toBeDisabled()
    await act(async () => {
      release(jsonResponse({}))
    })
    expect(await screen.findByRole('button', { name: '名前を保存' })).toBeEnabled()
  })

  it('空にして送ると、バックエンドへ送らずエラーを示す', async () => {
    const fetchMock = installFetchByPath({ [UPDATE]: () => jsonResponse({}) })
    render(<ProjectNameForm projectId={7} name="旧名" />)

    fireEvent.change(screen.getByLabelText('プロジェクト名'), { target: { value: '  ' } })
    await submit(screen.getByRole('button', { name: '名前を保存' }).closest('form'))

    expect(await screen.findByText('プロジェクト名を入力してください。')).toBeInTheDocument()
    expect(calledRequests(fetchMock)).toEqual([])
  })

  it('バックエンドが拒否したら、その理由を示す', async () => {
    installFetchByPath({ [UPDATE]: () => textResponse('名前が重複しています', 409) })
    render(<ProjectNameForm projectId={7} name="旧名" />)

    await submit(screen.getByRole('button', { name: '名前を保存' }).closest('form'))

    expect(await screen.findByText('APIエラー (409): 名前が重複しています')).toBeInTheDocument()
    expect(screen.queryByText('保存しました。')).toBeNull()
  })
})

describe('MasterEnvironmentSelector', () => {
  const MASTER = 'PUT /api/projects/7/master-environment'
  const project = { id: 7, masterEnvironment: 'test', testSite: { id: 1 }, productionSite: null } as unknown as Project

  it('未紐付けの環境には「(未紐付け)」を添える', () => {
    installFetchByPath({})
    render(<MasterEnvironmentSelector projectId={7} project={project} />)

    expect(screen.getByRole('option', { name: 'テスト' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '本番(未紐付け)' })).toBeInTheDocument()
  })

  it('紐付け済みの環境には「(未紐付け)」を添えない', () => {
    installFetchByPath({})
    const linked = { ...project, testSite: null, productionSite: { id: 2 } } as unknown as Project
    render(<MasterEnvironmentSelector projectId={7} project={linked} />)

    expect(screen.getByRole('option', { name: 'テスト(未紐付け)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '本番' })).toBeInTheDocument()
  })

  it('マスター環境を保存すると、保存したことを示す', async () => {
    const fetchMock = installFetchByPath({ [MASTER]: () => jsonResponse({}) })
    render(<MasterEnvironmentSelector projectId={7} project={project} />)

    fireEvent.change(screen.getByLabelText('マスター環境'), { target: { value: 'production' } })
    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByText('保存しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, MASTER)).toEqual([{ masterEnvironment: 'production' }])
  })

  it('往復の間は「保存中…」を示して押せなくする', async () => {
    const { promise, release } = pendingResponse()
    installFetchByPath({ [MASTER]: () => promise })
    render(<MasterEnvironmentSelector projectId={7} project={project} />)

    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByRole('button', { name: '保存中…' })).toBeDisabled()
    await act(async () => {
      release(jsonResponse({}))
    })
    expect(await screen.findByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('バックエンドが拒否したら、その理由を示す', async () => {
    installFetchByPath({ [MASTER]: () => textResponse('不正な環境です', 400) })
    render(<MasterEnvironmentSelector projectId={7} project={project} />)

    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByText('APIエラー (400): 不正な環境です')).toBeInTheDocument()
  })
})

describe('ProjectGithubRepositoryForm', () => {
  const REPO = 'PUT /api/projects/7/github-repository'

  it('リポジトリを保存すると、保存したことを示す(未設定の初期値は空)', async () => {
    const fetchMock = installFetchByPath({ [REPO]: () => jsonResponse({}) })
    render(<ProjectGithubRepositoryForm projectId={7} githubRepository={null} />)
    const input = screen.getByLabelText('リポジトリ (owner/repo)')
    expect(input).toHaveValue('')

    fireEvent.change(input, { target: { value: ' owner/repo ' } })
    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByText('保存しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, REPO)).toEqual([{ githubRepository: 'owner/repo' }])
  })

  it('設定済みのリポジトリを初期値に出す', () => {
    installFetchByPath({})
    render(<ProjectGithubRepositoryForm projectId={7} githubRepository="o/r" />)

    expect(screen.getByLabelText('リポジトリ (owner/repo)')).toHaveValue('o/r')
  })

  it('往復の間は「保存中…」を示して押せなくする', async () => {
    const { promise, release } = pendingResponse()
    installFetchByPath({ [REPO]: () => promise })
    render(<ProjectGithubRepositoryForm projectId={7} githubRepository="o/r" />)

    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByRole('button', { name: '保存中…' })).toBeDisabled()
    await act(async () => {
      release(jsonResponse({}))
    })
    expect(await screen.findByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('バックエンドが拒否したら、その理由を示す', async () => {
    installFetchByPath({ [REPO]: () => textResponse('見つかりません', 404) })
    render(<ProjectGithubRepositoryForm projectId={7} githubRepository="o/r" />)

    await submit(screen.getByRole('button', { name: '保存' }).closest('form'))

    expect(await screen.findByText('APIエラー (404): 見つかりません')).toBeInTheDocument()
  })
})

describe('ProjectApiKeysForm', () => {
  const GITHUB = '/api/projects/7/api-keys/github-token'
  const BRAVE = '/api/projects/7/api-keys/brave-search-api-key'
  let confirmSpy: jest.SpyInstance

  beforeEach(() => {
    confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => {
    confirmSpy.mockRestore()
  })

  function renderForm(github: boolean, brave: boolean) {
    return render(<ProjectApiKeysForm projectId={7} githubTokenConfigured={github} braveSearchApiKeyConfigured={brave} />)
  }

  const formOf = (label: string) => screen.getByRole('heading', { name: label }).parentElement?.querySelector('form') as HTMLFormElement

  it('設定済みかどうかを、項目ごとに示す。設定済みの項目にだけ削除ボタンを出す', () => {
    installFetchByPath({})
    renderForm(true, false)

    expect(screen.getByText('このプロジェクトで設定済み')).toBeInTheDocument()
    expect(screen.getByText('未設定(フォールバック先の設定を使用)')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'プロジェクト設定を削除' })).toHaveLength(1)
  })

  it('GitHub トークンを保存すると、保存したことを示す', async () => {
    const fetchMock = installFetchByPath({ ['PUT ' + GITHUB]: () => jsonResponse(null, 204) })
    renderForm(false, false)

    const form = formOf('GitHub Personal Access Token')
    fireEvent.change(form.querySelector('input[name="githubToken"]') as HTMLInputElement, { target: { value: ' ghp_x ' } })
    await submit(form)

    expect(await screen.findByText('保存しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, 'PUT ' + GITHUB)).toEqual([{ githubToken: 'ghp_x' }])
  })

  it('Brave Search API キーを保存すると、保存したことを示す', async () => {
    const fetchMock = installFetchByPath({ ['PUT ' + BRAVE]: () => jsonResponse(null, 204) })
    renderForm(false, false)

    const form = formOf('Brave Search APIキー')
    fireEvent.change(form.querySelector('input[name="apiKey"]') as HTMLInputElement, { target: { value: 'BSA1' } })
    await submit(form)

    expect(await screen.findByText('保存しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, 'PUT ' + BRAVE)).toEqual([{ apiKey: 'BSA1' }])
  })

  it('値が空のまま送ると、バックエンドへ送らずエラーを示す', async () => {
    const fetchMock = installFetchByPath({})
    renderForm(false, false)

    await submit(formOf('GitHub Personal Access Token'))
    expect(await screen.findByText('GitHubトークンを入力してください。')).toBeInTheDocument()
    await submit(formOf('Brave Search APIキー'))
    expect(await screen.findByText('APIキーを入力してください。')).toBeInTheDocument()
    expect(calledRequests(fetchMock)).toEqual([])
  })

  it('保存がバックエンドに拒否されたら、その理由を示す', async () => {
    installFetchByPath({ ['PUT ' + GITHUB]: () => textResponse('権限がありません', 403) })
    renderForm(false, false)

    const form = formOf('GitHub Personal Access Token')
    fireEvent.change(form.querySelector('input[name="githubToken"]') as HTMLInputElement, { target: { value: 'x' } })
    await submit(form)

    expect(await screen.findByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
  })

  it('往復の間は「保存中…」を示して押せなくする', async () => {
    const { promise, release } = pendingResponse()
    installFetchByPath({ ['PUT ' + GITHUB]: () => promise })
    renderForm(false, false)

    const form = formOf('GitHub Personal Access Token')
    fireEvent.change(form.querySelector('input[name="githubToken"]') as HTMLInputElement, { target: { value: 'x' } })
    await submit(form)

    const savingButton = await screen.findByRole('button', { name: '保存中…' })
    expect(savingButton).toBeDisabled()
    await act(async () => {
      release(jsonResponse(null, 204))
    })
    await waitFor(() => expect(screen.queryByRole('button', { name: '保存中…' })).toBeNull())
  })

  it('削除を確認で取り消すと何も送らず、承認するとプロジェクト設定を削除する(往復の間は「削除中…」)', async () => {
    const { promise, release } = pendingResponse()
    const fetchMock = installFetchByPath({ ['DELETE ' + BRAVE]: () => promise })
    renderForm(false, true)

    confirmSpy.mockReturnValueOnce(false)
    fireEvent.click(screen.getByRole('button', { name: 'プロジェクト設定を削除' }))
    expect(calledRequests(fetchMock)).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: 'プロジェクト設定を削除' }))
    expect(await screen.findByRole('button', { name: '削除中…' })).toBeDisabled()
    expect(confirmSpy).toHaveBeenLastCalledWith(expect.stringContaining('Brave Search APIキーのプロジェクト設定を削除しますか?'))
    await act(async () => {
      release(jsonResponse(null, 204))
    })
    await waitFor(() => expect(calledRequests(fetchMock)).toEqual(['DELETE ' + BRAVE]))
  })

  it('GitHub トークンの削除も、対応する要求を送る', async () => {
    const fetchMock = installFetchByPath({ ['DELETE ' + GITHUB]: () => jsonResponse(null, 204) })
    renderForm(true, false)

    fireEvent.click(screen.getByRole('button', { name: 'プロジェクト設定を削除' }))

    await waitFor(() => expect(calledRequests(fetchMock)).toEqual(['DELETE ' + GITHUB]))
  })
})

describe('AddProjectUserModal', () => {
  const ADD = 'POST /api/projects/7/users'
  const users = [{ id: 3, email: 'a@example.com' }] as unknown as AppUser[]

  async function fillAndSubmit() {
    fireEvent.change(screen.getByLabelText('ユーザーを追加'), { target: { value: '3' } })
    await submit(screen.getByRole('button', { name: '追加' }).closest('form'))
  }

  it('ユーザーとロールを選んで追加すると、追加したことを示して入力を戻す', async () => {
    const fetchMock = installFetchByPath({ [ADD]: () => jsonResponse(null, 204) })
    render(<AddProjectUserModal projectId={7} candidateUsers={users} />)

    await fillAndSubmit()

    expect(await screen.findByText('追加しました。')).toBeInTheDocument()
    expect(bodies(fetchMock, ADD)).toEqual([{ userId: 3, wpRole: 'contributor' }])
    expect(screen.getByLabelText('ユーザーを追加')).toHaveValue('')
  })

  it('ユーザー未選択で送ると、バックエンドへ送らずエラーを示す', async () => {
    const fetchMock = installFetchByPath({ [ADD]: () => jsonResponse(null, 204) })
    render(<AddProjectUserModal projectId={7} candidateUsers={users} />)

    await submit(screen.getByRole('button', { name: '追加' }).closest('form'))

    expect(await screen.findByText('ユーザーとロールを選択してください。')).toBeInTheDocument()
    expect(calledRequests(fetchMock)).toEqual([])
  })

  it('バックエンドが拒否したら、その理由を示す', async () => {
    installFetchByPath({ [ADD]: () => textResponse('すでに参加しています', 409) })
    render(<AddProjectUserModal projectId={7} candidateUsers={users} />)

    await fillAndSubmit()

    expect(await screen.findByText('APIエラー (409): すでに参加しています')).toBeInTheDocument()
    expect(screen.queryByText('追加しました。')).toBeNull()
  })

  it('往復の間は「追加中…」を示して押せなくする', async () => {
    const { promise, release } = pendingResponse()
    installFetchByPath({ [ADD]: () => promise })
    render(<AddProjectUserModal projectId={7} candidateUsers={users} />)

    await fillAndSubmit()

    expect(await screen.findByRole('button', { name: '追加中…' })).toBeDisabled()
    await act(async () => {
      release(jsonResponse(null, 204))
    })
    expect(await screen.findByRole('button', { name: '追加' })).toBeEnabled()
  })
})

describe('DeleteProjectButton', () => {
  const DELETE = 'DELETE /api/projects/7'
  let confirmSpy: jest.SpyInstance

  beforeEach(() => {
    confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => {
    confirmSpy.mockRestore()
  })

  it('確認で取り消すと、削除を送らない', () => {
    confirmSpy.mockReturnValue(false)
    const fetchMock = installFetchByPath({ [DELETE]: () => jsonResponse(null, 204) })
    render(<DeleteProjectButton id={7} />)

    fireEvent.click(screen.getByRole('button', { name: 'プロジェクトを削除' }))

    expect(calledRequests(fetchMock)).toEqual([])
  })

  it('確認で承認すると、削除を要求している間は「削除中…」を示し、削除後は一覧へ戻す', async () => {
    const { promise, release } = pendingResponse()
    const fetchMock = installFetchByPath({ [DELETE]: () => promise })
    const { container } = render(<DeleteProjectButton id={7} />)
    // 削除の要求は transition の外で await されないため、「削除中…」は一瞬しか描かれない。DOM の変更を記録して見る。
    const texts: string[] = []
    const observer = new MutationObserver((records) => {
      // 文字列の差し替えは characterData の変更として届く。変更の前の値(oldValue)が、直前に描かれていた文言。
      for (const record of records) texts.push(record.oldValue ?? '')
    })
    observer.observe(container, { subtree: true, characterData: true, characterDataOldValue: true })

    fireEvent.click(screen.getByRole('button', { name: 'プロジェクトを削除' }))
    await act(async () => {
      release(jsonResponse(null, 204))
    })
    observer.disconnect()

    await waitFor(() => expect(redirectMock).toHaveBeenCalledWith('/projects'))
    expect(texts).toContain('削除中…')
    expect(screen.getByRole('button', { name: 'プロジェクトを削除' })).toBeEnabled()
    expect(calledRequests(fetchMock)).toEqual([DELETE])
  })
})
