import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { useSession } from 'next-auth/react'
import { CustomTagGenerationForm } from '../CustomTagGenerationForm'
import type { Project } from '@/lib/apiClient'

/**
 * 自前の useCustomTagGeneration・Server Action(@/app/custom-tags/actions)・@/lib/session・@/lib/apiClient は
 * 差し替えない。差し替えるのは Next.js の実行基盤・`next-auth/react`(ブラウザのセッション取得)と
 * 外部との通信(fetch)だけ(#1834)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock('next-auth/react')

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'

const GENERATE = 'POST /api/custom-tags/generate/jobs'
let fetchMock: jest.Mock
const generateBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith('/api/custom-tags/generate/jobs') && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init.body)))

const mockedUseSession = useSession as jest.MockedFunction<typeof useSession>

function authenticated() {
  mockedUseSession.mockReturnValue({
    data: { user: { email: 'a@example.com', role: 'admin' }, expires: '2099-01-01' },
    status: 'authenticated',
    update: jest.fn(),
  } as unknown as ReturnType<typeof useSession>)
}

function sessionState(status: 'loading' | 'unauthenticated') {
  mockedUseSession.mockReturnValue({
    data: null,
    status,
    update: jest.fn(),
  } as unknown as ReturnType<typeof useSession>)
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION)
  fetchMock = installFetch({ [GENERATE]: () => jsonResponse({ id: 21, type: 'custom_tag_generation', status: 'running' }) })
})

/**
 * issue #778: セッション未解決の間に「生成」を押すと、handleSubmit が黙って return し、
 * isLoading も error も立たないため「押しても何も起きない」無反応状態になっていた。
 */
describe('CustomTagGenerationForm: セッション未解決時の挙動(issue #778)', () => {
  function renderForm() {
    return render(<CustomTagGenerationForm projects={[]} currentProjectId={null} />)
  }

  it('セッション未解決の間は生成ボタンを押せず、状態がラベルに出る', () => {
    sessionState('loading')

    renderForm()

    const button = screen.getByRole('button', { name: /セッション確認中/ })
    expect(button).toBeDisabled()
    // 押せない = 送信が捨てられることも無い
    fireEvent.click(button)
    expect(generateBodies()).toEqual([])
  })

  it('セッション未解決中に(ボタンの外から)送信されても、確認中である旨を表示する', () => {
    sessionState('loading')

    const { container } = renderForm()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(generateBodies()).toEqual([])
    expect(screen.getByText('セッションを確認しています。少し待ってからもう一度お試しください。')).toBeInTheDocument()
  })

  it('セッションが無い状態で送信されたら、黙って捨てず理由を表示する', () => {
    sessionState('unauthenticated')

    const { container } = renderForm()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(generateBodies()).toEqual([])
    expect(screen.getByText(/セッションが確認できませんでした/)).toBeInTheDocument()
  })

  it('セッション解決後は従来どおり生成を要求できる', async () => {
    authenticated()

    const { container } = renderForm()

    const button = screen.getByRole('button', { name: '生成' })
    expect(button).not.toBeDisabled()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => expect(generateBodies()).toHaveLength(1))
  })

  it('セッションの確認メッセージは、セッションが解決して再送信に成功すると消える', async () => {
    sessionState('unauthenticated')
    const { container, rerender } = renderForm()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    expect(screen.getByText(/セッションが確認できませんでした/)).toBeInTheDocument()

    authenticated()
    rerender(<CustomTagGenerationForm projects={[]} currentProjectId={null} />)
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => expect(generateBodies()).toHaveLength(1))
    expect(screen.queryByText(/セッションが確認できませんでした/)).not.toBeInTheDocument()
  })
})

/**
 * issue #1409: 生成は非同期ジョブとして要求し、生成と同時には保存しない。受理されたら
 * 「処理キューに追加された」旨を示し、結果は処理キューの「結果を見る」から確認して「保存」で登録する。
 * issue #1051: <form>にはmethod="post"を明示している。
 */
describe('CustomTagGenerationForm: 非同期ジョブとしての要求(issue #1409)', () => {
  beforeEach(() => {
    authenticated()
  })

  function renderForm(props: Partial<Parameters<typeof CustomTagGenerationForm>[0]> = {}) {
    return render(<CustomTagGenerationForm projects={[]} currentProjectId={null} {...props} />)
  }

  it('<form>はmethod="post"を持つ', () => {
    const { container } = renderForm()

    expect(container.querySelector('form')?.getAttribute('method')).toBe('post')
  })

  it('送信すると、プロンプト・タグ名・説明・プロジェクトを非同期の生成要求として渡す', async () => {
    const { container } = renderForm({ currentProjectId: 7, projects: [{ id: 7, name: 'P' } as Project] })

    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: '青いボタン' } })
    fireEvent.change(screen.getByLabelText(/タグ名/), { target: { value: 'blue-button' } })
    fireEvent.change(screen.getByLabelText(/説明\(任意\)/), { target: { value: '説明です' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() =>
      expect(generateBodies()).toEqual([
        { prompt: '青いボタン', tagName: 'blue-button', description: '説明です', projectId: 7 },
      ])
    )
  })

  it('説明が空なら description を送らない', async () => {
    const { container } = renderForm()

    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'p' } })
    fireEvent.change(screen.getByLabelText(/タグ名/), { target: { value: 't' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => expect(generateBodies()).toEqual([{ prompt: 'p', tagName: 't', projectId: null }]))
  })

  it('要求が受理されたら、保存済みとは言わず、処理キューの「結果を見る」から確認して「保存」する旨を示し、入力欄を空にする', async () => {
    const { container } = renderForm()
    const prompt = screen.getByLabelText(/プロンプト/) as HTMLTextAreaElement
    fireEvent.change(prompt, { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await waitFor(() => expect(prompt.value).toBe(''))

    const notice = screen.getByTestId('custom-tag-generation-queued')
    expect(notice).toHaveAttribute('data-job-id', '21')
    expect(notice).toHaveTextContent('処理キューに追加されました')
    expect(notice).toHaveTextContent('結果を見る')
    expect(notice).toHaveTextContent('保存')
    expect(screen.queryByText(/生成と同時に保存/)).not.toBeInTheDocument()
    // 完了を待たないので、生成ボタンは押せる状態のまま
    expect(screen.getByRole('button', { name: '生成' })).toBeEnabled()
  })

  it('受理されなかったとき(待ち行列が満杯)は入力欄を空にせず、理由を表示し、受理の通知は出さない', async () => {
    fetchMock = installFetch({ [GENERATE]: () => jsonResponse({ id: 22, status: 'failed' }) })
    const { container } = renderForm()
    const prompt = screen.getByLabelText(/プロンプト/) as HTMLTextAreaElement
    fireEvent.change(prompt, { target: { value: 'テスト' } })

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await screen.findByText(/待ち行列が満杯です/)
    expect(prompt.value).toBe('テスト')
    expect(screen.queryByTestId('custom-tag-generation-queued')).not.toBeInTheDocument()
  })

  it('受理の拒否理由(エラー)をフォームに表示し、受理の通知は出さない', async () => {
    fetchMock = installFetch({ [GENERATE]: () => textResponse('権限がありません', 403) })
    const { container } = renderForm()

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(await screen.findByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
    expect(screen.queryByTestId('custom-tag-generation-queued')).not.toBeInTheDocument()
  })

  it('要求中はボタンの表示が「生成中...」になり、押せない', async () => {
    let finish: (r: Response) => void = () => {}
    fetchMock = installFetch({ [GENERATE]: () => new Promise<Response>((r) => (finish = r)) })
    const { container } = renderForm()

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(await screen.findByRole('button', { name: '生成中...' })).toBeDisabled()
    finish(jsonResponse({ id: 21, status: 'running' }))
    await screen.findByTestId('custom-tag-generation-queued')
  })

  it('現在のプロジェクトが指定されている場合はプロジェクト名をスコープに表示する', () => {
    render(<CustomTagGenerationForm projects={[{ id: 5, name: 'マイプロジェクト' } as Project]} currentProjectId={5} />)

    expect(screen.getByText('マイプロジェクト')).toBeInTheDocument()
  })

  it('現在のプロジェクトIDがprojects一覧に無い場合はproject#IDをスコープに表示する', () => {
    render(<CustomTagGenerationForm projects={[]} currentProjectId={99} />)

    expect(screen.getByText('project#99')).toBeInTheDocument()
  })

  it('プロジェクトに紐付かない場合はスコープに「グローバル」を表示する', () => {
    renderForm()

    expect(screen.getByText('グローバル')).toBeInTheDocument()
  })
})

/**
 * issue #1414(#1413の横展開): マウント前は生成ボタンを押せない
 * (CustomTagGenerationForm.mountGate.test.tsx)。マウント後は従来どおり押せて送信できる。
 */
describe('CustomTagGenerationForm: マウント後の送信(issue #1414)', () => {
  it('マウント後は生成ボタンが有効で、送信すると生成を呼ぶ', async () => {
    authenticated()

    const { container } = render(<CustomTagGenerationForm projects={[]} currentProjectId={null} />)

    expect(screen.getByRole('button', { name: '生成' })).toBeEnabled()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await waitFor(() => expect(generateBodies()).toHaveLength(1))
  })
})
