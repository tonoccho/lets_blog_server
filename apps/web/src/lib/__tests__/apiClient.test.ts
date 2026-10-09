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
  startCustomTagGenerationJob,
  startStaticContentGenerationJob,
  startManagedWordPressSiteJob,
  startProjectEnvironmentSyncJob,
  startTagDesignGenerationJob,
  saveStaticContent,
  getSiteAdminPath,
  listGeneratedImages,
  listGeneratedImageFolders,
  createGeneratedImageFolder,
  setGeneratedImageFolder,
  renameGeneratedImageFolder,
  getGeneratedImageFolderDeleteImpact,
  deleteGeneratedImageFolder,
  getSetupStatus,
  downloadGeneratedImageFile,
  deleteGeneratedImage,
  bulkDeleteGeneratedImages,
  streamConnectedServiceStatuses,
  listReviewStepSettings,
  listArticleReviewPullRequests,
  startArticleReview,
  approveArticleReview,
  rejectArticleReview,
  listProjectAdSenseAccounts,
  listUnifiedOperationLogs,
  getRouteStats,
  getOperationStats,
  selectProjectAdSenseAccount,
  updateReviewStepSetting,
  listAiConnections,
  getProjectConnections,
  updateProjectConnections,
  pullOllamaModel,
  setProjectOpenAiApiKey,
  clearProjectOpenAiApiKey,
  setProjectClaudeApiKey,
  clearProjectClaudeApiKey,
  downloadPenpotPlugin,
  downloadMcpServer,
  getProjectXConnection,
  startProjectXAuthorization,
  completeProjectXAuthorization,
  testProjectXPost,
  getProjectThreadsConnection,
  startProjectThreadsAuthorization,
  completeProjectThreadsAuthorization,
  testProjectThreadsPost,
  disconnectProjectThreads,
  getProjectLinkedInConnection,
  startProjectLinkedInAuthorization,
  completeProjectLinkedInAuthorization,
  testProjectLinkedInPost,
  disconnectProjectLinkedIn,
  getProjectHatenaConnection,
  startProjectHatenaAuthorization,
  completeProjectHatenaAuthorization,
  testProjectHatenaPost,
  disconnectProjectHatena,
  getProjectFacebookConnection,
  startProjectFacebookAuthorization,
  completeProjectFacebookAuthorization,
  getProjectFacebookPages,
  selectProjectFacebookPage,
  testProjectFacebookPost,
  disconnectProjectFacebook,
  getProjectPvRules,
  addProjectPvRule,
  deleteProjectPvRule,
  resendProjectPvRules,
  getProjectSnsTemplates,
  saveProjectSnsTemplates,
  resendProjectSnsTemplates,
  getLetsblogSync,
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

  it('gateway へ操作IDを X-Correlation-Id として送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1 }))

    await getGeneratedImage(1)

    const [, init] = calls()[0]
    expect(init.headers?.['X-Correlation-Id']).toBe('op-123')
  })

  it('未認証の呼び出しでも操作IDがあれば X-Correlation-Id を送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ initialized: true }))

    await getSetupStatus()

    const [, init] = calls()[0]
    expect(init.headers?.Authorization).toBeUndefined()
    expect(init.headers?.['X-Correlation-Id']).toBe('op-123')
  })

  it('未認証で操作IDも無ければ X-Correlation-Id を送らない', async () => {
    headersMock.mockResolvedValue({ get: () => null })
    fetchMock.mockResolvedValue(jsonResponse({ initialized: true }))

    await getSetupStatus()

    const [, init] = calls()[0]
    expect(init.headers?.['X-Correlation-Id']).toBeUndefined()
  })

  it('未認証でリクエストの外(headers() が例外)でも X-Correlation-Id を送らずに呼び出せる', async () => {
    headersMock.mockRejectedValue(new Error('outside request scope'))
    fetchMock.mockResolvedValue(jsonResponse({ initialized: true }))

    await getSetupStatus()

    const [, init] = calls()[0]
    expect(init.headers?.['X-Correlation-Id']).toBeUndefined()
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

  it('bulkDeleteGeneratedImages は imageIds を POST し、結果を返す (issue #1492)', async () => {
    const result = { deletedCount: 2, failedCount: 0, deletedIds: [1, 2], failures: {} }
    fetchMock.mockResolvedValue(jsonResponse(result))

    await expect(bulkDeleteGeneratedImages([1, 2])).resolves.toEqual(result)
    const [url, init] = calls()[0]
    expect(String(url)).toContain('/api/generated-images/bulk-delete')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ imageIds: [1, 2] })
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
      availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] },
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
      availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] },
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
      availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] },
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

describe('startArticleReview(issue #1345)', () => {
  it('PRのレビューAPIを POST で呼び、応答の testPostUrl を返す', async () => {
    const body = { prNumber: 201, state: 'IN_REVIEW', testPostUrl: 'https://test.example/review-sample/', reviewedByUserId: 3, wpPostId: '9' }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await startArticleReview(7, 201)

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/article-review/pull-requests/201/review')
    expect(init.method).toBe('POST')
  })

  it('サーバが返した失敗の理由(409)を含む例外として伝える', async () => {
    fetchMock.mockResolvedValue(textResponse('テスト環境のサイトが紐づいていません', 409, 'Conflict'))

    await expect(startArticleReview(7, 201)).rejects.toThrow('APIエラー (409): テスト環境のサイトが紐づいていません')
  })
})

describe('approveArticleReview(issue #1346)', () => {
  it('PRのレビュー完了APIを POST で呼び、応答の productionPostUrl と branchDeleted を返す', async () => {
    const body = { prNumber: 201, state: 'PUBLISHED', productionPostUrl: 'https://prod.example/a/', wpPostId: '9', merged: true, branchDeleted: false }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await approveArticleReview(7, 201)

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/article-review/pull-requests/201/approve')
    expect(init.method).toBe('POST')
  })

  it('サーバが返した失敗の理由(409)を含む例外として伝える', async () => {
    fetchMock.mockResolvedValue(textResponse('レビュー中ではありません', 409, 'Conflict'))

    await expect(approveArticleReview(7, 201)).rejects.toThrow('APIエラー (409): レビュー中ではありません')
  })
})

describe('rejectArticleReview(issue #1346)', () => {
  it('PRの差し戻しAPIを POST し、指摘事項を comment として JSON で送る', async () => {
    const body = { prNumber: 201, state: 'CHANGES_REQUESTED', commentId: 5, rejectedByUserId: 3, rejectedAt: '2026-10-08T00:00:00Z' }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await rejectArticleReview(7, 201, '見出しを直してください')

    expect(result).toEqual(body)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/article-review/pull-requests/201/reject')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ comment: '見出しを直してください' })
  })

  it('サーバが返した失敗の理由(400)を含む例外として伝える', async () => {
    fetchMock.mockResolvedValue(textResponse('指摘事項は必須です', 400, 'Bad Request'))

    await expect(rejectArticleReview(7, 201, ' ')).rejects.toThrow('APIエラー (400): 指摘事項は必須です')
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
    const body = { ollama: { overrideBaseUrl: null, baseUrl: 'http://o', source: 'DATABASE' } }
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

  it('pullOllamaModelはモデル名をJSONでPOSTし、ジョブIDと実行中かどうかを返す(issue #1675)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ jobId: 31, alreadyRunning: false }))

    const result = await pullOllamaModel(7, 'qwen2.5:7b-instruct')

    expect(result).toEqual({ jobId: 31, alreadyRunning: false })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/ai-models/ollama/pull')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ model: 'qwen2.5:7b-instruct' })
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

describe('LLM生成の非同期ジョブAPI(issue #1409)', () => {
  it('startCustomTagGenerationJob は POST /api/custom-tags/generate/jobs へ送り、同期の /generate は叩かない', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 21, type: 'custom_tag_generation', status: 'running' }, 202))

    const job = await startCustomTagGenerationJob({ prompt: 'p', tagName: 't', description: 'd', projectId: 7 })

    expect(job).toEqual({ id: 21, type: 'custom_tag_generation', status: 'running' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/custom-tags/generate/jobs')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ prompt: 'p', tagName: 't', description: 'd', projectId: 7 })
  })

  it('startStaticContentGenerationJob は POST /api/sites/{id}/static-content/generate/jobs へ種別を送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 22, type: 'static_content_generation', status: 'running' }, 202))

    const job = await startStaticContentGenerationJob(3, 'PRIVACY_POLICY')

    expect(job.id).toBe(22)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/sites/3/static-content/generate/jobs')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ contentType: 'PRIVACY_POLICY' })
  })

  it('startManagedWordPressSiteJob は POST /api/sites/managed-wordpress/jobs へ入力を送り、同期APIは呼ばない (#1696)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 31, type: 'site_provisioning', status: 'running' }, 202))
    const input = {
      name: 'n', siteKey: 'k', title: 't', adminUser: 'u', adminEmail: 'a@b.c', adminPassword: 'p', locale: 'ja',
    }

    const job = await startManagedWordPressSiteJob(input)

    expect(job.id).toBe(31)
    const [url, init] = calls()[0]
    expect(url).toMatch(/\/api\/sites\/managed-wordpress\/jobs$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(input)
  })

  it('startProjectEnvironmentSyncJob は POST /api/projects/{id}/environments/sync/jobs へ入力を送り、同期APIは呼ばない (#1697)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 41, type: 'environment_sync', status: 'running' }, 202))
    const input = { from: 'test' as const, to: 'local' as const, targets: ['db' as const, 'media' as const] }

    const job = await startProjectEnvironmentSyncJob(7, input)

    expect(job.id).toBe(41)
    const [url, init] = calls()[0]
    expect(url).toMatch(/\/api\/projects\/7\/environments\/sync\/jobs$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(input)
  })

  it('startTagDesignGenerationJob はプロジェクト個別とグローバルで別のパスへ送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 23, type: 'tag_design_generation', status: 'running' }, 202))

    await startTagDesignGenerationJob(7, 'TOC', '淡いグレー')
    await startTagDesignGenerationJob(null, 'BLOGCARD', 'p')

    // 操作ログの記録(#143)も同じ fetch を通るので、ジョブの要求だけを取り出す。
    const [projectCall, globalCall] = calls().filter(([url]) => url.includes('/generate/jobs'))
    expect(projectCall[0]).toContain('/api/projects/7/tag-design-settings/TOC/generate/jobs')
    expect(JSON.parse(String(projectCall[1].body))).toEqual({ prompt: '淡いグレー' })
    expect(globalCall[0]).toContain('/api/tag-design-settings/BLOGCARD/generate/jobs')
    expect(globalCall[0]).not.toContain('/api/projects/')
  })

  it('saveStaticContent は PUT /api/sites/{id}/static-content/{種別} へ本文を送り、保存された静的コンテンツを返す', async () => {
    const saved = { id: 1, siteId: 3, contentType: 'OPERATOR_INFO', body: '本文', createdAt: 'a', updatedAt: 'b' }
    fetchMock.mockResolvedValue(jsonResponse(saved))

    const result = await saveStaticContent(3, 'OPERATOR_INFO', '本文')

    expect(result).toEqual(saved)
    const [url, init] = calls()[0]
    expect(url).toContain('/api/sites/3/static-content/OPERATOR_INFO')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ body: '本文' })
  })
})

describe('操作ログ集計API(issue #1471)', () => {
  it('getRouteStats は期間・並べ替え・上限をクエリに載せる', async () => {
    fetchMock.mockResolvedValue(jsonResponse([{ method: 'GET', path: '/a', count: 1, p50Ms: 1, p95Ms: 1, maxMs: 1 }]))

    const result = await getRouteStats({
      startDate: '2026-10-01T00:00:00',
      endDate: '2026-10-02T00:00:00',
      sort: 'count',
      direction: 'asc',
      limit: 50,
    })

    expect(result).toHaveLength(1)
    const [url] = calls()[0]
    expect(url).toContain('/api/operation-logs/stats/routes?')
    const query = new URL(url, 'http://localhost').searchParams
    expect(query.get('startDate')).toBe('2026-10-01T00:00:00')
    expect(query.get('endDate')).toBe('2026-10-02T00:00:00')
    expect(query.get('sort')).toBe('count')
    expect(query.get('direction')).toBe('asc')
    expect(query.get('limit')).toBe('50')
  })

  it('getOperationStats は並べ替えと上限が未指定ならクエリに載せない', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await getOperationStats({ startDate: '2026-10-01T00:00:00', endDate: '2026-10-02T00:00:00' })

    const [url] = calls()[0]
    expect(url).toContain('/api/operation-logs/stats/operations?')
    const query = new URL(url, 'http://localhost').searchParams
    expect(query.has('sort')).toBe(false)
    expect(query.has('direction')).toBe(false)
    expect(query.has('limit')).toBe(false)
  })
})

describe('配布物(Zip)のダウンロード(issue #1491)', () => {
  function zipResponse(disposition: string | null): Response {
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      arrayBuffer: async () => new ArrayBuffer(6),
      text: async () => '',
      headers: { get: (name: string) => (name === 'content-disposition' ? disposition : null) },
    } as unknown as Response
  }

  it('Penpotプラグインは /api/system/penpot-plugin を呼び、Content-Dispositionのファイル名を返す', async () => {
    fetchMock.mockResolvedValue(zipResponse('attachment; filename="p.zip"'))

    const result = await downloadPenpotPlugin()

    expect(calls()[0][0]).toContain('/api/system/penpot-plugin')
    expect(result.filename).toBe('p.zip')
    expect(result.body.byteLength).toBe(6)
  })

  it('MCPサーバーは /api/system/mcp-server を呼び、Content-Dispositionが無ければ既定のファイル名', async () => {
    fetchMock.mockResolvedValue(zipResponse(null))

    const result = await downloadMcpServer()

    expect(calls()[0][0]).toContain('/api/system/mcp-server')
    expect(result.filename).toBe('letsblog-mcp-server.zip')
  })

  it('Penpotプラグインも既定のファイル名を持つ', async () => {
    fetchMock.mockResolvedValue(zipResponse(null))
    expect((await downloadPenpotPlugin()).filename).toBe('letsblog-penpot-plugin.zip')
  })
})

describe('生成画像フォルダ(issue #1493)', () => {
  it('listGeneratedImages は folderId を載せる', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(undefined, { folderId: 5 })

    const url = new URL(calls()[0][0])
    expect(url.searchParams.get('folderId')).toBe('5')
    expect(url.searchParams.has('unfiled')).toBe(false)
  })

  it('listGeneratedImages は unfiled=true を載せ、false/未指定では載せない', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(undefined, { unfiled: true })
    await listGeneratedImages(undefined, { unfiled: false })

    expect(new URL(calls()[0][0]).searchParams.get('unfiled')).toBe('true')
    expect(new URL(calls()[1][0]).searchParams.has('unfiled')).toBe(false)
  })

  it('listGeneratedImages は source(UPLOAD / AI)を載せ、未指定では載せない(issue #1647)', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]))

    await listGeneratedImages(undefined, { source: 'UPLOAD', tag: '猫', limit: 24, offset: 24 })
    await listGeneratedImages(undefined, { source: 'AI' })
    await listGeneratedImages(undefined, {})

    // 失敗時の操作ログ送信(/api/operation-logs)が間に挟まるので、一覧の呼び出しだけを取り出す。
    const listCalls = calls().filter((c) => new URL(c[0]).pathname === '/api/generated-images')
    const first = new URL(listCalls[0][0]).searchParams
    expect(first.get('source')).toBe('UPLOAD')
    expect(first.get('tag')).toBe('猫')
    expect(first.get('offset')).toBe('24')
    expect(new URL(listCalls[1][0]).searchParams.get('source')).toBe('AI')
    expect(new URL(listCalls[2][0]).searchParams.has('source')).toBe(false)
  })

  it('listGeneratedImageFolders は GET /api/generated-images/folders を呼ぶ', async () => {
    const folders = [{ id: 1, name: '風景', parentId: null }]
    fetchMock.mockResolvedValue(jsonResponse(folders))

    await expect(listGeneratedImageFolders()).resolves.toEqual(folders)
    expect(String(calls()[0][0])).toMatch(/\/api\/generated-images\/folders$/)
  })

  it('createGeneratedImageFolder は name と parentId を POST する', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 2, name: '山', parentId: 1 }))

    await expect(createGeneratedImageFolder('山', 1)).resolves.toEqual({ id: 2, name: '山', parentId: 1 })
    const [url, init] = calls()[0]
    expect(String(url)).toMatch(/\/api\/generated-images\/folders$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ name: '山', parentId: 1 })
  })

  it('setGeneratedImageFolder は folderId を PUT する(null は未分類)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 7, folderId: null }))

    await setGeneratedImageFolder(7, null)

    const [url, init] = calls()[0]
    expect(String(url)).toMatch(/\/api\/generated-images\/7\/folder$/)
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ folderId: null })
  })
})

describe('生成画像フォルダの改名・削除(issue #1494)', () => {
  it('renameGeneratedImageFolder は name を PUT する', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 3, name: '新名', parentId: null }))

    await expect(renameGeneratedImageFolder(3, '新名')).resolves.toEqual({ id: 3, name: '新名', parentId: null })
    const [url, init] = calls()[0]
    expect(String(url)).toMatch(/\/api\/generated-images\/folders\/3\/name$/)
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ name: '新名' })
  })

  it('getGeneratedImageFolderDeleteImpact は delete-impact を GET する', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ descendantFolderCount: 2, imageCount: 5 }))

    await expect(getGeneratedImageFolderDeleteImpact(3)).resolves.toEqual({ descendantFolderCount: 2, imageCount: 5 })
    expect(String(calls()[0][0])).toMatch(/\/api\/generated-images\/folders\/3\/delete-impact$/)
  })

  it('deleteGeneratedImageFolder は DELETE する', async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, 204))

    await expect(deleteGeneratedImageFolder(3)).resolves.toBeUndefined()
    const [url, init] = calls()[0]
    expect(String(url)).toMatch(/\/api\/generated-images\/folders\/3$/)
    expect(init.method).toBe('DELETE')
  })
})

describe('プロジェクトの X 接続(issue #1574)', () => {
  it('getProjectXConnectionは接続状態を取得する', async () => {
    const view = { connectable: true, reason: null, siteName: '本番', status: null, log: null }
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectXConnection(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/x')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('startProjectXAuthorizationはクライアントの情報とリダイレクト先をPOSTし認可URLを受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ authorizeUrl: 'https://x.example/authorize' }))

    const result = await startProjectXAuthorization(7, {
      clientId: 'cid',
      clientSecret: 'csecret',
      redirectUri: 'https://localhost/connect/x/callback',
    })

    expect(result).toEqual({ authorizeUrl: 'https://x.example/authorize' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/x/authorize')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      clientId: 'cid',
      clientSecret: 'csecret',
      redirectUri: 'https://localhost/connect/x/callback',
    })
  })

  it('completeProjectXAuthorizationはstateとコードをPOSTしアカウント名を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, accountName: 'lets_blog' }))

    const result = await completeProjectXAuthorization(7, { state: '7.abc', code: 'the-code' })

    expect(result).toEqual({ projectId: 7, accountName: 'lets_blog' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/x/callback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', code: 'the-code' })
  })

  it('testProjectXPostはテスト投稿をPOSTし結果を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: true, error: null }))

    await expect(testProjectXPost(7)).resolves.toEqual({ success: true, error: null })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/x/test')
    expect(init.method).toBe('POST')
  })
})

describe('プロジェクトの PV 達成ルール(issue #1578)', () => {
  const view = {
    addable: true,
    reason: null,
    rules: [{ id: 'r1', period: 'daily', threshold: 100 }],
    send: { state: 'SENT', error: null, at: null },
  }

  it('getProjectPvRulesはルールと送信状態を取得する', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectPvRules(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/pv')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('addProjectPvRuleは期間と閾値をPOSTする', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(addProjectPvRule(7, { period: 'total', threshold: 5000 })).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/pv/rules')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ period: 'total', threshold: 5000 })
  })

  it('deleteProjectPvRuleはルールIDを指定してDELETEする', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(deleteProjectPvRule(7, 'r1')).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/pv/rules/r1')
    expect(init.method).toBe('DELETE')
  })

  it('resendProjectPvRulesは再送をPOSTする', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(resendProjectPvRules(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/pv/resend')
    expect(init.method).toBe('POST')
  })
})

describe('プロジェクトの告知文テンプレート(issue #1583)', () => {
  const view = {
    publishTemplate: '【新着】{title} {url}',
    pvTemplate: '',
    send: { state: 'SENT', error: null, at: null },
  }

  it('getProjectSnsTemplatesはテンプレートと送信状態を取得する', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectSnsTemplates(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/templates')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('saveProjectSnsTemplatesは公開時と PV 達成時のテンプレートをPUTする', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(
      saveProjectSnsTemplates(7, { publishTemplate: '【新着】{title} {url}', pvTemplate: '{threshold}PV' })
    ).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/templates')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ publishTemplate: '【新着】{title} {url}', pvTemplate: '{threshold}PV' })
  })

  it('resendProjectSnsTemplatesは再送をPOSTする', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(resendProjectSnsTemplates(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/templates/resend')
    expect(init.method).toBe('POST')
  })
})

describe('プロジェクトの Threads 接続(issue #1579)', () => {
  it('getProjectThreadsConnectionは接続状態を取得する', async () => {
    const view = { connectable: true, reason: null, siteName: '本番', status: null, log: null }
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectThreadsConnection(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/threads')
    expect(url).not.toContain('/sns/x')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('startProjectThreadsAuthorizationはアプリの情報とリダイレクト先をPOSTし認可URLを受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ authorizeUrl: 'https://threads.example/authorize' }))

    const result = await startProjectThreadsAuthorization(7, {
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/threads/callback',
    })

    expect(result).toEqual({ authorizeUrl: 'https://threads.example/authorize' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/threads/authorize')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/threads/callback',
    })
  })

  it('completeProjectThreadsAuthorizationはstateとコードをPOSTしアカウント名を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, accountName: 'lets_blog' }))

    const result = await completeProjectThreadsAuthorization(7, { state: '7.abc', code: 'the-code' })

    expect(result).toEqual({ projectId: 7, accountName: 'lets_blog' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/threads/callback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', code: 'the-code' })
  })

  it('testProjectThreadsPostはテスト投稿をPOSTし結果を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: true, error: null }))

    await expect(testProjectThreadsPost(7)).resolves.toEqual({ success: true, error: null })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/threads/test')
    expect(init.method).toBe('POST')
  })

  it('disconnectProjectThreadsはDELETEで切断する', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await disconnectProjectThreads(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/threads')
    expect(init.method).toBe('DELETE')
  })
})

describe('プロジェクトの LinkedIn 接続(issue #1581)', () => {
  it('getProjectLinkedInConnectionは接続状態を取得する', async () => {
    const view = { connectable: true, reason: null, siteName: '本番', status: null, log: null }
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectLinkedInConnection(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/linkedin')
    expect(url).not.toContain('/sns/x')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('startProjectLinkedInAuthorizationはアプリの情報とリダイレクト先をPOSTし認可URLを受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ authorizeUrl: 'https://linkedin.example/authorize' }))

    const result = await startProjectLinkedInAuthorization(7, {
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/linkedin/callback',
    })

    expect(result).toEqual({ authorizeUrl: 'https://linkedin.example/authorize' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/linkedin/authorize')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/linkedin/callback',
    })
  })

  it('completeProjectLinkedInAuthorizationはstateとコードをPOSTしアカウント名を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, accountName: "Let's Blog E2E" }))

    const result = await completeProjectLinkedInAuthorization(7, { state: '7.abc', code: 'the-code' })

    expect(result).toEqual({ projectId: 7, accountName: "Let's Blog E2E" })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/linkedin/callback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', code: 'the-code' })
  })

  it('testProjectLinkedInPostはテスト投稿をPOSTし結果を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: true, error: null }))

    await expect(testProjectLinkedInPost(7)).resolves.toEqual({ success: true, error: null })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/linkedin/test')
    expect(init.method).toBe('POST')
  })

  it('disconnectProjectLinkedInはDELETEで切断する', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await disconnectProjectLinkedIn(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/linkedin')
    expect(init.method).toBe('DELETE')
  })
})

describe('プロジェクトのはてなブックマーク接続(issue #1582)', () => {
  it('getProjectHatenaConnectionは接続状態を取得する', async () => {
    const view = { connectable: true, reason: null, siteName: '本番', status: null, log: null }
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectHatenaConnection(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/hatena')
    expect(url).not.toContain('/sns/x')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('startProjectHatenaAuthorizationはconsumerの情報とリダイレクト先をPOSTし認可URLを受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ authorizeUrl: 'https://hatena.example/authorize' }))

    const result = await startProjectHatenaAuthorization(7, {
      clientId: 'consumer-key',
      clientSecret: 'consumer-secret',
      redirectUri: 'https://localhost/connect/hatena/callback',
    })

    expect(result).toEqual({ authorizeUrl: 'https://hatena.example/authorize' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/hatena/authorize')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      clientId: 'consumer-key',
      clientSecret: 'consumer-secret',
      redirectUri: 'https://localhost/connect/hatena/callback',
    })
  })

  it('completeProjectHatenaAuthorizationはstateとリクエストトークンとverifierをPOSTしアカウント名を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, accountName: "Let's Blog E2E" }))

    const result = await completeProjectHatenaAuthorization(7, { state: '7.abc', oauthToken: 'rt', oauthVerifier: 'v' })

    expect(result).toEqual({ projectId: 7, accountName: "Let's Blog E2E" })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/hatena/callback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', oauthToken: 'rt', oauthVerifier: 'v' })
  })

  it('testProjectHatenaPostはテスト投稿をPOSTし結果を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: true, error: null }))

    await expect(testProjectHatenaPost(7)).resolves.toEqual({ success: true, error: null })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/hatena/test')
    expect(init.method).toBe('POST')
  })

  it('disconnectProjectHatenaはDELETEで切断する', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await disconnectProjectHatena(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/hatena')
    expect(init.method).toBe('DELETE')
  })
})

describe('プロジェクトの Facebook ページ接続(issue #1580)', () => {
  it('getProjectFacebookConnectionは接続状態を取得する', async () => {
    const view = { connectable: true, reason: null, siteName: '本番', status: null, log: null }
    fetchMock.mockResolvedValue(jsonResponse(view))

    await expect(getProjectFacebookConnection(7)).resolves.toEqual(view)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook')
    expect(url).not.toContain('/sns/x')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('startProjectFacebookAuthorizationはアプリの情報とリダイレクト先をPOSTし認可URLを受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ authorizeUrl: 'https://facebook.example/dialog/oauth' }))

    const result = await startProjectFacebookAuthorization(7, {
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/facebook/callback',
    })

    expect(result).toEqual({ authorizeUrl: 'https://facebook.example/dialog/oauth' })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook/authorize')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      clientId: 'app-id',
      clientSecret: 'app-secret',
      redirectUri: 'https://localhost/connect/facebook/callback',
    })
  })

  it('completeProjectFacebookAuthorizationはstateとコードをPOSTし選べるページの一覧を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, pages: [{ id: '100', name: 'ページA' }] }))

    const result = await completeProjectFacebookAuthorization(7, { state: '7.abc', code: 'the-code' })

    expect(result).toEqual({ projectId: 7, pages: [{ id: '100', name: 'ページA' }] })
    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook/callback')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', code: 'the-code' })
  })

  it('getProjectFacebookPagesはstateを付けて選べるページの一覧を取得する', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, pages: [{ id: '100', name: 'ページA' }] }))

    await expect(getProjectFacebookPages(7, '7.a b')).resolves.toEqual({ projectId: 7, pages: [{ id: '100', name: 'ページA' }] })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook/pages?state=7.a%20b')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('selectProjectFacebookPageはstateとページIDをPOSTしページ名を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ projectId: 7, accountName: 'ページA' }))

    await expect(selectProjectFacebookPage(7, { state: '7.abc', pageId: '100' })).resolves.toEqual({ projectId: 7, accountName: 'ページA' })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook/page')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ state: '7.abc', pageId: '100' })
  })

  it('testProjectFacebookPostはテスト投稿をPOSTし結果を受け取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: true, error: null }))

    await expect(testProjectFacebookPost(7)).resolves.toEqual({ success: true, error: null })

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook/test')
    expect(init.method).toBe('POST')
  })

  it('disconnectProjectFacebookはDELETEで切断する', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, statusText: 'No Content', text: async () => '', headers: { get: () => null } } as unknown as Response)

    await disconnectProjectFacebook(7)

    const [url, init] = calls()[0]
    expect(url).toContain('/api/projects/7/sns/facebook')
    expect(init.method).toBe('DELETE')
  })

  it('getLetsblogSync は未同期(空本文)を undefined ではなく null として返す (issue #1660)', async () => {
    fetchMock.mockResolvedValue(textResponse('', 200, 'OK'))

    await expect(getLetsblogSync(7)).resolves.toBeNull()
    expect(calls()[0][0]).toContain('/api/sites/7/letsblog-sync')
  })

  it('getLetsblogSync は同期済みの状態をそのまま返す (issue #1660)', async () => {
    const state = { status: 'SYNCED', error: null, hash: 'abc', syncedAt: '2026-10-04T00:00:00Z' }
    fetchMock.mockResolvedValue(jsonResponse(state))

    await expect(getLetsblogSync(7)).resolves.toEqual(state)
  })
})
