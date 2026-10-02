/**
 * @jest-environment node
 */

/**
 * issue #1471: admin 限定の「遅い操作」画面。ページを直接呼び、返る JSX ツリーを検査する
 * (`operation-logs/__tests__/page.test.tsx` と同じ手法)。
 */
jest.mock('server-only', () => ({}))

const requireAdminSession = jest.fn()
const getViewerTimeZone = jest.fn()
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerTimeZone: (...a: unknown[]) => getViewerTimeZone(...a),
}))

const getRouteStats = jest.fn()
const getOperationStats = jest.fn()
const getOperationTrace = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  getRouteStats: (...a: unknown[]) => getRouteStats(...a),
  getOperationStats: (...a: unknown[]) => getOperationStats(...a),
  getOperationTrace: (...a: unknown[]) => getOperationTrace(...a),
}))

import Link from 'next/link'
import SlowOperationsPage from '../page'

interface ElementLike {
  type: unknown
  props: Record<string, unknown>
}

function collect(node: unknown, predicate: (e: ElementLike) => boolean, out: ElementLike[] = []): ElementLike[] {
  if (node == null || typeof node !== 'object') return out
  if (Array.isArray(node)) {
    node.forEach((child) => collect(child, predicate, out))
    return out
  }
  const element = node as ElementLike
  if (predicate(element)) out.push(element)
  if (element.props && 'children' in element.props) collect(element.props.children, predicate, out)
  return out
}

function textOf(node: unknown): string {
  if (node == null || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  const element = node as ElementLike
  return element.props && 'children' in element.props ? textOf(element.props.children) : ''
}

const links = (root: unknown) => collect(root, (e) => e.type === Link)
const hrefs = (root: unknown) => links(root).map((l) => String(l.props.href))
const inputNamed = (root: unknown, name: string) => collect(root, (e) => e.type === 'input' && e.props.name === name)[0]

async function render(searchParams: Record<string, string> = {}) {
  return (await SlowOperationsPage({ searchParams: Promise.resolve(searchParams) })) as unknown as ElementLike
}

const route = { method: 'GET', path: '/api/projects/{id}/posts', count: 4, p50Ms: 20, p95Ms: 40, maxMs: 41 }
const operation = { operationId: 'op-slow-1', totalDurationMs: 900, callCount: 3, startedAt: '2026-10-01T01:02:03', userId: 7 }

describe('/operation-logs/slow(issue #1471)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } })
    getViewerTimeZone.mockResolvedValue('Asia/Tokyo')
    getRouteStats.mockResolvedValue([route])
    getOperationStats.mockResolvedValue([operation])
    getOperationTrace.mockResolvedValue([])
  })

  it('admin セッションを要求し、admin でなければ集計を取得しない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT'))

    await expect(render()).rejects.toThrow('NEXT_REDIRECT')
    expect(getRouteStats).not.toHaveBeenCalled()
    expect(getOperationStats).not.toHaveBeenCalled()
  })

  it('閲覧者TZの期間を UTC の ISO 日時へ換算して2つの API へ渡す', async () => {
    await render({ startDate: '2026-10-01T09:00', endDate: '2026-10-02T09:00' })

    const expected = expect.objectContaining({ startDate: '2026-10-01T00:00:00', endDate: '2026-10-02T00:00:59' })
    expect(getRouteStats).toHaveBeenCalledWith(expected)
    expect(getOperationStats).toHaveBeenCalledWith(expected)
  })

  it('期間が未指定なら直近24時間で集計する', async () => {
    await render()

    const arg = getRouteStats.mock.calls[0][0]
    expect(arg.startDate).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    expect(arg.endDate).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  })

  it('個人設定TZが無ければフォームが送ったブラウザTZで解釈する', async () => {
    getViewerTimeZone.mockResolvedValue(null)

    await render({ startDate: '2026-10-01T09:00', endDate: '2026-10-01T10:00', tz: 'Asia/Tokyo' })
    expect(getRouteStats).toHaveBeenCalledWith(expect.objectContaining({ startDate: '2026-10-01T00:00:00' }))

    await render({ startDate: '2026-10-01T09:00', endDate: '2026-10-01T10:00', tz: 'Not/AZone' })
    expect(getRouteStats).toHaveBeenLastCalledWith(expect.objectContaining({ startDate: '2026-10-01T09:00:00' }))
  })

  it('既定の並びは p95 降順・合計所要時間降順', async () => {
    await render()

    expect(getRouteStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'p95', direction: 'desc' }))
    expect(getOperationStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'totalDuration', direction: 'desc' }))
  })

  it('指定した並べ替えを API へ渡す', async () => {
    await render({ routeSort: 'count', routeDir: 'asc', opSort: 'callCount', opDir: 'asc' })

    expect(getRouteStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'count', direction: 'asc' }))
    expect(getOperationStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'callCount', direction: 'asc' }))
  })

  it('不正な並べ替えの指定は既定に戻す', async () => {
    await render({ routeSort: 'bogus', routeDir: 'sideways', opSort: 'x', opDir: 'y' })

    expect(getRouteStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'p95', direction: 'desc' }))
    expect(getOperationStats).toHaveBeenCalledWith(expect.objectContaining({ sort: 'totalDuration', direction: 'desc' }))
  })

  it('ルート別一覧に集約されたパスと件数・p50・p95・最大が出る', async () => {
    const text = textOf(await render())

    expect(text).toContain('GET')
    expect(text).toContain('/api/projects/{id}/posts')
    expect(text).toContain('40')
    expect(text).toContain('41')
  })

  it('列見出しは並べ替えのリンクで、いまの列は向きが反転し、別の列は降順になる', async () => {
    const all = hrefs(await render())

    const p95 = all.find((h) => h.includes('routeSort=p95'))!
    expect(new URLSearchParams(p95.split('?')[1]).get('routeDir')).toBe('asc')
    const count = all.find((h) => h.includes('routeSort=count'))!
    expect(new URLSearchParams(count.split('?')[1]).get('routeDir')).toBe('desc')
    for (const key of ['routeSort=p50', 'routeSort=max', 'opSort=totalDuration', 'opSort=callCount', 'opSort=startedAt']) {
      expect(all.some((h) => h.includes(key))).toBe(true)
    }
  })

  it('並べ替えリンクは期間と他方の一覧の並びを引き継ぐ', async () => {
    const all = hrefs(await render({ startDate: '2026-10-01T09:00', endDate: '2026-10-02T09:00', opSort: 'callCount' }))

    const p50 = all.find((h) => h.includes('routeSort=p50'))!
    const q = new URLSearchParams(p50.split('?')[1])
    expect(q.get('startDate')).toBe('2026-10-01T09:00')
    expect(q.get('endDate')).toBe('2026-10-02T09:00')
    expect(q.get('opSort')).toBe('callCount')
  })

  it('操作別一覧の各行からその操作のトレースへ辿るリンクがある', async () => {
    const all = hrefs(await render({ startDate: '2026-10-01T09:00' }))

    const traceLink = all.find((h) => h.includes('trace=op-slow-1'))!
    expect(traceLink).toBeDefined()
    expect(new URLSearchParams(traceLink.split('?')[1]).get('startDate')).toBe('2026-10-01T09:00')
  })

  it('trace を指定するとその操作の全呼び出しを表示する', async () => {
    getOperationTrace.mockResolvedValue([
      {
        id: 1, operationId: 'op-slow-1', userId: 7, actorKeycloakSub: null, method: 'GET', path: '/api/sites/5',
        statusCode: 200, durationMs: 321, success: true, errorMessage: null, createdAt: '2026-10-01T01:02:03',
      },
    ])

    const text = textOf(await render({ trace: 'op-slow-1' }))

    expect(getOperationTrace).toHaveBeenCalledWith('op-slow-1')
    expect(text).toContain('/api/sites/5')
    expect(text).toContain('321')
  })

  it('利用者ID・ステータスが無い行や失敗した呼び出しも描画する', async () => {
    getOperationStats.mockResolvedValue([{ ...operation, userId: null }])
    getOperationTrace.mockResolvedValue([
      {
        id: 2, operationId: 'op-slow-1', userId: null, actorKeycloakSub: null, method: 'POST', path: '/api/x',
        statusCode: null, durationMs: 5, success: false, errorMessage: 'timeout', createdAt: '2026-10-01T01:02:03',
      },
    ])

    const text = textOf(await render({ trace: 'op-slow-1' }))

    expect(text).toContain('(応答なし)')
    expect(text).toContain('失敗: timeout')
    expect(text).toContain('-')
  })

  it('個人設定TZが無くtzも未指定ならサーバー既定のTZで解釈する', async () => {
    getViewerTimeZone.mockResolvedValue(null)

    await expect(render({ startDate: '2026-10-01T09:00' })).resolves.toBeDefined()
    expect(getRouteStats).toHaveBeenCalledWith(expect.objectContaining({ startDate: '2026-10-01T09:00:00' }))
  })

  it('trace の取得に失敗しても画面は描画し、記録なしと伝える', async () => {
    getOperationTrace.mockRejectedValue(new Error('boom'))

    const text = textOf(await render({ trace: 'op-x' }))

    expect(text).toContain('記録が見つかりませんでした')
  })

  it('trace を指定しなければ取得しない', async () => {
    await render()

    expect(getOperationTrace).not.toHaveBeenCalled()
  })

  it('集計の取得に失敗したら、空の一覧とエラー表示で描画する', async () => {
    getRouteStats.mockRejectedValue(new Error('boom'))
    getOperationStats.mockRejectedValue(new Error('boom'))

    const text = textOf(await render())

    expect(text).toContain('集計を取得できませんでした')
  })

  it('一覧が空なら該当なしと表示する', async () => {
    getRouteStats.mockResolvedValue([])
    getOperationStats.mockResolvedValue([])

    expect(textOf(await render())).toContain('該当する記録はありません')
  })

  it('フォームに開始日時・終了日時の datetime-local 入力がある', async () => {
    const tree = await render({ startDate: '2026-10-01T09:00' })

    expect(inputNamed(tree, 'startDate')?.props.type).toBe('datetime-local')
    expect(inputNamed(tree, 'startDate')?.props.defaultValue).toBe('2026-10-01T09:00')
    expect(inputNamed(tree, 'endDate')?.props.type).toBe('datetime-local')
  })
})
