import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { GarbageCollectionPanel } from '../GarbageCollectionPanel'
import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'
import { answer, bodies, calledRequests, failWith, installBackend, noContent } from './projectCoreRuntime'
import { MAX_CONSECUTIVE_POLL_FAILURES } from '../useGenerationJobPolling'
import type { Project, Site } from '@/lib/apiClient'

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

const SCAN = 'GET /api/projects/1/media-garbage-collection/scan'
const DELETE = 'POST /api/projects/1/media-garbage-collection/delete'
const JOB = 'GET /api/generation-jobs/999'

// gateway の向こうのバックエンドの代役。解決値は 200 の JSON、`Response` はそのまま、`Error` の拒否は 500 の応答になる。
const scanMock = jest.fn()
const deleteMock = jest.fn()
const jobMock = jest.fn()

let fetchSpy: jest.Mock

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION)
  scanMock.mockReset()
  deleteMock.mockReset()
  jobMock.mockReset()
  fetchSpy = installBackend({
    [SCAN]: answer(() => scanMock()),
    [DELETE]: answer(() => deleteMock()),
    [JOB]: answer(() => jobMock()),
  })
})

afterEach(() => {
  jest.restoreAllMocks()
})

/** ブラウザの確認ダイアログで承認(true)かキャンセル(false)を選ぶ。jsdom は `window.confirm` を実装していない。 */
function answerConfirm(approve: boolean) {
  return jest.spyOn(window, 'confirm').mockReturnValue(approve)
}

function buildSite(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    name: 'site',
    siteKey: 'site',
    cmsType: 'WORDPRESS',
    baseUrl: 'https://example.com',
    createdAt: '',
    updatedAt: '',
    connectionCheckStatus: null,
    managedWordpress: true,
    sshConfigured: false,
    ...overrides,
  } as Site
}

function buildProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: 'project',
    slug: 'project',
    localSite: buildSite({ id: 10, siteKey: 'local-site' }),
    testSite: null,
    productionSite: null,
    masterEnvironment: 'test',
    githubRepository: null,
    createdAt: '',
    updatedAt: '',
    ...overrides,
  } as Project
}

function getSelect(labelText: string) {
  const label = screen.getByText(labelText).closest('label') as HTMLElement
  return within(label).getByRole('combobox') as HTMLSelectElement
}

// テキストが複数のDOMノードに分割される要素をマッチさせるヘルパー。
// https://testing-library.com/docs/guide-disappearance/#tips
function getByTextAcrossNodes(text: string) {
  return screen.getByText((_, element) => {
    if (!element) return false
    const hasText = (node: Element) => node.textContent === text
    const nodeHasText = hasText(element)
    const childrenDontHaveText = Array.from(element.children).every((child) => !hasText(child))
    return nodeHasText && childrenDontHaveText
  })
}

const SCAN_RESPONSE = {
  environment: 'local' as const,
  items: [
    { mediaId: '10', guid: 'https://example.com/a.jpg', title: 'a', mimeType: 'image/jpeg', uploadedAt: '2024-01-01' },
    { mediaId: '20', guid: 'https://example.com/b.jpg', title: 'b', mimeType: 'image/jpeg', uploadedAt: '2024-01-02' },
  ],
  totalMediaCount: 5,
  referencedMediaCount: 3,
  unreferencedMediaCount: 2,
}

async function selectLocalAndScan() {
  render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
  fireEvent.change(getSelect('環境'), { target: { value: 'local' } })
  fireEvent.click(screen.getByText('スキャン'))
  await waitFor(() => {
    expect(screen.getByText('a')).toBeInTheDocument()
  })
}

describe('GarbageCollectionPanel 環境の選択肢', () => {
  it('managed/SSHいずれのサイトも紐付いていない環境は選択肢に出ない', () => {
    render(
      <GarbageCollectionPanel
        projectId={1}
        project={buildProject({ localSite: null })}
      />
    )
    expect(screen.getByText('ガベージコレクション')).toBeInTheDocument()
    expect(screen.queryByText('スキャン')).not.toBeInTheDocument()
  })

  it('managedなサイトが紐付いた環境は選択肢に出る', () => {
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    const select = getSelect('環境')
    const optionLabels = Array.from(select.options).map((o) => o.textContent)
    expect(optionLabels).toContain('ローカル')
  })
})

describe('GarbageCollectionPanel スキャン', () => {
  beforeEach(() => {
      })

  it('スキャンをクリックするとfetchMediaGarbageScanActionを呼び一覧を表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)

    await selectLocalAndScan()

    expect(calledRequests(fetchSpy)).toContain('GET /api/projects/1/media-garbage-collection/scan?environment=local')
    expect(screen.getByText('b')).toBeInTheDocument()
    expect(getByTextAcrossNodes('全5件中、参照あり3件・ 未参照2件')).toBeInTheDocument()
  })

  it('スキャン失敗時はエラーメッセージを表示する', async () => {
    scanMock.mockResolvedValue(failWith(502, '接続に失敗しました')())

    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })
    fireEvent.click(screen.getByText('スキャン'))

    await waitFor(() => {
      expect(screen.getByText('APIエラー (502): 接続に失敗しました')).toBeInTheDocument()
    })
  })
})

describe('GarbageCollectionPanel 削除', () => {
  beforeEach(() => {
      })

  it('確認ダイアログをキャンセルすると削除アクションは呼ばれない', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    const confirmSpy = answerConfirm(false)

    await selectLocalAndScan()
    fireEvent.click(screen.getByText('全選択'))
    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(confirmSpy).toHaveBeenCalled()
    expect(calledRequests(fetchSpy).filter((r) => r.startsWith('POST /api/projects/1/media-garbage'))).toEqual([])
  })

  it('確認して削除するとジョブをポーリングし完了サマリーを表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue({
      id: 999,
      type: 'media_garbage_collection_delete',
      status: 'done',
      requestPayload: null,
      resultPayload: JSON.stringify({ deletedCount: 2, failedCount: 0, deletedMediaIds: ['10', '20'], failures: {} }),
      createdAt: '',
      updatedAt: '',
    })

    await selectLocalAndScan()
    fireEvent.click(screen.getByText('全選択'))
    fireEvent.click(screen.getByText(/選択した2件を削除/))

    await waitFor(() => {
      expect(bodies(fetchSpy, DELETE)).toEqual([{ mediaIds: ['10', '20'] }])
    })
    await waitFor(() => {
      expect(screen.getByText('2件削除しました。')).toBeInTheDocument()
    })
  })

  it('ジョブが失敗した場合はエラーメッセージを表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue({
      id: 999,
      type: 'media_garbage_collection_delete',
      status: 'failed',
      requestPayload: null,
      resultPayload: JSON.stringify({ error: '削除エラー' }),
      createdAt: '',
      updatedAt: '',
    })

    await selectLocalAndScan()
    fireEvent.click(screen.getByText('全選択'))
    fireEvent.click(screen.getByText(/選択した2件を削除/))

    await waitFor(() => {
      expect(screen.getByText('削除処理に失敗しました。')).toBeInTheDocument()
    })
  })
})

describe('GarbageCollectionPanel 進捗の取得失敗(#1716)', () => {
  beforeEach(() => {
    jest.useFakeTimers()
  })
  afterEach(() => {
    jest.useRealTimers()
  })

  async function startDelete() {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })
    fireEvent.click(screen.getByText('スキャン'))
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })
    fireEvent.click(screen.getByText('全選択'))
    fireEvent.click(screen.getByText(/選択した2件を削除/))
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })
  }

  it('取得が1回失敗しても続けて、done になれば完了を表示する', async () => {
    jobMock
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValue({
        id: 999,
        type: 'media_garbage_collection_delete',
        status: 'done',
        requestPayload: null,
        resultPayload: JSON.stringify({ deletedCount: 2, failedCount: 0, deletedMediaIds: ['10', '20'], failures: {} }),
        createdAt: '',
        updatedAt: '',
      })
    await startDelete()
    await act(async () => {
      await jest.advanceTimersByTimeAsync(2000)
    })

    expect(screen.getByText('2件削除しました。')).toBeInTheDocument()
  })

  it('取得が続けて失敗したら、削除中の表示を解除し、確認できなかった理由を表示する', async () => {
    jobMock.mockRejectedValue(new Error('503 Service Unavailable'))
    await startDelete()
    expect(screen.getByText('削除中…')).toBeInTheDocument()
    await act(async () => {
      await jest.advanceTimersByTimeAsync(2000 * MAX_CONSECUTIVE_POLL_FAILURES)
    })

    expect(screen.queryByText('削除中…')).not.toBeInTheDocument()
    expect(screen.getByText(/進捗を確認できませんでした.*APIエラー \(500\): 503 Service Unavailable/)).toBeInTheDocument()
  })
})

const DONE_JOB = (resultPayload: string | null, status = 'done') => ({
  id: 999,
  type: 'media_garbage_collection_delete',
  status,
  requestPayload: null,
  resultPayload,
  createdAt: '',
  updatedAt: '',
})

async function scanAndSelectAll() {
  await selectLocalAndScan()
  fireEvent.click(screen.getByText('全選択'))
}

describe('GarbageCollectionPanel 環境の選択肢(SSH管理)', () => {
  it('SSH管理のサイトが紐付いた環境も選択肢に出る', () => {
    render(
      <GarbageCollectionPanel
        projectId={1}
        project={buildProject({
          localSite: null,
          testSite: buildSite({ id: 11, managedWordpress: false, sshConfigured: true }),
        })}
      />
    )
    const labels = Array.from(getSelect('環境').options).map((o) => o.textContent)
    expect(labels).toContain('テスト')
    expect(labels).not.toContain('ローカル')
  })

  it('managedでもSSH管理でもないサイトの環境は選択肢に出ない', () => {
    render(
      <GarbageCollectionPanel
        projectId={1}
        project={buildProject({
          localSite: buildSite({ managedWordpress: false, sshConfigured: false }),
          productionSite: buildSite({ id: 12 }),
        })}
      />
    )
    const labels = Array.from(getSelect('環境').options).map((o) => o.textContent)
    expect(labels).toEqual(['選択してください', '本番'])
  })
})

describe('GarbageCollectionPanel スキャンの応答', () => {
  it('エラーも結果も無い応答なら既定のスキャン失敗メッセージを表示し、前の結果を消す', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    await selectLocalAndScan()
    scanMock.mockResolvedValue(noContent())

    fireEvent.click(screen.getByText('スキャン'))

    expect(await screen.findByText('スキャンに失敗しました。')).toBeInTheDocument()
    expect(screen.queryByText('a')).not.toBeInTheDocument()
  })

  it('環境を選ぶ前はスキャンできない(ボタンが無効で、通信しない)', () => {
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)

    const button = screen.getByText('スキャン')
    expect(button).toBeDisabled()
    fireEvent.click(button)

    expect(calledRequests(fetchSpy)).toEqual([])
  })

  it('スキャン中はボタンを「スキャン中…」にして無効にする', async () => {
    let finish: (value: unknown) => void = () => {}
    scanMock.mockReturnValue(new Promise((r) => (finish = r)))
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })

    fireEvent.click(screen.getByText('スキャン'))

    expect(await screen.findByText('スキャン中…')).toBeDisabled()
    finish(SCAN_RESPONSE)
    expect(await screen.findByText('スキャン')).toBeEnabled()
  })

  it('未参照のメディアが無ければその旨を表示し、削除の操作は出さない', async () => {
    scanMock.mockResolvedValue({ ...SCAN_RESPONSE, items: [], unreferencedMediaCount: 0 })
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })

    fireEvent.click(screen.getByText('スキャン'))

    expect(await screen.findByText('未参照のメディアはありません。')).toBeInTheDocument()
    expect(screen.queryByText('全選択')).not.toBeInTheDocument()
  })

  it('タイトルが空のメディアは「(無題)」と表示する', async () => {
    scanMock.mockResolvedValue({
      ...SCAN_RESPONSE,
      items: [{ mediaId: '30', guid: 'https://example.com/c.jpg', title: '', mimeType: 'image/png', uploadedAt: '2024-01-03' }],
    })
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })

    fireEvent.click(screen.getByText('スキャン'))

    expect(await screen.findByText('(無題)')).toBeInTheDocument()
  })
})

describe('GarbageCollectionPanel 選択', () => {
  it('行のチェックを入れると、そのメディアだけが選択される', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    await selectLocalAndScan()
    const [, first, second] = screen.getAllByRole('checkbox')

    fireEvent.click(second)

    expect(second).toBeChecked()
    expect(first).not.toBeChecked()
    expect(screen.getByText(/選択した1件を削除/)).toBeEnabled()
  })

  it('全選択したあと「全選択解除」で選択を空に戻せる', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    await scanAndSelectAll()
    expect(screen.getByText('全選択解除')).toBeInTheDocument()
    expect(screen.getByText(/選択した2件を削除/)).toBeEnabled()

    fireEvent.click(screen.getByText('全選択解除'))

    expect(screen.getByText('全選択')).toBeInTheDocument()
    expect(screen.getByText(/選択した0件を削除/)).toBeDisabled()
  })

  it('見出しのチェックボックスでも全選択でき、行のチェックを外すと選択数が減る', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    await selectLocalAndScan()
    const [header, first] = screen.getAllByRole('checkbox')

    fireEvent.click(header)
    expect(screen.getByText(/選択した2件を削除/)).toBeInTheDocument()
    fireEvent.click(first)

    expect(screen.getByText(/選択した1件を削除/)).toBeInTheDocument()
    expect(first).not.toBeChecked()
  })
})

describe('GarbageCollectionPanel 削除の開始と結果', () => {
  it('環境を選び直して空にした状態では、確認も削除も行わない', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    const confirmSpy = answerConfirm(true)
    await scanAndSelectAll()

    fireEvent.change(getSelect('環境'), { target: { value: '' } })
    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(bodies(fetchSpy, DELETE)).toEqual([])
  })

  it('削除の開始がエラーなら、削除中の表示を戻してエラーを表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue(failWith(403, '権限がありません')())
    await scanAndSelectAll()

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
    expect(screen.getByText(/選択した2件を削除/)).toBeEnabled()
  })

  it('ジョブのIDが返らない応答なら、既定の開始失敗メッセージを表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({})
    await scanAndSelectAll()

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('削除の開始に失敗しました。')).toBeInTheDocument()
  })

  it('一部の削除に失敗したときは、件数と失敗したメディアのIDと理由を表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue(
      DONE_JOB(JSON.stringify({ deletedCount: 1, failedCount: 1, deletedMediaIds: ['10'], failures: { '20': '使用中' } }))
    )
    await scanAndSelectAll()

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('1件削除しました(1件失敗)。')).toBeInTheDocument()
    expect(screen.getByText('削除に失敗したメディア:')).toBeInTheDocument()
    expect(screen.getByText('ID 20: 使用中')).toBeInTheDocument()
  })

  it('完了の結果に失敗件数・削除したID・失敗の一覧が無くても、削除した件数だけを表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue(DONE_JOB(JSON.stringify({ deletedCount: 2, failures: null, deletedMediaIds: 'x' })))
    await scanAndSelectAll()

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('2件削除しました。')).toBeInTheDocument()
    expect(screen.queryByText('削除に失敗したメディア:')).not.toBeInTheDocument()
  })

  it.each([
    ['結果が無い', null],
    ['結果が JSON でない', 'not json'],
    ['結果に削除件数が無い', JSON.stringify({ phase: 'x' })],
  ])('完了しても%sなら、削除処理に失敗したと表示する', async (_name, payload) => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue(DONE_JOB(payload))
    await scanAndSelectAll()

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('削除処理に失敗しました。')).toBeInTheDocument()
  })

  it('削除が終わる前に環境を空にすると、終わっても再スキャンしない', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    let finish: (value: unknown) => void = () => {}
    jobMock.mockReturnValue(new Promise((r) => (finish = r)))
    await scanAndSelectAll()
    fireEvent.click(screen.getByText(/選択した2件を削除/))
    await waitFor(() => expect(calledRequests(fetchSpy)).toContain(JOB))

    fireEvent.change(getSelect('環境'), { target: { value: '' } })
    finish(DONE_JOB(JSON.stringify({ deletedCount: 2, failedCount: 0, deletedMediaIds: ['10', '20'], failures: {} })))

    expect(await screen.findByText('2件削除しました。')).toBeInTheDocument()
    expect(calledRequests(fetchSpy).filter((r) => r.startsWith('GET /api/projects/1/media-garbage-collection/scan'))).toHaveLength(1)
  })

  it('削除が終わったら、同じ環境をもう一度スキャンして一覧を更新する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue(DONE_JOB(JSON.stringify({ deletedCount: 2, failedCount: 0, deletedMediaIds: ['10', '20'], failures: {} })))
    await scanAndSelectAll()
    scanMock.mockResolvedValue({ ...SCAN_RESPONSE, items: [], unreferencedMediaCount: 0 })

    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(await screen.findByText('未参照のメディアはありません。')).toBeInTheDocument()
  })
})

describe('GarbageCollectionPanel 削除中の進捗表示', () => {
  beforeEach(() => {
    jest.useFakeTimers()
  })
  afterEach(() => {
    jest.useRealTimers()
  })

  it('進捗が取れるまでは「削除を開始しています…」、進捗が届いたらその内容を表示する', async () => {
    scanMock.mockResolvedValue(SCAN_RESPONSE)
    answerConfirm(true)
    deleteMock.mockResolvedValue({ id: 999, type: 'media_garbage_collection_delete', status: 'pending' })
    jobMock.mockResolvedValue(DONE_JOB(JSON.stringify({ phase: '削除中', percent: 50 }), 'running'))
    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })
    fireEvent.click(screen.getByText('スキャン'))
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })
    fireEvent.click(screen.getByText('全選択'))

    fireEvent.click(screen.getByText(/選択した2件を削除/))
    expect(screen.getByText('削除を開始しています…')).toBeInTheDocument()
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })

    expect(screen.getByText('削除中 50%')).toBeInTheDocument()
  })
})
