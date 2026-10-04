/**
 * issue #1599: 画像ファイルを生成画像ギャラリーへ登録する `uploadGeneratedImage` の検証。
 * `apiClient.test.ts` と同じく、全リクエストが通る共通経路を fetch のモックで確かめる。
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

import { uploadGeneratedImage } from '@/lib/apiClient'

let fetchMock: jest.Mock

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  cookiesMock.mockResolvedValue({})
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
})

describe('uploadGeneratedImage(issue #1599)', () => {
  it('projectId をクエリに、ファイルを multipart の file として POST し、登録された画像を返す', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      statusText: 'Created',
      text: async () => JSON.stringify({ id: 9, provider: 'UPLOAD', prompt: null }),
      headers: { get: () => 'application/json' },
    } as unknown as Response)
    const file = new File([new Uint8Array([1, 2, 3])], 'photo.jpg', { type: 'image/jpeg' })

    const result = await uploadGeneratedImage(7, file)

    expect(result).toEqual({ id: 9, provider: 'UPLOAD', prompt: null })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit & { headers?: Record<string, string> }]
    expect(url).toContain('/api/generated-images/upload?projectId=7')
    expect(init.method).toBe('POST')
    expect(init.headers?.Authorization).toBe('Bearer access-token')
    const body = init.body as FormData
    expect(body.get('file')).toBeInstanceOf(File)
    expect((body.get('file') as File).name).toBe('photo.jpg')
  })

  it('サーバーが拒否したらその理由を含む Error を投げる', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      text: async () => JSON.stringify({ error: '対応していない画像形式です。JPEGまたはPNGを選択してください。' }),
      headers: { get: () => 'application/json' },
    } as unknown as Response)
    const file = new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' })

    await expect(uploadGeneratedImage(7, file)).rejects.toThrow(/対応していない画像形式/)
  })
})
