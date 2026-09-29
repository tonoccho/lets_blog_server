import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { useSession } from 'next-auth/react'
import { CustomTagGenerationForm } from '../CustomTagGenerationForm'
import type { CustomTag, Project } from '@/lib/apiClient'

jest.mock('next-auth/react')

const mockGenerate = jest.fn()
const mockReset = jest.fn()
const useCustomTagGenerationMock = jest.fn(() => ({
  isLoading: false,
  error: null,
  result: null as CustomTag | null,
  generate: mockGenerate,
  reset: mockReset,
}))
jest.mock('@/lib/useCustomTagGeneration', () => ({
  useCustomTagGeneration: () => useCustomTagGenerationMock(),
}))

jest.mock('@/lib/useCustomTagValidation', () => ({
  useCustomTagValidation: () => ({
    isLoading: false,
    error: null,
    result: null,
    validate: jest.fn(),
    reset: jest.fn(),
  }),
}))

jest.mock('../ValidationPanel', () => ({
  ValidationPanel: () => <div>ValidationPanel</div>,
}))

const mockedUseSession = useSession as jest.MockedFunction<typeof useSession>

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
    return render(
      <CustomTagGenerationForm
        projects={projects}
        currentProjectId={null}
        onGenerationSuccess={jest.fn()}
      />
    )
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
    // ボタンはdisabledで押せないが、Enterキー送信等でフォーム自体のsubmitイベントは
    // 発生しうる経路を想定して、その場合にも黙って捨てないことを確認する。
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
    // status が loading を抜けてもセッションが無い場合(セッション切れ・取得失敗)。
    // ボタンは押せるため、ここで捨てると無反応になる。
    mockedUseSession.mockReturnValue({
      data: null,
      status: 'unauthenticated',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    const { container } = renderForm()
    const form = container.querySelector('form')
    expect(form).not.toBeNull()

    fireEvent.submit(form as HTMLFormElement)

    expect(mockGenerate).not.toHaveBeenCalled()
    expect(screen.getByText(/セッションが確認できませんでした/)).toBeInTheDocument()
  })

  it('セッション解決後は従来どおり生成できる', async () => {
    mockedUseSession.mockReturnValue({
      data: { user: { email: 'a@example.com', role: 'admin' }, expires: '2099-01-01' },
      status: 'authenticated',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    const { container } = renderForm()

    const button = screen.getByRole('button', { name: '生成' })
    expect(button).not.toBeDisabled()

    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    expect(mockGenerate).toHaveBeenCalled()
  })
})

/**
 * issue #1051: <form>にmethod="post"を明示した(このIssueでの変更点自体はJSX属性の追加のみで
 * 新たな分岐は生じない)。ついでに、生成成功後の結果表示・生成失敗時のcatch経路・結果画面から
 * フォームへ戻る経路は元々未検証だったため、この機会にあわせて押さえる。
 */
describe('CustomTagGenerationForm: 生成結果の表示とやり直し', () => {
  const projects: Project[] = []

  function tag(overrides: Partial<CustomTag> = {}): CustomTag {
    return {
      id: 1,
      tagName: 'my-button',
      htmlTemplate: '<button>OK</button>',
      description: null,
      cssContent: '.my-button { color: blue; }',
      tagFormat: 'INLINE',
      projectId: null,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      penpotFileUrl: 'https://penpot.example.com/file/1',
      ...overrides,
    }
  }

  beforeEach(() => {
    jest.clearAllMocks()
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: null,
      generate: mockGenerate,
      reset: mockReset,
    })
    mockedUseSession.mockReturnValue({
      data: { user: { email: 'a@example.com', role: 'admin' }, expires: '2099-01-01' },
      status: 'authenticated',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  function renderForm() {
    return render(
      <CustomTagGenerationForm
        projects={projects}
        currentProjectId={null}
        effectivePrefix="myproject"
        onGenerationSuccess={jest.fn()}
      />
    )
  }

  it('<form>はmethod="post"を持つ', () => {
    const { container } = renderForm()

    expect(container.querySelector('form')?.getAttribute('method')).toBe('post')
  })

  it('生成に成功すると結果(CSS・Penpotリンクを含む)を表示し、使用後はフォームへ戻る', async () => {
    const generatedTag = tag()
    mockGenerate.mockResolvedValue(generatedTag)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: generatedTag,
      generate: mockGenerate,
      reset: mockReset,
    })

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.getByText('生成完了！')).toBeInTheDocument()
    })
    expect(screen.getAllByText(/myproject/).length).toBeGreaterThan(0)
    expect(screen.getByRole('link', { name: 'Penpotで開く →' })).toHaveAttribute(
      'href',
      'https://penpot.example.com/file/1'
    )

    fireEvent.click(screen.getByRole('button', { name: 'この結果を使用' }))
    expect(mockReset).toHaveBeenCalled()
  })

  it('結果表示中に「別のプロンプトを試す」を押すとフォームへ戻る', async () => {
    const generatedTag = tag()
    mockGenerate.mockResolvedValue(generatedTag)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: generatedTag,
      generate: mockGenerate,
      reset: mockReset,
    })

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.getByText('生成完了！')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: '別のプロンプトを試す' }))

    expect(mockReset).toHaveBeenCalled()
    expect(screen.queryByText('生成完了！')).not.toBeInTheDocument()
  })

  it('生成が例外を投げても画面をクラッシュさせない', async () => {
    mockGenerate.mockRejectedValue(new Error('生成に失敗しました'))

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(mockGenerate).toHaveBeenCalled()
    })
    expect(screen.getByRole('button', { name: '生成' })).toBeInTheDocument()
  })

  it('CSSを持たない生成結果は検証をcssContent無しで呼び出し、CSS欄・Penpot欄を表示しない', async () => {
    const generatedTag = tag({ cssContent: null, penpotFileUrl: null })
    mockGenerate.mockResolvedValue(generatedTag)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: generatedTag,
      generate: mockGenerate,
      reset: mockReset,
    })

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.getByText('生成完了！')).toBeInTheDocument()
    })
    expect(screen.queryByText('CSS:')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Penpotで開く →' })).not.toBeInTheDocument()
  })

  it('説明が設定された生成結果はその説明文を表示する', async () => {
    const generatedTag = tag({ description: '青いボタン' })
    mockGenerate.mockResolvedValue(generatedTag)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: generatedTag,
      generate: mockGenerate,
      reset: mockReset,
    })

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.getByText('青いボタン')).toBeInTheDocument()
    })
  })

  it('CSSの先頭セレクタが取り出せない場合はプレフィックス例に「...」を使う', async () => {
    const generatedTag = tag({ cssContent: ' { color: blue; }' })
    mockGenerate.mockResolvedValue(generatedTag)
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: generatedTag,
      generate: mockGenerate,
      reset: mockReset,
    })

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.getByText('生成完了！')).toBeInTheDocument()
    })
    expect(screen.getByText(/myproject \.\.\./)).toBeInTheDocument()
  })

  it('生成中はボタンの表示が「生成中...」になる', () => {
    useCustomTagGenerationMock.mockReturnValue({
      isLoading: true,
      error: null,
      result: null,
      generate: mockGenerate,
      reset: mockReset,
    })

    renderForm()

    expect(screen.getByRole('button', { name: '生成中...' })).toBeDisabled()
  })

  it('現在のプロジェクトが指定されている場合はプロジェクト名をスコープに表示する', () => {
    render(
      <CustomTagGenerationForm
        projects={[{ id: 5, name: 'マイプロジェクト' } as Project]}
        currentProjectId={5}
        onGenerationSuccess={jest.fn()}
      />
    )

    expect(screen.getByText('マイプロジェクト')).toBeInTheDocument()
  })

  it('現在のプロジェクトIDがprojects一覧に無い場合はproject#IDをスコープに表示する', () => {
    render(
      <CustomTagGenerationForm projects={[]} currentProjectId={99} onGenerationSuccess={jest.fn()} />
    )

    expect(screen.getByText('project#99')).toBeInTheDocument()
  })

  it('生成に成功しても結果(hookのresult)がまだ無ければ何も表示しない', async () => {
    // resultはフックの内部状態であり、generateの解決と同じレンダーで即座に更新される保証は
    // 実装上無い(このモックは意図的にresultをnullのまま据え置く)。showResultsだけがtrueに
    // なった場合に何も描画せず、クラッシュもしないことを確認する。
    mockGenerate.mockResolvedValue(tag())

    const { container } = renderForm()
    fireEvent.change(screen.getByLabelText(/プロンプト/), { target: { value: 'テスト' } })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)

    await waitFor(() => {
      expect(screen.queryByLabelText(/プロンプト/)).not.toBeInTheDocument()
    })
    expect(screen.queryByText('生成完了！')).not.toBeInTheDocument()
  })
})

/**
 * issue #1414(#1413の横展開): マウント前は生成ボタンを押せない
 * (CustomTagGenerationForm.mountGate.test.tsx)。マウント後は従来どおり押せて送信できる。
 */
describe('CustomTagGenerationForm: マウント後の送信(issue #1414)', () => {
  it('マウント後は生成ボタンが有効で、送信すると生成を呼ぶ', () => {
    mockedUseSession.mockReturnValue({
      data: { user: { email: 'a@example.com', role: 'admin' }, expires: '2099-01-01' },
      status: 'authenticated',
      update: jest.fn(),
    } as unknown as ReturnType<typeof useSession>)

    const { container } = render(
      <CustomTagGenerationForm projects={[]} currentProjectId={null} onGenerationSuccess={jest.fn()} />
    )

    expect(screen.getByRole('button', { name: '生成' })).toBeEnabled()
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    expect(mockGenerate).toHaveBeenCalled()
  })
})
