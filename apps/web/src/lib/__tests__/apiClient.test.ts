/**
 * バックエンド(gateway)への全リクエストが通る共通経路 `apiRequest` / `apiFetch` の検証。
 *
 * この経路は issue #584 で1箇所に集約された唯一の出口で、Authorization ヘッダの付与・
 * 操作ログの記録(issue #143)・エラー整形をすべて担うが、単体テストが無かった。
 * issue #1101 で `GeneratedImageDetail` に `batchIndex` を足すにあたり、
 * 変更ファイルの分岐カバレッジを満たすためここで固定する。
 */
jest.mock('server-only', () => ({}))

const afterMock = jest.fn((fn: () => unknown) => fn())
jest.mock('next/server', () => ({ after: (fn: () => unknown) => afterMock(fn) }))

const cookiesMock = jest.fn()
const headersMock = jest.fn()
jest.mock('next/headers', () => ({
  cookies: () => cookiesMock(),
  headers: () => headersMock(),
}))

const getTokenMock = jest.fn()
jest.mock('next-auth/jwt', () => ({ getToken: (...args: unknown[]) => getTokenMock(...args) }))

import {
  getGeneratedImage,
  getSetupStatus,
  downloadGeneratedImageFile,
  deleteGeneratedImage,
  streamConnectedServiceStatuses,
} from '@/lib/apiClient'

type FetchCall = [string, RequestInit & { headers?: Record<string, string> }]

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    text: async () => JSON.stringify(body),
    arrayBuffer: async () => new ArrayBuffer(4),
    headers: { get: () => 'application/json' },
  } as unknown as Response
}

function textResponse(text: string, status: number, statusText = 'Error'): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText,
    text: async () => text,
    headers: { get: () => null },
  } as unknown as Response
}

let fetchMock: jest.Mock

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

function calls(): FetchCall[] {
  return fetchMock.mock.calls as FetchCall[]
}

describe('apiFetch の成功経路', () => {
  it('Authorizationヘッダを付けて gateway を呼び、JSONを返す', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1, seed: 42, batchIndex: 0 }))

    const detail = await getGeneratedImage(1)

    expect(detail).toEqual({ id: 1, seed: 42, batchIndex: 0 })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/generated-images/1')
    expect(init.headers?.Authorization).toBe('Bearer access-token')
  })

  it('操作ログを1件記録する', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1 }))

    await getGeneratedImage(1)

    expect(afterMock).toHaveBeenCalledTimes(1)
    const logCall = calls().find(([url]) => url.includes('/api/operation-logs'))
    expect(logCall).toBeDefined()
    const body = JSON.parse(String(logCall?.[1].body))
    expect(body).toMatchObject({ operationId: 'op-123', method: 'GET', statusCode: 200, success: true })
  })

  it('x-operation-id が無ければ新しいIDを発番する', async () => {
    headersMock.mockResolvedValue({ get: () => null })
    fetchMock.mockResolvedValue(jsonResponse({ id: 1 }))

    await getGeneratedImage(1)

    const logCall = calls().find(([url]) => url.includes('/api/operation-logs'))
    expect(JSON.parse(String(logCall?.[1].body)).operationId).toMatch(/[0-9a-f-]{36}/)
  })

  it('204応答は undefined を返す', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      text: async () => '',
      headers: { get: () => null },
    } as unknown as Response)

    await expect(deleteGeneratedImage(1)).resolves.toBeUndefined()
    const [, init] = calls()[0]
    expect(init.method).toBe('DELETE')
  })

  it('空ボディの200応答も undefined を返す', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      text: async () => '',
      headers: { get: () => null },
    } as unknown as Response)

    await expect(getGeneratedImage(1)).resolves.toBeUndefined()
  })

  it('Content-Typeが無いバイナリ応答はPNGとして扱う', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      arrayBuffer: async () => new ArrayBuffer(2),
      text: async () => '',
      headers: { get: () => null },
    } as unknown as Response)

    await expect(downloadGeneratedImageFile(1)).resolves.toMatchObject({ mimeType: 'image/png' })
  })

  it('バイナリ取得はContent-Typeをそのまま返す', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      arrayBuffer: async () => new ArrayBuffer(8),
      text: async () => '',
      headers: { get: () => 'image/png' },
    } as unknown as Response)

    const result = await downloadGeneratedImageFile(1)

    expect(result.mimeType).toBe('image/png')
    expect(result.body.byteLength).toBe(8)
  })
})

describe('認証を要しない公開エンドポイント', () => {
  it('Authorizationヘッダを付けず、操作ログも記録しない', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ initialized: true }))

    await getSetupStatus()

    const [, init] = calls()[0]
    expect(init.headers?.Authorization).toBeUndefined()
    expect(afterMock).not.toHaveBeenCalled()
  })
})

describe('apiRequest の失敗経路', () => {
  it('ログイン中のアクセストークンが無ければ再ログインを促す', async () => {
    getTokenMock.mockResolvedValue(null)

    await expect(getGeneratedImage(1)).rejects.toThrow('再度ログインしてください')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('HTTPエラーはボディ込みのメッセージで例外にする', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.resolve(jsonResponse({}))
        : Promise.resolve(textResponse('not found', 404, 'Not Found'))
    )

    await expect(getGeneratedImage(1)).rejects.toThrow('APIエラー (404): not found')
  })

  it('エラーボディが空ならstatusTextを使う', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.resolve(jsonResponse({}))
        : Promise.resolve(textResponse('', 500, 'Internal Server Error'))
    )

    await expect(getGeneratedImage(1)).rejects.toThrow('APIエラー (500): Internal Server Error')
  })

  it('エラーボディの読み取りに失敗してもstatusTextで例外にする', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.resolve(jsonResponse({}))
        : Promise.resolve({
            ok: false,
            status: 502,
            statusText: 'Bad Gateway',
            text: async () => {
              throw new Error('stream closed')
            },
            headers: { get: () => null },
          } as unknown as Response)
    )

    await expect(getGeneratedImage(1)).rejects.toThrow('APIエラー (502): Bad Gateway')
  })

  it('通信そのものが失敗したら失敗として記録し例外を投げ直す', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.resolve(jsonResponse({}))
        : Promise.reject(new Error('ECONNREFUSED'))
    )

    await expect(getGeneratedImage(1)).rejects.toThrow('ECONNREFUSED')
    const logCall = calls().find(([url]) => url.includes('/api/operation-logs'))
    expect(JSON.parse(String(logCall?.[1].body))).toMatchObject({
      statusCode: null,
      success: false,
      errorMessage: 'ECONNREFUSED',
    })
  })

  it('Error以外がthrowされてもメッセージへ変換して記録する', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs') ? Promise.resolve(jsonResponse({})) : Promise.reject('文字列で落ちた')
    )

    await expect(getGeneratedImage(1)).rejects.toBe('文字列で落ちた')
    const logCall = calls().find(([url]) => url.includes('/api/operation-logs'))
    expect(JSON.parse(String(logCall?.[1].body)).errorMessage).toBe('文字列で落ちた')
  })

  it('操作ログの記録失敗は本来の呼び出しに影響しない', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.reject(new Error('log service down'))
        : Promise.resolve(jsonResponse({ id: 1 }))
    )

    await expect(getGeneratedImage(1)).resolves.toEqual({ id: 1 })
  })
})

/**
 * SSE中継(issue #198 / #280)は、上流のステータスをそのままブラウザへ返すため
 * `throwOnError: false` で呼ぶ。エラー応答でも例外にせず Response を返し、
 * 本文は消費しない(呼び出し元が中継するため)。
 */
describe('throwOnError: false の中継経路', () => {
  it('エラー応答でも例外にせずResponseをそのまま返す', async () => {
    const upstream = textResponse('should not be consumed', 503, 'Service Unavailable')
    const textSpy = jest.spyOn(upstream, 'text')
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs') ? Promise.resolve(jsonResponse({})) : Promise.resolve(upstream)
    )

    const res = await streamConnectedServiceStatuses()

    expect(res).toBe(upstream)
    expect(textSpy).not.toHaveBeenCalled()
    const logCall = calls().find(([url]) => url.includes('/api/operation-logs'))
    expect(JSON.parse(String(logCall?.[1].body))).toMatchObject({
      statusCode: 503,
      success: false,
      errorMessage: 'APIエラー (503): Service Unavailable',
    })
  })

  it('成功応答はそのまま返す', async () => {
    const upstream = jsonResponse({ ok: true })
    fetchMock.mockResolvedValue(upstream)

    await expect(streamConnectedServiceStatuses()).resolves.toBe(upstream)
  })
})
