import { logErrorToBackend, logErrorToConsole } from '../errorLogger';

describe('errorLogger', () => {
  const originalFetch = global.fetch;
  const originalConsoleError = console.error;

  beforeEach(() => {
    jest.clearAllMocks();
    global.fetch = jest.fn();
    console.error = jest.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    console.error = originalConsoleError;
  });

  describe('logErrorToBackend', () => {
    it('should send error to backend', async () => {
      const mockFetch = jest.fn().mockResolvedValue({ ok: true });
      global.fetch = mockFetch;

      const error = new Error('Test error');
      await logErrorToBackend(error, { level: 'error' });

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/logs/errors',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const callArgs = mockFetch.mock.calls[0];
      const body = JSON.parse(callArgs[1].body);
      expect(body.message).toBe('Test error');
      expect(body.level).toBe('error');
    });

    it('should handle string errors', async () => {
      const mockFetch = jest.fn().mockResolvedValue({ ok: true });
      global.fetch = mockFetch;

      await logErrorToBackend('String error message', { level: 'warn' });

      const callArgs = mockFetch.mock.calls[0];
      const body = JSON.parse(callArgs[1].body);
      expect(body.message).toBe('String error message');
      expect(body.level).toBe('warn');
    });

    it('should handle fetch errors gracefully', async () => {
      const mockFetch = jest.fn().mockRejectedValue(new Error('Network error'));
      global.fetch = mockFetch;
      const consoleErrorSpy = jest.fn();
      console.error = consoleErrorSpy;

      const error = new Error('Test error');
      await logErrorToBackend(error);

      expect(consoleErrorSpy).toHaveBeenCalledWith(
        'Failed to log error to backend:',
        expect.any(Error)
      );
    });

    it('should include context and component stack', async () => {
      const mockFetch = jest.fn().mockResolvedValue({ ok: true });
      global.fetch = mockFetch;

      const error = new Error('Test error');
      await logErrorToBackend(error, {
        context: { userId: 123 },
        componentStack: 'Component stack trace',
      });

      const callArgs = mockFetch.mock.calls[0];
      const body = JSON.parse(callArgs[1].body);
      expect(body.context).toEqual({ userId: 123 });
      expect(body.componentStack).toBe('Component stack trace');
    });
  });

  describe('logErrorToConsole', () => {
    it('should log error to console', () => {
      const error = new Error('Test error');
      const consoleErrorSpy = jest.fn();
      console.error = consoleErrorSpy;

      logErrorToConsole('Error message', error, { page: 'test' });

      expect(consoleErrorSpy).toHaveBeenCalledWith(
        '[ERROR] Error message',
        expect.objectContaining({
          error,
          context: { page: 'test' },
        })
      );
    });

    it('should work without error or context', () => {
      const consoleErrorSpy = jest.fn();
      console.error = consoleErrorSpy;

      logErrorToConsole('Simple error message');

      expect(consoleErrorSpy).toHaveBeenCalled();
    });
  });
});
