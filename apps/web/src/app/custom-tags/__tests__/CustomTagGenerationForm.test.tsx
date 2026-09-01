import { render, screen, fireEvent } from '@testing-library/react'
import { useSession } from 'next-auth/react'
import { CustomTagGenerationForm } from '../CustomTagGenerationForm'
import type { Project } from '@/lib/apiClient'

jest.mock('next-auth/react')

const mockGenerate = jest.fn()
jest.mock('@/lib/useCustomTagGeneration', () => ({
  useCustomTagGeneration: () => ({
    isLoading: false,
    error: null,
    result: null,
    generate: mockGenerate,
    reset: jest.fn(),
  }),
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
