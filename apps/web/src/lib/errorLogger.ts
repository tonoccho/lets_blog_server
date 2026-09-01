interface ErrorLogPayload {
  message: string;
  stack?: string;
  componentStack?: string;
  level: 'error' | 'warn';
  context?: Record<string, unknown>;
  url?: string;
  userAgent?: string;
  timestamp: string;
}

export async function logErrorToBackend(error: Error | string, options?: {
  level?: 'error' | 'warn';
  context?: Record<string, unknown>;
  componentStack?: string;
}) {
  const message = typeof error === 'string' ? error : error.message;
  const stack = typeof error === 'string' ? undefined : error.stack;

  const payload: ErrorLogPayload = {
    message,
    stack,
    componentStack: options?.componentStack,
    level: options?.level || 'error',
    context: options?.context,
    url: typeof window !== 'undefined' ? window.location.href : undefined,
    userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : undefined,
    timestamp: new Date().toISOString(),
  };

  try {
    // 送信先は同一オリジンのBFF(apps/web/src/app/client-errors/route.ts)。
    // 以前はブラウザから /api/logs/errors を直叩きしていたが、Authorizationヘッダーが
    // 無いため #772 の認証ゲート復元で401になり、エラーログが無言で全滅していた(issue #791)。
    // nginx の location /api/ が /api/** を gateway へ転送するため、この中継先は
    // /api の外に置く必要がある。
    await fetch('/client-errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch (err) {
    console.error('Failed to log error to backend:', err);
  }
}

export function logErrorToConsole(
  message: string,
  error?: Error,
  context?: Record<string, unknown>
) {
  console.error(`[ERROR] ${message}`, {
    error,
    context,
    timestamp: new Date().toISOString(),
  });
}
