/**
 * issue #1655: 画像を編集して新しい画像として保存する `editGeneratedImage` の検証。
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

import { editGeneratedImage } from '@/lib/apiClient'

let fetchMock: jest.Mock

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

describe('editGeneratedImage(issue #1655)', () => {
  it('操作と切り抜き範囲を JSON で POST し、新しい画像を返す', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      statusText: 'Created',
      text: async () => JSON.stringify({ id: 9, provider: 'UPLOAD' }),
      headers: { get: () => 'application/json' },
    } as unknown as Response)

    const result = await editGeneratedImage(3, ['ROTATE_CW', 'FLIP_HORIZONTAL'], { x: 1, y: 2, width: 3, height: 4 }, {
      brightness: 20,
      contrast: -10,
    })

    expect(result).toEqual({ id: 9, provider: 'UPLOAD' })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit & { headers?: Record<string, string> }]
    expect(url).toContain('/api/generated-images/3/edit')
    expect(init.method).toBe('POST')
    expect(init.headers?.['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({
      operations: ['ROTATE_CW', 'FLIP_HORIZONTAL'],
      crop: { x: 1, y: 2, width: 3, height: 4 },
      adjustment: { brightness: 20, contrast: -10 },
    })
  })

  it('調整を省略(null)すると adjustment: null で送る', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      statusText: 'Created',
      text: async () => JSON.stringify({ id: 9 }),
      headers: { get: () => 'application/json' },
    } as unknown as Response)

    await editGeneratedImage(3, ['ROTATE_CW'], null, null)

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({ operations: ['ROTATE_CW'], crop: null, adjustment: null })
  })
})
