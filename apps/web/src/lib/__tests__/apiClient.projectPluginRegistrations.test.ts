/**
 * issue #1808: プロジェクト単位のプラグインの登録の上書き API(一覧・追加・編集・削除・使用中の切り替え・認証情報の消去)の検証。
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
  activateProjectPluginRegistration,
  clearProjectPluginRegistrationCredential,
  createProjectPluginRegistration,
  deleteProjectPluginRegistration,
  listProjectPluginRegistrations,
  updateProjectPluginRegistration,
} from '@/lib/apiClient'

let fetchMock: jest.Mock

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    text: async () => JSON.stringify(body),
    headers: { get: () => 'application/json' },
  } as unknown as Response
}

function emptyResponse(): Response {
  return {
    ok: true,
    status: 204,
    statusText: 'No Content',
    text: async () => '',
    headers: { get: () => null },
  } as unknown as Response
}

const VIEW = {
  id: 1, kind: 'LLM', name: '社内', url: 'http://gpu-pc:11434/v1', inUse: false, drawioUrl: null, credentialConfigured: true,
}
const BASE = '/api/projects/10/plugin-registrations'

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

describe('プロジェクト単位のプラグインの登録のAPIクライアント', () => {
  it('listProjectPluginRegistrations は GET /api/projects/{id}/plugin-registrations', async () => {
    fetchMock.mockResolvedValue(jsonResponse([VIEW]))

    expect(await listProjectPluginRegistrations(10)).toEqual([VIEW])
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(BASE)
  })

  it('createProjectPluginRegistration は POST で種類・名前・URL・drawioUrl・認証情報を JSON で送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse(VIEW))

    await createProjectPluginRegistration(10, { kind: 'LLM', name: '社内', url: 'http://gpu-pc:11434/v1', drawioUrl: null, credential: 'sk-1' })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(BASE)
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      kind: 'LLM', name: '社内', url: 'http://gpu-pc:11434/v1', drawioUrl: null, credential: 'sk-1',
    })
  })

  it('updateProjectPluginRegistration は PUT /{id} で名前・URL・drawioUrl・認証情報を送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse(VIEW))

    await updateProjectPluginRegistration(10, 7, { name: '新', url: 'http://gpu-pc:2', drawioUrl: null, credential: null })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`${BASE}/7`)
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ name: '新', url: 'http://gpu-pc:2', drawioUrl: null, credential: null })
  })

  it('deleteProjectPluginRegistration は DELETE /{id}', async () => {
    fetchMock.mockResolvedValue(emptyResponse())

    await deleteProjectPluginRegistration(10, 7)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`${BASE}/7`)
    expect(init.method).toBe('DELETE')
  })

  it('activateProjectPluginRegistration は PUT /{id}/in-use', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...VIEW, inUse: true }))

    expect((await activateProjectPluginRegistration(10, 7)).inUse).toBe(true)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`${BASE}/7/in-use`)
    expect(init.method).toBe('PUT')
  })

  it('clearProjectPluginRegistrationCredential は DELETE /{id}/credential', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...VIEW, credentialConfigured: false }))

    expect((await clearProjectPluginRegistrationCredential(10, 7)).credentialConfigured).toBe(false)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`${BASE}/7/credential`)
    expect(init.method).toBe('DELETE')
  })
})
