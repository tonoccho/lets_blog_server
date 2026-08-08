'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { logErrorToBackend, logErrorToConsole } from '@/lib/errorLogger';

interface ErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function GlobalError({ error, reset }: ErrorProps) {
  useEffect(() => {
    logErrorToConsole('Global error boundary triggered', error);
    logErrorToBackend(error, {
      level: 'error',
      context: { digest: error.digest },
    });
  }, [error]);

  return (
    <html lang="ja">
      <body className="flex min-h-screen items-center justify-center bg-neutral-50 dark:bg-neutral-950">
        <div className="mx-auto w-full max-w-md space-y-6 px-4 py-8">
          <div className="space-y-2">
            <h1 className="text-3xl font-bold text-neutral-900 dark:text-neutral-50">
              エラーが発生しました
            </h1>
            <p className="text-neutral-600 dark:text-neutral-400">
              申し訳ございません。予期しないエラーが発生しました。
            </p>
          </div>

          <div className="rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950">
            <p className="text-sm text-red-800 dark:text-red-200">
              <strong>エラーID:</strong> {error.digest || 'unknown'}
            </p>
            {process.env.NODE_ENV === 'development' && (
              <details className="mt-2">
                <summary className="cursor-pointer text-sm font-semibold text-red-800 dark:text-red-200">
                  詳細情報
                </summary>
                <pre className="mt-2 overflow-x-auto rounded bg-red-100 p-2 text-xs text-red-900 dark:bg-red-900 dark:text-red-100">
                  {error.message}
                  {error.stack}
                </pre>
              </details>
            )}
          </div>

          <div className="space-y-3">
            <button
              onClick={reset}
              className="w-full rounded-lg bg-blue-600 px-4 py-2 font-semibold text-white transition-colors hover:bg-blue-700 dark:hover:bg-blue-500"
            >
              もう一度試す
            </button>
            <Link
              href="/"
              className="block rounded-lg border border-neutral-300 px-4 py-2 text-center font-semibold text-neutral-900 transition-colors hover:bg-neutral-100 dark:border-neutral-700 dark:text-neutral-50 dark:hover:bg-neutral-900"
            >
              ホームページに戻る
            </Link>
          </div>

          {process.env.NODE_ENV === 'development' && (
            <div className="rounded-lg bg-yellow-50 p-4 dark:bg-yellow-950">
              <p className="text-xs text-yellow-800 dark:text-yellow-200">
                開発モード: エラーの詳細情報を上記に表示しています。
              </p>
            </div>
          )}
        </div>
      </body>
    </html>
  );
}
