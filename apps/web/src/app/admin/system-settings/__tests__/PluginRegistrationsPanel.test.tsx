import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { PluginRegistrationsPanel } from '../PluginRegistrationsPanel'
import {
  activatePluginRegistrationAction,
  deletePluginRegistrationAction,
  savePluginRegistrationAction,
} from '../pluginRegistrationActions'
import type { PluginRegistration } from '@/lib/apiClient'

jest.mock('../pluginRegistrationActions', () => ({
  savePluginRegistrationAction: jest.fn(),
  deletePluginRegistrationAction: jest.fn(),
  activatePluginRegistrationAction: jest.fn(),
}))

const saveMock = savePluginRegistrationAction as jest.MockedFunction<typeof savePluginRegistrationAction>
const deleteMock = deletePluginRegistrationAction as jest.MockedFunction<typeof deletePluginRegistrationAction>
const activateMock = activatePluginRegistrationAction as jest.MockedFunction<typeof activatePluginRegistrationAction>

const REGISTRATIONS: PluginRegistration[] = [
  { id: 1, kind: 'LLM', name: '社内のOllama', url: 'http://lbx-ollama:11434/v1', inUse: true, drawioUrl: null },
  { id: 2, kind: 'LLM', name: '予備', url: 'http://backup.lan:11434/v1', inUse: false, drawioUrl: null },
  { id: 3, kind: 'DIAGRAM_RENDERER', name: '図', url: 'http://lbx-plantuml:8085', inUse: false, drawioUrl: 'https://drawio.example/' },
]

function region(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('PluginRegistrationsPanel(issue #1807)', () => {
  beforeEach(() => {
    saveMock.mockReset().mockResolvedValue({ saved: true })
    deleteMock.mockReset().mockResolvedValue({ saved: true })
    activateMock.mockReset().mockResolvedValue({ saved: true })
  })

  it('従来の設定項目が使われることを1行で注記し、4種類の欄を出す', () => {
    render(<PluginRegistrationsPanel registrations={[]} />)

    expect(screen.getByRole('heading', { name: 'プラグインの登録' })).toBeInTheDocument()
    expect(screen.getByText(/従来の設定項目が使われます/)).toBeInTheDocument()
    for (const name of ['LLM の登録', '画像生成 の登録', '図のレンダリング の登録', 'Penpot の登録']) {
      expect(region(name)).toBeInTheDocument()
    }
    expect(within(region('Penpot の登録')).getByText('登録はありません')).toBeInTheDocument()
  })

  it('登録を種類ごとに名前とURLで並べ、使用中を示し、使用中でないLLMにだけ「使用中にする」を出す', () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)

    const llm = region('LLM の登録')
    const items = within(llm).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('社内のOllama')
    expect(items[0]).toHaveTextContent('http://lbx-ollama:11434/v1')
    expect(items[0]).toHaveTextContent('使用中')
    expect(within(items[0]).queryByRole('button', { name: '使用中にする' })).toBeNull()
    expect(within(items[1]).getByRole('button', { name: '使用中にする' })).toBeInTheDocument()
    expect(within(items[1]).queryByText('使用中', { exact: true })).toBeNull()

    const diagram = within(region('図のレンダリング の登録')).getByRole('listitem')
    expect(diagram).toHaveTextContent('https://drawio.example/')
    expect(within(diagram).queryByRole('button', { name: '使用中にする' })).toBeNull()
  })

  it('drawioUrl の入力欄は図のレンダリングの追加フォームだけにある', () => {
    render(<PluginRegistrationsPanel registrations={[]} />)

    expect(within(region('図のレンダリング の登録')).getByLabelText('drawioUrl(任意)')).toBeInTheDocument()
    expect(within(region('LLM の登録')).queryByLabelText('drawioUrl(任意)')).toBeNull()
  })

  it('追加すると種類・名前・URLを送り、保存結果を表示する', async () => {
    render(<PluginRegistrationsPanel registrations={[]} />)
    const llm = region('LLM の登録')

    fireEvent.change(within(llm).getByLabelText('名前'), { target: { value: '社内' } })
    fireEvent.change(within(llm).getByLabelText('URL'), { target: { value: 'http://lbx-ollama:11434/v1' } })
    fireEvent.click(within(llm).getByRole('button', { name: '追加' }))

    await waitFor(() => expect(saveMock).toHaveBeenCalled())
    const data = saveMock.mock.calls[0][1] as FormData
    expect(data.get('kind')).toBe('LLM')
    expect(data.get('name')).toBe('社内')
    expect(data.get('url')).toBe('http://lbx-ollama:11434/v1')
    expect(await within(llm).findByRole('status')).toHaveTextContent('保存しました')
  })

  it('保存が拒否されたら理由を表示する', async () => {
    saveMock.mockResolvedValue({ error: '接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています' })
    render(<PluginRegistrationsPanel registrations={[]} />)
    const llm = region('LLM の登録')

    fireEvent.click(within(llm).getByRole('button', { name: '追加' }))

    expect(await within(llm).findByRole('alert')).toHaveTextContent('禁止されたアドレス')
    expect(within(llm).queryByRole('status')).toBeNull()
  })

  it('編集は既存の値を入れたフォームを出し、更新するとidつきで送る。キャンセルで閉じる', async () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)
    const llm = region('LLM の登録')

    fireEvent.click(within(within(llm).getAllByRole('listitem')[1]).getByRole('button', { name: '編集' }))
    const form = within(llm).getByRole('form', { name: '予備 を編集' })
    expect(within(form).getByLabelText('名前')).toHaveValue('予備')
    expect(within(form).getByLabelText('URL')).toHaveValue('http://backup.lan:11434/v1')
    fireEvent.change(within(form).getByLabelText('名前'), { target: { value: '予備2' } })
    fireEvent.click(within(form).getByRole('button', { name: '更新' }))

    await waitFor(() => expect(saveMock).toHaveBeenCalled())
    const data = saveMock.mock.calls[0][1] as FormData
    expect(data.get('id')).toBe('2')
    expect(data.get('name')).toBe('予備2')

    // 更新を送ると編集フォームは閉じる(送信の完了は非同期なので待つ)
    fireEvent.click(await within(within(llm).getAllByRole('listitem')[1]).findByRole('button', { name: '編集' }))
    fireEvent.click(within(llm).getByRole('button', { name: 'キャンセル' }))
    expect(within(llm).queryByRole('form', { name: '予備 を編集' })).toBeNull()
  })

  it('図のレンダリングの編集フォームは drawioUrl を入れて出す', () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)
    const diagram = region('図のレンダリング の登録')

    fireEvent.click(within(diagram).getByRole('button', { name: '編集' }))

    expect(within(diagram).getByRole('form', { name: '図 を編集' }).querySelector('input[name="drawioUrl"]'))
      .toHaveValue('https://drawio.example/')
  })

  it('削除は id を送る', async () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)

    fireEvent.click(within(within(region('LLM の登録')).getAllByRole('listitem')[1]).getByRole('button', { name: '削除' }))

    await waitFor(() => expect(deleteMock).toHaveBeenCalled())
    expect((deleteMock.mock.calls[0][1] as FormData).get('id')).toBe('2')
  })

  it('使用中にするは id を送る', async () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)

    fireEvent.click(within(region('LLM の登録')).getByRole('button', { name: '使用中にする' }))

    await waitFor(() => expect(activateMock).toHaveBeenCalled())
    expect((activateMock.mock.calls[0][1] as FormData).get('id')).toBe('2')
  })

  it('削除・使用中の切り替えが失敗したら理由を表示する', async () => {
    deleteMock.mockResolvedValue({ error: '削除できません' })
    activateMock.mockResolvedValue({ error: '切り替えられません' })
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)
    const llm = region('LLM の登録')

    fireEvent.click(within(llm).getByRole('button', { name: '使用中にする' }))
    expect(await within(llm).findByRole('alert')).toHaveTextContent('切り替えられません')
    fireEvent.click(within(within(llm).getAllByRole('listitem')[1]).getByRole('button', { name: '削除' }))
    await waitFor(() => expect(within(llm).getByRole('alert')).toHaveTextContent('削除できません'))
  })
})
