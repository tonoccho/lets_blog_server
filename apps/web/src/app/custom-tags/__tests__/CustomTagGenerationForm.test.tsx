import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { useSession } from 'next-auth/react'
import { CustomTagGenerationForm } from '../CustomTagGenerationForm'
import type { Project } from '@/lib/apiClient'

jest.mock('next-auth/react')

const mockGenerate = jest.fn()
const mockReset = jest.fn()
const useCustomTagGenerationMock = jest.fn(() => ({
  isLoading: false,
  error: null as string | null,
  queuedJobId: null as number | null,
  generate: mockGenerate,
  reset: mockReset,
}))
jest.mock('@/lib/useCustomTagGeneration', () => ({
  useCustomTagGeneration: () => useCustomTagGenerationMock(),
}))

const mockedUseSession = useSession as jest.MockedFunction<typeof useSession>

function authenticated() {
  mockedUseSession.mockReturnValue({
    data: { user: { email: 'a@example.com', role: 'admin' }, expires: '2099-01-01' },
    status: 'authenticated',
    update: jest.fn(),
  } as unknown as ReturnType<typeof useSession>)
}

/**
 * issue #778: セッション未解決の間に「生成」を押すと、handleSubmit が黙って return し、
 * isLoading も error も立たないため「押しても何も起きない」無反応状態になっていた。
 */
describe('CustomTagGenerationForm: セッション未解決時の挙動(issue #778)', () => {
  const projects: Project[] = []

  beforeEach(() => {
    jest.clearAllMocks()
  })

  function renderForm() {
    return render(<CustomTagGenerationForm projects={projects} currentProjectId={null} />)
  }

  it('セッション未解決の間は生成ボタンを押せず、状態がラベルに出る', () => {
    mockedUseSession.mockReturnValue({
      data: null,
      status: 'loading',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    renderForm()

    const button = screen.getByRole('button', { name: /セッション確認中/ })
    expect(button).toBeDisabled()
    // 押せない = 送信が捨てられることも無い
    fireEvent.click(button)
    expect(mockGenerate).not.toHaveBeenCalled()
  })

  it('セッション未解決中に(ボタンの外から)送信されても、確認中である旨を表示する', () => {
    mockedUseSession.mockReturnValue({
      data: null,
      status: 'loading',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    const { container } = renderForm()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(mockGenerate).not.toHaveBeenCalled()
    expect(screen.getByText('セッションを確認しています。少し待ってからもう一度お試しください。')).toBeInTheDocument()
  })

  it('セッションが無い状態で送信されたら、黙って捨てず理由を表示する', () => {
    mockedUseSession.mockReturnValue({
      data: null,
      status: 'unauthenticated',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    const { container } = renderForm()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(mockGenerate).not.toHaveBeenCalled()
    expect(screen.getByText(/セッションが確認できませんでした/)).toBeInTheDocument()
  })

  it('セッション解決後は従来どおり生成を要求できる', async () => {
    authenticated()
    mockGenerate.mockResolvedValue(21)

    const { container } = renderForm()

    const button = screen.getByRole('button', { name: '生成' })
    expect(button).not.toBeDisabled()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => expect(mockGenerate).toHaveBeenCalled())
  })
})

/**
 * issue #1409: 生成は非同期ジョブとして要求し、生成と同時には保存しない。受理されたら
 * 「処理キューに追加された」旨を示し、結果は処理キューの「結果を見る」から確認して「保存」で登録する。
 * issue #1051: <form>にはmethod="post"を明示している。
 */
describe('CustomTagGenerationForm: 非同期ジョブとしての要求(issue #1409)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      queuedJobId: null,
      generate: mockGenerate,
      reset: mockReset,
    })
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
    mockGenerate.mockResolvedValue(21)
    const { container } = renderForm({ currentProjectId: 7, projects: [{ id: 7, name: 'P' } as Project] })

    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: '青いボタン' } })
    fireEvent.change(screen.getByLabelText(/タグ名/), { target: { value: 'blue-button' } })
    fireEvent.change(screen.getByLabelText(/説明\(任意\)/), { target: { value: '説明です' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() =>
      expect(mockGenerate).toHaveBeenCalledWith({
        prompt: '青いボタン',
        tagName: 'blue-button',
        description: '説明です',
        projectId: 7,
      })
    )
  })

  it('説明が空なら description を送らない', async () => {
    mockGenerate.mockResolvedValue(21)
    const { container } = renderForm()

    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'p' } })
    fireEvent.change(screen.getByLabelText(/タグ名/), { target: { value: 't' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() =>
      expect(mockGenerate).toHaveBeenCalledWith({ prompt: 'p', tagName: 't', description: undefined, projectId: null })
    )
  })

  it('要求が受理されたら、保存済みとは言わず、処理キューの「結果を見る」から確認して「保存」する旨を示し、入力欄を空にする', async () => {
    mockGenerate.mockResolvedValue(21)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      queuedJobId: 21,
      generate: mockGenerate,
      reset: mockReset,
    })
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

  it('受理されなかったとき(生成の要求が undefined を返す)は入力欄を空にしない', async () => {
    mockGenerate.mockResolvedValue(undefined)
    const { container } = renderForm()
    const prompt = screen.getByLabelText(/プロンプト/) as HTMLTextAreaElement
    fireEvent.change(prompt, { target: { value: 'テスト' } })

    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => expect(mockGenerate).toHaveBeenCalled())
    expect(prompt.value).toBe('テスト')
  })

  it('受理の拒否理由(エラー)をフォームに表示し、受理の通知は出さない', () => {
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: 'APIエラー (403): 権限がありません',
      queuedJobId: null,
      generate: mockGenerate,
      reset: mockReset,
    })

    renderForm()

    expect(screen.getByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
    expect(screen.queryByTestId('custom-tag-generation-queued')).not.toBeInTheDocument()
  })

  it('要求中はボタンの表示が「生成中...」になる', () => {
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: true,
      error: null,
      queuedJobId: null,
      generate: mockGenerate,
      reset: mockReset,
    })

    renderForm()

    expect(screen.getByRole('button', { name: '生成中...' })).toBeDisabled()
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
  it('マウント後は生成ボタンが有効で、送信すると生成を呼ぶ', () => {
    jest.clearAllMocks()
    authenticated()

    const { container } = render(<CustomTagGenerationForm projects={[]} currentProjectId={null} />)

    expect(screen.getByRole('button', { name: '生成' })).toBeEnabled()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    expect(mockGenerate).toHaveBeenCalled()
  })
})
