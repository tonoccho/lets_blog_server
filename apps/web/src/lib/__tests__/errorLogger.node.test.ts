/**
 * @jest-environment node
 *
 * window / navigator が無い環境(Node)で logErrorToBackend を呼んだときのペイロード。
 * 既存の errorLogger.test.ts は jsdom で、url と userAgent が入る側を検証している。
 */
import { logErrorToBackend } from '../errorLogger';

describe('logErrorToBackend(window / navigator が無い環境)', () => {
  const originalFetch = global.fetch;

  const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');

  beforeEach(() => {
    // Node 21 以降は globalThis.navigator を持つ。navigator が無い実行環境を再現するため一時的に外す。
    Object.defineProperty(globalThis, 'navigator', { value: undefined, configurable: true });
  });

  afterEach(() => {
    global.fetch = originalFetch;
    if (navigatorDescriptor) Object.defineProperty(globalThis, 'navigator', navigatorDescriptor);
  });

  it('url と userAgent を載せずに送る', async () => {
    const mockFetch = jest.fn().mockResolvedValue({ ok: true });
    global.fetch = mockFetch;

    await logErrorToBackend('boom');

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.message).toBe('boom');
    expect(body).not.toHaveProperty('url');
    expect(body).not.toHaveProperty('userAgent');
  });
});
