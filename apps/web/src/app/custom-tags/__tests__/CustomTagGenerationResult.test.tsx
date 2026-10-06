import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CustomTagGenerationResult } from '../CustomTagGenerationResult'

const mockValidate = jest.fn()
const useValidationMock = jest.fn()
jest.mock('@/lib/useCustomTagValidation', () => ({
  useCustomTagValidation: () => useValidationMock(),
}))
jest.mock('../ValidationPanel', () => ({
  ValidationPanel: () => <div>ValidationPanel</div>,
}))

const mockSave = jest.fn()
jest.mock('@/app/projects/[id]/custom-tags/actions', () => ({
  upsertProjectCustomTagAction: (...args: unknown[]) => mockSave(...args),
}))

const RESULT = {
  jobId: 21,
  tagName: 'blue-button',
  description: '青いボタン',
  projectId: 7,
  htmlTemplate: '<button>{{content}}</button>',
  cssContent: '.blue-button { color: blue; }',
}

/**
 * issue #1409: 処理キューの「結果を見る」の遷移先。生成結果(ジョブの結果にだけある)を確認し、
 * 「保存」を押したときに初めて既存の保存先(カスタムタグ)へ登録する。
 */
describe('CustomTagGenerationResult (#1409)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockValidate.mockResolvedValue(undefined)
    useValidationMock.mockReturnValue({
      isLoading: false,
      error: null,
      result: null,
      validate: mockValidate,
      reset: jest.fn(),
    })
  })

  it('shows the generated tag as not yet saved, with its HTML and CSS', () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    const panel = screen.getByTestId('custom-tag-generation-result')
    expect(panel).toHaveAttribute('data-job-id', '21')
    expect(panel).toHaveTextContent('保存されていません')
    expect(panel).toHaveTextContent('[blue-button]')
    expect(panel).toHaveTextContent('青いボタン')
    expect(panel).toHaveTextContent('<button>{{content}}</button>')
    expect(panel).toHaveTextContent('.blue-button { color: blue; }')
    expect(screen.queryByText(/保存済みです/)).not.toBeInTheDocument()
  })

  it('validates the generated content once on display', async () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    await waitFor(() => expect(mockValidate).toHaveBeenCalledWith('<button>{{content}}</button>', '.blue-button { color: blue; }'))
    expect(screen.getByText('ValidationPanel')).toBeInTheDocument()
  })

  it('does not crash when the validation request itself fails', async () => {
    mockValidate.mockRejectedValue(new Error('down'))
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    await waitFor(() => expect(mockValidate).toHaveBeenCalled())
    expect(screen.getByTestId('custom-tag-generation-result')).toBeInTheDocument()
  })

  it('shows "(なし)" for a missing description and hides the CSS section for an empty CSS', () => {
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, description: null, cssContent: '' }} />)

    expect(screen.getByText('(なし)')).toBeInTheDocument()
    expect(screen.queryByText('CSS:')).not.toBeInTheDocument()
    expect(mockValidate).toHaveBeenCalledWith('<button>{{content}}</button>', '')
  })

  it('explains the selector prefix that the bundled CSS will get, using the first selector', () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} effectivePrefix="myproject" />)

    expect(screen.getAllByText(/myproject/, { selector: 'code' })).toHaveLength(2)
    expect(screen.getByText(/\.myproject \.blue-button/)).toBeInTheDocument()
  })

  it('falls back to "..." when the first selector cannot be taken out of the CSS', () => {
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, cssContent: ' { color: blue; }' }} effectivePrefix="myproject" />)

    expect(screen.getByText(/myproject \.\.\./)).toBeInTheDocument()
  })

  it('"保存" registers the tag through the existing tag save action for the project, then says it is saved', async () => {
    mockSave.mockResolvedValue({ success: true })
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(screen.getByText('保存しました。')).toBeInTheDocument())
    const [, formData] = mockSave.mock.calls[0] as [unknown, FormData]
    expect(Object.fromEntries(formData.entries())).toEqual({
      projectId: '7',
      tagName: 'blue-button',
      description: '青いボタン',
      htmlTemplate: '<button>{{content}}</button>',
      cssContent: '.blue-button { color: blue; }',
    })
    expect(formData.has('id')).toBe(false)
    // 二重に保存させない
    expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument()
  })

  it('saves without a description and a CSS when the generation had none', async () => {
    mockSave.mockResolvedValue({ success: true })
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, description: null, cssContent: '' }} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(mockSave).toHaveBeenCalled())
    const [, formData] = mockSave.mock.calls[0] as [unknown, FormData]
    expect(formData.get('description')).toBe('')
    expect(formData.get('cssContent')).toBe('')
  })

  it('shows the reason when the save is refused (e.g. a tag with the same name exists) and keeps the button', async () => {
    mockSave.mockResolvedValue({ error: 'タグ名 blue-button は既に登録されています' })
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(screen.getByText('タグ名 blue-button は既に登録されています')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
    expect(screen.queryByText('保存しました。')).not.toBeInTheDocument()
  })

  it('disables the button while saving', async () => {
    let resolve: (v: unknown) => void = () => {}
    mockSave.mockReturnValue(new Promise((r) => (resolve = r)))
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('button', { name: '保存中…' })).toBeDisabled()
    resolve({ success: true })
    await waitFor(() => expect(screen.getByText('保存しました。')).toBeInTheDocument())
  })
})
