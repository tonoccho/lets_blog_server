import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ExtensionsImportPanel } from '../ExtensionsImportPanel'
import { applyExtensionsConnectionsAction, fetchExtensionsConnectionsAction } from '../extensionsImportActions'

jest.mock('../extensionsImportActions', () => ({
  fetchExtensionsConnectionsAction: jest.fn(),
  applyExtensionsConnectionsAction: jest.fn(),
}))

const fetchMock = fetchExtensionsConnectionsAction as jest.MockedFunction<typeof fetchExtensionsConnectionsAction>
const applyMock = applyExtensionsConnectionsAction as jest.MockedFunction<typeof applyExtensionsConnectionsAction>

const CANDIDATES = {
  portalUrl: 'http://lbx-portal:3100',
  route: 'SAME_PC' as const,
  candidates: [
    { key: 'llm_ollama_base_url', label: 'Ollama ベースURL', currentValue: 'http://old:11434/v1', candidateValue: 'http://lbx-ollama:11434/v1' },
    { key: 'comfyui_base_url', label: 'ComfyUI ベースURL', currentValue: null, candidateValue: 'http://lbx-comfyui:8188' },
  ],
  ignoredIds: ['plantuml', 'gitlab'],
}

function section(): HTMLElement {
  return screen.getByRole('heading', { name: 'extensions から取り込む' }).closest('section') as HTMLElement
}

describe('ExtensionsImportPanel(issue #1783)', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    applyMock.mockReset()
  })

  it('保存済みのポータルのURLを入力済みで表示し、経路は「同じ PC」が既定で、候補はまだ出さない', () => {
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    expect(within(section()).getByLabelText('ポータルのURL')).toHaveValue('http://lbx-portal:3100')
    expect(within(section()).getByLabelText(/同じ PC/)).toBeChecked()
    expect(within(section()).getByLabelText(/別ホスト/)).not.toBeChecked()
    expect(screen.queryByRole('table', { name: '取り込み候補' })).toBeNull()
    expect(screen.queryByRole('button', { name: '反映' })).toBeNull()
  })

  it('取得すると、現在の値と取り込む値を並べ、未設定は「(未設定)」、取り込み対象外のidも表示する', async () => {
    fetchMock.mockResolvedValue(CANDIDATES)
    render(<ExtensionsImportPanel portalUrl="" />)

    fireEvent.change(within(section()).getByLabelText('ポータルのURL'), { target: { value: 'http://lbx-portal:3100' } })
    fireEvent.click(within(section()).getByLabelText(/別ホスト/))
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    const table = await screen.findByRole('table', { name: '取り込み候補' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'llm_ollama_base_url', 'http://old:11434/v1', 'http://lbx-ollama:11434/v1',
    ])
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'comfyui_base_url', '(未設定)', 'http://lbx-comfyui:8188',
    ])
    expect(within(section()).getByText(/取り込み対象外/)).toHaveTextContent('plantuml, gitlab')
    const formData = fetchMock.mock.calls[0][1] as FormData
    expect(formData.get('portalUrl')).toBe('http://lbx-portal:3100')
    expect(formData.get('route')).toBe('OTHER_HOST')
  })

  it('候補が空なら取り込める接続先が無いと案内し、反映ボタンを出さない', async () => {
    fetchMock.mockResolvedValue({ ...CANDIDATES, candidates: [], ignoredIds: [] })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    expect(await screen.findByText('取り込める接続先がありません。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '反映' })).toBeNull()
    expect(screen.queryByText(/取り込み対象外/)).toBeNull()
  })

  it('取得に失敗したら原因をalertで出し、候補は出さない', async () => {
    fetchMock.mockResolvedValue({ error: 'ポータルがエラーを返しました(HTTP 500)' })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('ポータルがエラーを返しました(HTTP 500)')
    expect(screen.queryByRole('table', { name: '取り込み候補' })).toBeNull()
  })

  it('反映すると、保存した項目と、保存されなかった項目のキーと理由を表示する', async () => {
    fetchMock.mockResolvedValue(CANDIDATES)
    applyMock.mockResolvedValue({
      saved: ['extensions_portal_url', 'comfyui_base_url'],
      rejected: [{ key: 'penpot_base_url', reason: 'penpot_base_url に指定できない接続先です。' }],
    })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('反映しました')
    expect(status).toHaveTextContent('comfyui_base_url')
    const rejected = within(section()).getByRole('list', { name: '保存されなかった項目' })
    expect(within(rejected).getByRole('listitem')).toHaveTextContent('penpot_base_url')
    expect(within(rejected).getByRole('listitem')).toHaveTextContent('指定できない接続先です。')
    const formData = applyMock.mock.calls[0][1] as FormData
    expect(formData.get('portalUrl')).toBe('http://lbx-portal:3100')
    expect(formData.get('value:llm_ollama_base_url')).toBe('http://lbx-ollama:11434/v1')
    expect(formData.get('value:comfyui_base_url')).toBe('http://lbx-comfyui:8188')
  })

  it('全て保存できたときは保存されなかった項目の一覧を出さない', async () => {
    fetchMock.mockResolvedValue(CANDIDATES)
    applyMock.mockResolvedValue({ saved: ['comfyui_base_url'], rejected: [] })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('反映しました'))
    expect(screen.queryByRole('list', { name: '保存されなかった項目' })).toBeNull()
  })

  it('反映のAPIが失敗したらエラーをalertで出す', async () => {
    fetchMock.mockResolvedValue(CANDIDATES)
    applyMock.mockResolvedValue({ error: '管理者のみ' })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('管理者のみ')
    expect(screen.queryByRole('status')).toBeNull()
  })
})
