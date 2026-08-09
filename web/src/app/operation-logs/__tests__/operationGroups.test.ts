import type { OperationLogEntry } from '@/lib/apiClient'
import { groupOperationLogEntries, describeOperationTraceText } from '../operationGroups'

function entry(overrides: Partial<OperationLogEntry>): OperationLogEntry {
  return {
    id: 1,
    operationId: 'op-1',
    userId: 1,
    method: 'GET',
    path: '/api/sites',
    statusCode: 200,
    durationMs: 10,
    success: true,
    errorMessage: null,
    createdAt: '2026-08-09T00:00:00',
    ...overrides,
  }
}

describe('groupOperationLogEntries', () => {
  it('operationIdごとにまとめ、グループ内は古い順に並べる', () => {
    const entries = [
      entry({ id: 2, operationId: 'op-1', createdAt: '2026-08-09T00:00:02' }),
      entry({ id: 1, operationId: 'op-1', createdAt: '2026-08-09T00:00:01' }),
      entry({ id: 3, operationId: 'op-2', createdAt: '2026-08-09T00:00:05' }),
    ]

    const groups = groupOperationLogEntries(entries)

    expect(groups).toHaveLength(2)
    const op1 = groups.find((g) => g.operationId === 'op-1')!
    expect(op1.entries.map((e) => e.id)).toEqual([1, 2])
  })

  it('グループ同士は開始時刻が新しい順に並べる', () => {
    const entries = [
      entry({ id: 1, operationId: 'op-old', createdAt: '2026-08-09T00:00:00' }),
      entry({ id: 2, operationId: 'op-new', createdAt: '2026-08-09T00:10:00' }),
    ]

    const groups = groupOperationLogEntries(entries)

    expect(groups.map((g) => g.operationId)).toEqual(['op-new', 'op-old'])
  })

  it('全ての呼び出しが成功していればsuccess=trueになる', () => {
    const entries = [
      entry({ id: 1, operationId: 'op-1', success: true }),
      entry({ id: 2, operationId: 'op-1', success: false }),
    ]

    const groups = groupOperationLogEntries(entries)

    expect(groups[0].success).toBe(false)
  })
})

describe('describeOperationTraceText', () => {
  it('操作ID・呼び出し一覧・エラー内容を含むテキストを生成する', () => {
    const entries = [
      entry({ id: 1, operationId: 'op-1', method: 'GET', path: '/api/sites', success: true }),
      entry({
        id: 2,
        operationId: 'op-1',
        method: 'POST',
        path: '/api/sites',
        statusCode: 500,
        success: false,
        errorMessage: 'APIエラー (500): internal error',
      }),
    ]
    const [group] = groupOperationLogEntries(entries)

    const text = describeOperationTraceText(group, 'Asia/Tokyo')

    expect(text).toContain('操作ID: op-1')
    expect(text).toContain('呼び出し件数: 2')
    expect(text).toContain('結果: エラーあり')
    expect(text).toContain('GET /api/sites')
    expect(text).toContain('POST /api/sites')
    expect(text).toContain('エラー: APIエラー (500): internal error')
  })
})
