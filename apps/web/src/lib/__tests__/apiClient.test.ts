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
  startProjectImageJob,
  getSiteAdminPath,
  listGeneratedImages,
  getSetupStatus,
  downloadGeneratedImageFile,
  deleteGeneratedImage,
  streamConnectedServiceStatuses,
  listReviewStepSettings,
  listArticleReviewPullRequests,
  listProjectAdSenseAccounts,
  listUnifiedOperationLogs,
  selectProjectAdSenseAccount,
  updateReviewStepSetting,
  listAiConnections,
  getProjectConnections,
  updateProjectConnections,
  setProjectOpenAiApiKey,
  clearProjectOpenAiApiKey,
  setProjectClaudeApiKey,
  clearProjectClaudeApiKey,
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

  /**
   * issue #1053: 更新猶予を入れてもなお401が返るのは、ssoSessionIdleTimeout超過など
   * 正当に再ログインが必要な場合に限られる。生の `APIエラー (401): Unauthorized` は
   * 利用者に何をすればよいか伝えないため、再ログインを促す文言に差し替える。
   */
  it('401は生の文言ではなく再ログインを促すメッセージにする', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/operation-logs')
        ? Promise.resolve(jsonResponse({}))
        : Promise.resolve(textResponse('Unauthorized', 401, 'Unauthorized'))
    )

    await expect(getGeneratedImage(1)).rejects.toThrow('再度ログインしてください')
    await expect(getGeneratedImage(1)).rejects.not.toThrow(/APIエラー \(401\)/)
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

/**
 * issue #1212: レビューステップ別(#1210)のLLMプロバイダー/モデル設定(#1211のAPI)の
 * 一覧取得・更新。既存の listLlmProvider/selectLlmProvider と同じ薄いラッパーだが、
 * 変更したファイルの分岐カバレッジ(CLAUDE.md → Coverage)を満たすためここで固定する。
 */
describe('レビューステップ別のLLM設定(issue #1212)', () => {
  it('一覧を取得する', async () => {
    const body = {
      steps: [{ stepKey: 'JAPANESE', provider: null, model: null }],
      availableProviders: ['OPENAI'],
      availableModels: ['gpt-4o-mini'],
    }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await listReviewStepSettings(7)

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/ai-models/llm/review-steps')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('providerとmodelを指定してステップの設定を更新する', async () => {
    const body = {
      steps: [{ stepKey: 'JAPANESE', provider: 'OPENAI', model: 'gpt-4o-mini' }],
      availableProviders: ['OPENAI'],
      availableModels: ['gpt-4o-mini'],
    }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await updateReviewStepSetting(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini')

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/ai-models/llm/review-steps/JAPANESE')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ provider: 'OPENAI', model: 'gpt-4o-mini' })
  })

  it('未選択(空文字)はprovider/modelともにnullとして送る(上書き解除)', async () => {
    const body = {
      steps: [{ stepKey: 'JAPANESE', provider: null, model: null }],
      availableProviders: ['OPENAI'],
      availableModels: ['gpt-4o-mini'],
    }
    fetchMock.mockResolvedValue(jsonResponse(body))

    await updateReviewStepSetting(7, 'JAPANESE', '', '')

    const [, init] = calls()[0]
    expect(JSON.parse(String(init.body))).toEqual({ provider: null, model: null })
  })
})

describe('listUnifiedOperationLogs の日時範囲(issue #1138)', () => {
  const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 }

  it('startDate / endDate をクエリへそのまま載せる', async () => {
    fetchMock.mockResolvedValue(jsonResponse(emptyPage))

    await listUnifiedOperationLogs({
      type: 'OPERATION',
      q: 'sites',
      startDate: '2026-09-10T00:30:00',
      endDate: '2026-09-11T00:30:59',
      page: 2,
    })

    const query = new URL(calls()[0][0], 'http://localhost').searchParams
    expect(query.get('startDate')).toBe('2026-09-10T00:30:00')
    expect(query.get('endDate')).toBe('2026-09-11T00:30:59')
    expect(query.get('type')).toBe('OPERATION')
    expect(query.get('q')).toBe('sites')
    expect(query.get('page')).toBe('2')
  })

  it('未指定ならクエリに startDate / endDate を載せない(従来どおり)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(emptyPage))

    await listUnifiedOperationLogs({})

    const query = new URL(calls()[0][0], 'http://localhost').searchParams
    expect(query.has('startDate')).toBe(false)
    expect(query.has('endDate')).toBe(false)
    expect(query.get('page')).toBe('0')
    expect(query.get('size')).toBe('50')
  })
})

describe('AdSenseのパブリッシャーID自動発見(issue #1232)', () => {
  it('連携したGoogleアカウントが利用できるAdSenseアカウントの一覧を取得する', async () => {
    const body = [{ accountId: 'pub-1', displayName: 'A' }]
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await listProjectAdSenseAccounts(7)

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/adsense/accounts')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('選んだパブリッシャーIDをPUTで保存する', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 204))

    await selectProjectAdSenseAccount(7, 'pub-2')

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/adsense/account')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ accountId: 'pub-2' })
  })
})

describe('listArticleReviewPullRequests(issue #1340)', () => {
  it('プロジェクトのレビュー待ちPR一覧を GET で取得する', async () => {
    const body = [{ number: 201, title: 't', headBranch: 'article/x', createdAt: '2026-09-30T03:00:00Z', url: 'https://github.com/a/b/pull/201' }]
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await listArticleReviewPullRequests(7)

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/article-review/pull-requests')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('失敗は空配列にせず例外として伝える', async () => {
    fetchMock.mockResolvedValue(textResponse('bad gateway', 502, 'Bad Gateway'))

    await expect(listArticleReviewPullRequests(7)).rejects.toThrow('APIエラー (502)')
  })
})

describe('AI接続情報・接続先の上書き(issue #1504)', () => {
  it('listAiConnectionsは #1499 のエンドポイントを取得する', async () => {
    const body = [{ provider: 'OLLAMA', displayName: 'Ollama', targetUrl: 'http://o/models', source: 'PROJECT', status: 'NORMAL', detail: null, configured: true }]
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await listAiConnections(7)

    expect(result).toEqual(body)
    expect(calls()[0][0]).toContain('/api/projects/7/ai-connections')
  })

  it('getProjectConnectionsは #1503 のエンドポイントを取得する', async () => {
    const body = { ollama: { overrideBaseUrl: null, baseUrl: 'http://o', source: 'ENVIRONMENT' } }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await getProjectConnections(7)

    expect(result).toEqual(body)
    expect(calls()[0][0]).toContain('/api/projects/7/ai-models/connections')
  })

  it('updateProjectConnectionsは指定した項目だけをPUTし、空文字はそのまま送る(上書き解除)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}))

    await updateProjectConnections(7, { comfyuiBaseUrl: '' })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/ai-models/connections')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ comfyuiBaseUrl: '' })
  })
})

describe('ChatGPT(OpenAI)のプロジェクト単位APIキー(issue #1506)', () => {
  it('setProjectOpenAiApiKeyはキーをJSONでPUTする', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await setProjectOpenAiApiKey(7, 'sk-x')

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/openai-api-key')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ apiKey: 'sk-x' })
  })

  it('clearProjectOpenAiApiKeyはDELETEする', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await clearProjectOpenAiApiKey(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/openai-api-key')
    expect(init.method).toBe('DELETE')
  })
})

describe('Claude(Anthropic)のプロジェクト単位APIキー(issue #1507)', () => {
  it('setProjectClaudeApiKeyはキーをJSONでPUTする', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await setProjectClaudeApiKey(7, 'sk-ant-x')

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/claude-api-key')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ apiKey: 'sk-ant-x' })
  })

  it('clearProjectClaudeApiKeyはDELETEする', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await clearProjectClaudeApiKey(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/api-keys/claude-api-key')
    expect(init.method).toBe('DELETE')
  })
})

describe('listGeneratedImages のクエリ(issue #1472)', () => {
  it('引数なしは従来どおり素のパス(全件)を取得する', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages()

    expect(calls()[0][0]).toMatch(/\/api\/generated-images$/)
  })

  it('projectId だけなら従来どおり projectId のみ', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(7)

    expect(calls()[0][0]).toMatch(/\/api\/generated-images\?projectId=7$/)
  })

  it('limit・offset・tag を渡すとクエリに載せる(tag は URL エンコードする)', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(undefined, { limit: 24, offset: 48, tag: '猫 & 犬' })

    const url = new URL(calls()[0][0])
    expect(url.searchParams.get('limit')).toBe('24')
    expect(url.searchParams.get('offset')).toBe('48')
    expect(url.searchParams.get('tag')).toBe('猫 & 犬')
    expect(url.searchParams.has('projectId')).toBe(false)
  })

  it('offset=0 も省略せず送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(3, { limit: 24, offset: 0 })

    const url = new URL(calls()[0][0])
    expect(url.searchParams.get('projectId')).toBe('3')
    expect(url.searchParams.get('offset')).toBe('0')
    expect(url.searchParams.has('tag')).toBe(false)
  })
})

describe('getSiteAdminPath', () => {
  it('GET /api/system-settings/site-admin-path を呼び、path を返す', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ path: 'wp-admin' }))

    const res = await getSiteAdminPath()

    expect(res).toEqual({ path: 'wp-admin' })
    expect(calls()[0][0]).toContain('/api/system-settings/site-admin-path')
  })
})

describe('startProjectImageJob (issue #1408)', () => {
  it('POST /api/ai/image/jobs へ要求を JSON で送り、受理されたジョブを返す', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 12, type: 'image_generation', status: 'running' }, 202))

    const job = await startProjectImageJob({ prompt: 'cat', projectId: 7, batchSize: 2 })

    expect(job).toEqual({ id: 12, type: 'image_generation', status: 'running' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/ai/image/jobs')
    expect(url).not.toMatch(/\/api\/ai\/image(\?|$)/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ prompt: 'cat', projectId: 7, batchSize: 2 })
  })
})
