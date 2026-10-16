import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ExtensionsImportPanel } from '../ExtensionsImportPanel'
// 自前の @/lib/apiClient・@/lib/session・../extensionsImportActionsは差し替えない。差し替えるのは Next.js の実行基盤と
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

const FETCH = 'POST /api/system-settings/app-settings/extensions-import/fetch'
const APPLY = 'POST /api/system-settings/app-settings/extensions-import/apply'
let fetchMock: jest.Mock
const sentBodies = (suffix: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(suffix) && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init.body)))

/** gateway の応答(extensions のポータルの候補)。ポータルの URL・経路は画面の入力値として返る。 */
function candidatesResponse(result: { candidates: unknown[]; ignoredIds: string[] }) {
  return { candidates: result.candidates, ignoredIds: result.ignoredIds }
}

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
    resetServerRuntime(ADMIN_SESSION)
    fetchMock = installFetch({ [FETCH]: () => jsonResponse(candidatesResponse(CANDIDATES)), [APPLY]: () => jsonResponse({ saved: [], rejected: [] }) })
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
    expect(sentBodies('/extensions-import/fetch')).toEqual([{ portalUrl: 'http://lbx-portal:3100', route: 'OTHER_HOST' }])
  })

  it('候補が空なら取り込める接続先が無いと案内し、反映ボタンを出さない', async () => {
    installFetch({ [FETCH]: () => jsonResponse(candidatesResponse({ candidates: [], ignoredIds: [] })) })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    expect(await screen.findByText('取り込める接続先がありません。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '反映' })).toBeNull()
    expect(screen.queryByText(/取り込み対象外/)).toBeNull()
  })

  it('取得に失敗したら原因をalertで出し、候補は出さない', async () => {
    installFetch({ [FETCH]: () => textResponse('{"message":"ポータルがエラーを返しました(HTTP 500)"}', 409) })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('ポータルがエラーを返しました(HTTP 500)')
    expect(screen.queryByRole('table', { name: '取り込み候補' })).toBeNull()
  })

  it('反映すると、保存した項目と、保存されなかった項目のキーと理由を表示する', async () => {
    fetchMock = installFetch({
      [FETCH]: () => jsonResponse(candidatesResponse(CANDIDATES)),
      [APPLY]: () =>
        jsonResponse({
          saved: ['extensions_portal_url', 'comfyui_base_url'],
          rejected: [{ key: 'penpot_base_url', reason: 'penpot_base_url に指定できない接続先です。' }],
        }),
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
    expect(sentBodies('/extensions-import/apply')).toEqual([
      {
        portalUrl: 'http://lbx-portal:3100',
        values: { llm_ollama_base_url: 'http://lbx-ollama:11434/v1', comfyui_base_url: 'http://lbx-comfyui:8188' },
      },
    ])
  })

  it('全て保存できたときは保存されなかった項目の一覧を出さない', async () => {
    installFetch({
      [FETCH]: () => jsonResponse(candidatesResponse(CANDIDATES)),
      [APPLY]: () => jsonResponse({ saved: ['comfyui_base_url'], rejected: [] }),
    })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('反映しました'))
    expect(screen.queryByRole('list', { name: '保存されなかった項目' })).toBeNull()
  })

  it('反映のAPIが失敗したらエラーをalertで出す', async () => {
    installFetch({
      [FETCH]: () => jsonResponse(candidatesResponse(CANDIDATES)),
      [APPLY]: () => textResponse('{"message":"管理者のみ"}', 403),
    })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('管理者のみ')
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('反映が結果に rejected を含めない(本文が空の)応答でも、保存した項目だけを表示する', async () => {
    installFetch({
      [FETCH]: () => jsonResponse(candidatesResponse(CANDIDATES)),
      [APPLY]: () => jsonResponse({ saved: ['comfyui_base_url'] }),
    })
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))
    fireEvent.click(await screen.findByRole('button', { name: '反映' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('comfyui_base_url'))
    expect(screen.queryByRole('list', { name: '保存されなかった項目' })).toBeNull()
  })

  it('経路を「別ホスト」から「同じ PC」へ戻すと、取得は「同じ PC」で送る', async () => {
    render(<ExtensionsImportPanel portalUrl="http://lbx-portal:3100" />)

    fireEvent.click(within(section()).getByLabelText(/別ホスト/))
    fireEvent.click(within(section()).getByLabelText(/同じ PC/))
    fireEvent.click(within(section()).getByRole('button', { name: '取得' }))

    await screen.findByRole('table', { name: '取り込み候補' })
    expect(sentBodies('/extensions-import/fetch')).toEqual([{ portalUrl: 'http://lbx-portal:3100', route: 'SAME_PC' }])
  })
})
