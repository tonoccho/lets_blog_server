import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { PluginRegistrationsPanel } from '../PluginRegistrationsPanel'
import type { PluginRegistration } from '@/lib/apiClient'
// 自前の @/lib/apiClient・@/lib/session・../pluginRegistrationActionsは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
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

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'

const BASE = '/api/system-settings/plugin-registrations'
let fetchMock: jest.Mock
/** gateway へ送られた「<METHOD> <パス>」と本文(JSON)。 */
const sent = () =>
  fetchMock.mock.calls
    .filter(([url]) => String(url).includes(BASE))
    .map(([url, init]) => ({
      request: `${init?.method ?? 'GET'} ${String(url).replace(/^https?:\/\/[^/]+/, '')}`,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    }))

function installOk() {
  fetchMock = installFetch({
    [`POST ${BASE}`]: () => jsonResponse({}, 201),
    [`PUT ${BASE}/:id`]: () => jsonResponse({}),
    [`DELETE ${BASE}/:id`]: () => jsonResponse(null),
    [`PUT ${BASE}/:id/in-use`]: () => jsonResponse({}),
  })
}

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
    resetServerRuntime(ADMIN_SESSION)
    installOk()
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

    expect(await within(llm).findByRole('status')).toHaveTextContent('保存しました')
    expect(sent()).toEqual([
      { request: `POST ${BASE}`, body: { kind: 'LLM', name: '社内', url: 'http://lbx-ollama:11434/v1', drawioUrl: null } },
    ])
  })

  it('保存が拒否されたら理由を表示する', async () => {
    installFetch({
      [`POST ${BASE}`]: () => textResponse('{"message":"接続先のホスト(127.0.0.1)が禁止されたアドレスを指しています"}', 409),
    })
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

    await waitFor(() => expect(sent()).toHaveLength(1))
    expect(sent()[0]).toEqual({
      request: `PUT ${BASE}/2`,
      body: { name: '予備2', url: 'http://backup.lan:11434/v1', drawioUrl: null },
    })

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

    await waitFor(() => expect(sent()).toEqual([{ request: `DELETE ${BASE}/2`, body: null }]))
  })

  it('使用中にするは id を送る', async () => {
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)

    fireEvent.click(within(region('LLM の登録')).getByRole('button', { name: '使用中にする' }))

    await waitFor(() => expect(sent()).toEqual([{ request: `PUT ${BASE}/2/in-use`, body: null }]))
  })

  it('削除・使用中の切り替えが失敗したら理由を表示する', async () => {
    installFetch({
      [`DELETE ${BASE}/2`]: () => textResponse('{"message":"削除できません"}', 409),
      [`PUT ${BASE}/2/in-use`]: () => textResponse('{"message":"切り替えられません"}', 409),
    })
    render(<PluginRegistrationsPanel registrations={REGISTRATIONS} />)
    const llm = region('LLM の登録')

    fireEvent.click(within(llm).getByRole('button', { name: '使用中にする' }))
    expect(await within(llm).findByRole('alert')).toHaveTextContent('切り替えられません')
    fireEvent.click(within(within(llm).getAllByRole('listitem')[1]).getByRole('button', { name: '削除' }))
    await waitFor(() => expect(within(llm).getByRole('alert')).toHaveTextContent('削除できません'))
  })
})
