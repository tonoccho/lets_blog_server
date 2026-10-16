/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))

import { copyOperationTraceAction } from '../actions'
import { installFetch, jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'

/** operation-log-service の代役(gateway の向こう側)。 */
const getOperationTrace = jest.fn()

describe('copyOperationTraceAction(issue #1260)', () => {
  beforeEach(() => {
    getOperationTrace.mockReset()
    resetServerRuntime(ADMIN_SESSION)
    installFetch({
      'GET /api/operation-logs/:id': async (_url, _init, [id]) => jsonResponse(await getOperationTrace(decodeURIComponent(id))),
    })
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

    expect(getOperationTrace).toHaveBeenCalledWith('op-1')
    expect(text).toContain('開始日時: 2026-09-11T09:10:35Z')
    expect(text).toContain('1. [2026-09-11T09:10:35Z] GET /api/sites')
  })

  it('記録が見つからなければその旨のテキストを返す', async () => {
    getOperationTrace.mockResolvedValue([])

    const text = await copyOperationTraceAction('op-x')

    expect(text).toBe('操作ID: op-x\n(記録が見つかりませんでした)')
  })

  it('未ログインならログイン画面へ送り、トレースを取りに行かない', async () => {
    resetServerRuntime(null)

    await expect(copyOperationTraceAction('op-1')).rejects.toThrow('NEXT_REDIRECT:/login')

    expect(getOperationTrace).not.toHaveBeenCalled()
  })
})
