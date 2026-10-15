/**
 * issue #1807: システム全体のプラグインの登録 API(一覧・追加・編集・削除・使用中の切り替え)の検証。
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
  activatePluginRegistration,
  createPluginRegistration,
  deletePluginRegistration,
  listPluginRegistrations,
  updatePluginRegistration,
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

const VIEW = { id: 1, kind: 'LLM', name: '社内', url: 'http://lbx-ollama:11434/v1', inUse: false, drawioUrl: null }

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

describe('プラグインの登録のAPIクライアント', () => {
  it('listPluginRegistrations は GET /api/system-settings/plugin-registrations', async () => {
    fetchMock.mockResolvedValue(jsonResponse([VIEW]))

    expect(await listPluginRegistrations()).toEqual([VIEW])
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/plugin-registrations')
  })

  it('createPluginRegistration は POST で種類・名前・URL・drawioUrl を JSON で送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse(VIEW))

    await createPluginRegistration({ kind: 'DIAGRAM_RENDERER', name: '図', url: 'http://lbx-plantuml:8085', drawioUrl: 'https://d.example/' })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/plugin-registrations')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      kind: 'DIAGRAM_RENDERER', name: '図', url: 'http://lbx-plantuml:8085', drawioUrl: 'https://d.example/',
    })
  })

  it('updatePluginRegistration は PUT /{id} で名前・URL・drawioUrl を送る', async () => {
    fetchMock.mockResolvedValue(jsonResponse(VIEW))

    await updatePluginRegistration(7, { name: '新', url: 'http://lbx-ollama:2', drawioUrl: null })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/plugin-registrations/7')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ name: '新', url: 'http://lbx-ollama:2', drawioUrl: null })
  })

  it('deletePluginRegistration は DELETE /{id}', async () => {
    fetchMock.mockResolvedValue(emptyResponse())

    await deletePluginRegistration(7)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/plugin-registrations/7')
    expect(init.method).toBe('DELETE')
  })

  it('activatePluginRegistration は PUT /{id}/in-use', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...VIEW, inUse: true }))

    expect((await activatePluginRegistration(7)).inUse).toBe(true)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/plugin-registrations/7/in-use')
    expect(init.method).toBe('PUT')
  })
})
