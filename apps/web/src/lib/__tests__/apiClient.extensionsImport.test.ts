/**
 * issue #1783: extensions のポータルから接続先を取り込む API(取得・反映)の検証。
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

import { applyExtensionsConnections, fetchExtensionsConnections } from '@/lib/apiClient'

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

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

describe('fetchExtensionsConnections', () => {
  it('POST .../extensions-import/fetch へポータルのURLと経路をJSONで送り、候補を返す', async () => {
    const body = { candidates: [], ignoredIds: ['gitlab'] }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await fetchExtensionsConnections('http://lbx-portal:3100', 'SAME_PC')

    expect(result).toEqual(body)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/app-settings/extensions-import/fetch')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ portalUrl: 'http://lbx-portal:3100', route: 'SAME_PC' })
  })
})

describe('applyExtensionsConnections', () => {
  it('POST .../extensions-import/apply へポータルのURLと値をJSONで送り、結果を返す', async () => {
    const body = { saved: ['comfyui_base_url'], rejected: [] }
    fetchMock.mockResolvedValue(jsonResponse(body))

    const result = await applyExtensionsConnections('http://lbx-portal:3100', { comfyui_base_url: 'http://x' })

    expect(result).toEqual(body)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/system-settings/app-settings/extensions-import/apply')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({
      portalUrl: 'http://lbx-portal:3100',
      values: { comfyui_base_url: 'http://x' },
    })
  })
})
