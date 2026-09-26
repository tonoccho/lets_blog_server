/**
 * @jest-environment node
 */
jest.mock('server-only', () => ({}))

const requireSession = jest.fn()
jest.mock('@/lib/session', () => ({
  requireSession: (...a: unknown[]) => requireSession(...a),
}))

const getOperationTrace = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  getOperationTrace: (...a: unknown[]) => getOperationTrace(...a),
}))

import { copyOperationTraceAction } from '../actions'

describe('copyOperationTraceAction(issue #1260)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    requireSession.mockResolvedValue({ user: { role: 'admin' } })
  })

  it('個人設定TZに関係なく、日時をUTCのZ付きISO-8601で出力する', async () => {
    getOperationTrace.mockResolvedValue([
      {
        id: 1,
        operationId: 'op-1',
        userId: 1,
        actorKeycloakSub: null,
        method: 'GET',
        path: '/api/sites',
        statusCode: 200,
        durationMs: 10,
        success: true,
        errorMessage: null,
        createdAt: '2026-09-11T09:10:35',
      },
    ])

    const text = await copyOperationTraceAction('op-1')

    expect(requireSession).toHaveBeenCalled()
    expect(text).toContain('開始日時: 2026-09-11T09:10:35Z')
    expect(text).toContain('1. [2026-09-11T09:10:35Z] GET /api/sites')
  })

  it('記録が見つからなければその旨のテキストを返す', async () => {
    getOperationTrace.mockResolvedValue([])

    const text = await copyOperationTraceAction('op-x')

    expect(text).toBe('操作ID: op-x\n(記録が見つかりませんでした)')
  })
})
