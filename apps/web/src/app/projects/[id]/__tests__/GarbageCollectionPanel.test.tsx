import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { GarbageCollectionPanel } from '../GarbageCollectionPanel'
import * as actions from '../actions'
import { MAX_CONSECUTIVE_POLL_FAILURES } from '../useGenerationJobPolling'
import type { Project, Site } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  fetchMediaGarbageScanAction: jest.fn(),
  deleteMediaGarbageAction: jest.fn(),
  fetchGenerationJobAction: jest.fn(),
}))

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
    jest.clearAllMocks()
  })

  it('スキャンをクリックするとfetchMediaGarbageScanActionを呼び一覧を表示する', async () => {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ data: SCAN_RESPONSE })

    await selectLocalAndScan()

    expect(actions.fetchMediaGarbageScanAction).toHaveBeenCalledWith(1, 'local')
    expect(screen.getByText('b')).toBeInTheDocument()
    expect(getByTextAcrossNodes('全5件中、参照あり3件・ 未参照2件')).toBeInTheDocument()
  })

  it('スキャン失敗時はエラーメッセージを表示する', async () => {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ error: '接続に失敗しました' })

    render(<GarbageCollectionPanel projectId={1} project={buildProject()} />)
    fireEvent.change(getSelect('環境'), { target: { value: 'local' } })
    fireEvent.click(screen.getByText('スキャン'))

    await waitFor(() => {
      expect(screen.getByText('接続に失敗しました')).toBeInTheDocument()
    })
  })
})

describe('GarbageCollectionPanel 削除', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('確認ダイアログをキャンセルすると削除アクションは呼ばれない', async () => {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ data: SCAN_RESPONSE })
    window.confirm = jest.fn(() => false)

    await selectLocalAndScan()
    fireEvent.click(screen.getByText('全選択'))
    fireEvent.click(screen.getByText(/選択した2件を削除/))

    expect(window.confirm).toHaveBeenCalled()
    expect(actions.deleteMediaGarbageAction).not.toHaveBeenCalled()
  })

  it('確認して削除するとジョブをポーリングし完了サマリーを表示する', async () => {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ data: SCAN_RESPONSE })
    window.confirm = jest.fn(() => true)
    ;(actions.deleteMediaGarbageAction as jest.Mock).mockResolvedValue({ jobId: 999 })
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue({
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
      expect(actions.deleteMediaGarbageAction).toHaveBeenCalledWith(1, 'local', ['10', '20'])
    })
    await waitFor(() => {
      expect(screen.getByText('2件削除しました。')).toBeInTheDocument()
    })
  })

  it('ジョブが失敗した場合はエラーメッセージを表示する', async () => {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ data: SCAN_RESPONSE })
    window.confirm = jest.fn(() => true)
    ;(actions.deleteMediaGarbageAction as jest.Mock).mockResolvedValue({ jobId: 999 })
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue({
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
    jest.clearAllMocks()
    jest.useFakeTimers()
  })
  afterEach(() => {
    jest.useRealTimers()
  })

  async function startDelete() {
    ;(actions.fetchMediaGarbageScanAction as jest.Mock).mockResolvedValue({ data: SCAN_RESPONSE })
    window.confirm = jest.fn(() => true)
    ;(actions.deleteMediaGarbageAction as jest.Mock).mockResolvedValue({ jobId: 999 })
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
    ;(actions.fetchGenerationJobAction as jest.Mock)
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
    ;(actions.fetchGenerationJobAction as jest.Mock).mockRejectedValue(new Error('503 Service Unavailable'))
    await startDelete()
    expect(screen.getByText('削除中…')).toBeInTheDocument()
    await act(async () => {
      await jest.advanceTimersByTimeAsync(2000 * MAX_CONSECUTIVE_POLL_FAILURES)
    })

    expect(screen.queryByText('削除中…')).not.toBeInTheDocument()
    expect(screen.getByText(/進捗を確認できませんでした.*503 Service Unavailable/)).toBeInTheDocument()
  })
})
