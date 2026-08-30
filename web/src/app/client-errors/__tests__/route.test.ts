/**
 * @jest-environment node
 */
import { POST } from '../route';
import { logFrontendError } from '@/lib/apiClient';
import { getSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  logFrontendError: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  getSession: jest.fn(),
}));

const mockLogFrontendError = logFrontendError as jest.MockedFunction<typeof logFrontendError>;
const mockGetSession = getSession as jest.MockedFunction<typeof getSession>;

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
    jest.clearAllMocks();
    console.error = jest.fn();
  });

  afterEach(() => {
    console.error = originalConsoleError;
  });

  it('ログイン済みならlog-writerへ中継して204を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockLogFrontendError.mockResolvedValue(undefined);

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(mockLogFrontendError).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'Test error', level: 'error' })
    );
  });

  it('未認証なら中継せずに204を返す(log-writerを未認証で叩かない)', async () => {
    mockGetSession.mockResolvedValue(null);

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(mockLogFrontendError).not.toHaveBeenCalled();
  });

  it('JSONでないボディは400を返し、セッションも確認しない', async () => {
    const res = await POST(postRequest('not json'));

    expect(res.status).toBe(400);
    expect(mockGetSession).not.toHaveBeenCalled();
    expect(mockLogFrontendError).not.toHaveBeenCalled();
  });

  it('session.errorが立っていれば中継せずに204を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {}, error: 'RefreshAccessTokenError' } as never);

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(204);
    expect(mockLogFrontendError).not.toHaveBeenCalled();
  });

  it.each([['info'], [undefined], ['ERROR']])('levelが%sなら400を返す', async (level) => {
    const res = await POST(postRequest({ ...validPayload, level }));

    expect(res.status).toBe(400);
    expect(mockLogFrontendError).not.toHaveBeenCalled();
  });

  it.each([[''], [undefined], [123]])('messageが%sなら400を返す', async (message) => {
    const res = await POST(postRequest({ ...validPayload, message }));

    expect(res.status).toBe(400);
    expect(mockGetSession).not.toHaveBeenCalled();
    expect(mockLogFrontendError).not.toHaveBeenCalled();
  });

  it('ペイロードの全フィールドをそのまま中継する', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockLogFrontendError.mockResolvedValue(undefined);

    const full = {
      ...validPayload,
      stack: 'at foo (bar.ts:1:1)',
      componentStack: 'at Component',
      context: { projectId: 3 },
      url: 'https://localhost/projects/3',
      userAgent: 'jest',
    };
    await POST(postRequest(full));

    expect(mockLogFrontendError).toHaveBeenCalledWith(full);
  });

  it('log-writerへの中継が失敗しても502を返すだけでthrowしない', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockLogFrontendError.mockRejectedValue(new Error('APIエラー (503)'));

    const res = await POST(postRequest(validPayload));

    expect(res.status).toBe(502);
    expect(console.error).toHaveBeenCalled();
  });
});
