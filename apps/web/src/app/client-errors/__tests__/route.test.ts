/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth は差し替えない。差し替えるのは Next.js の実行基盤
// (next-auth・next/headers・next/server の after)と、外部との通信(fetch)だけ(#1832)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

import {
  getServerSessionMock as mockGetSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
} from '@/lib/__tests__/testUtils/serverRuntime';

import { POST } from '../route';

// log-writer(gateway の向こう)の応答。既定は 201。
let logWriterResponse: () => Response = () => jsonResponse({}, 201);
let fetchMock: jest.Mock;

/** log-writer の POST /api/logs/errors に飛んだ呼び出し(操作ログの記録は含めない)。 */
function logWriterCalls(): Array<[string, { body?: BodyInit }]> {
  return fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/logs/errors')) as Array<
    [string, { body?: BodyInit }]
  >;
}

function postRequest(body: unknown): Request {
  return new Request('http://localhost/client-errors', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

const validPayload = {
  message: 'Test error',
  level: 'error' as const,
  timestamp: '2026-08-30T00:00:00.000Z',
};

describe('POST /client-errors', () => {
  const originalConsoleError = console.error;

  beforeEach(() => {
    resetServerRuntime(null);
    logWriterResponse = () => jsonResponse({}, 201);
    fetchMock = installFetch({ 'POST /api/logs/errors': () => logWriterResponse() });
    console.error = jest.fn();
  });

  afterEach(() => {
    console.error = originalConsoleError;
  });

  it('ログイン済みならlog-writerへ中継して204を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} });

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(JSON.parse(String(logWriterCalls()[0][1].body))).toEqual(
      expect.objectContaining({ message: 'Test error', level: 'error' })
    );
  });

  it('未認証なら中継せずに204を返す(log-writerを未認証で叩かない)', async () => {
    mockGetSession.mockResolvedValue(null);

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(logWriterCalls()).toHaveLength(0);
  });

  it('JSONでないボディは400を返し、セッションも確認しない', async () => {
    const res = await POST(postRequest('not json'));

    expect(res.status).toBe(400);
    expect(mockGetSession).not.toHaveBeenCalled();
    expect(logWriterCalls()).toHaveLength(0);
  });

  it('session.errorが立っていれば中継せずに204を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {}, error: 'RefreshAccessTokenError' });

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(logWriterCalls()).toHaveLength(0);
  });

  it.each([['info'], [undefined], ['ERROR']])('levelが%sなら400を返す', async (level) => {
    const res = await POST(postRequest({ ...validPayload, level }));

    expect(res.status).toBe(400);
    expect(mockGetSession).not.toHaveBeenCalled();
    expect(logWriterCalls()).toHaveLength(0);
  });

  it.each([[''], [undefined], [123]])('messageが%sなら400を返す', async (message) => {
    const res = await POST(postRequest({ ...validPayload, message }));

    expect(res.status).toBe(400);
    expect(mockGetSession).not.toHaveBeenCalled();
    expect(logWriterCalls()).toHaveLength(0);
  });

  it('ペイロードの全フィールドをそのまま中継する', async () => {
    mockGetSession.mockResolvedValue({ user: {} });

    const full = {
      ...validPayload,
      stack: 'at foo (bar.ts:1:1)',
      componentStack: 'at Component',
      context: { projectId: 3 },
      url: 'https://localhost/projects/3',
      userAgent: 'jest',
    };
    await POST(postRequest(full));

    expect(JSON.parse(String(logWriterCalls()[0][1].body))).toEqual(full);
  });

  it('log-writerへの中継が失敗しても502を返すだけでthrowしない', async () => {
    mockGetSession.mockResolvedValue({ user: {} });
    logWriterResponse = () => textResponse('unavailable', 503);

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(502);
    expect(console.error).toHaveBeenCalled();
  });
});
