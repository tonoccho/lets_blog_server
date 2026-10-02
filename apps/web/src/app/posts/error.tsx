'use client';

import { useEffect, useRef } from 'react';
import Link from 'next/link';
import { logErrorToBackend, logErrorToConsole } from '@/lib/errorLogger';

interface ErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function PostsError({ error, reset }: ErrorProps) {
  // StrictModeのeffect二重実行でも同じerrorを2回送らない(#1394)。別のerrorなら改めて送る。
  const loggedError = useRef<Error | null>(null);
  useEffect(() => {
    if (loggedError.current === error) return;
    loggedError.current = error;
    logErrorToConsole('Posts page error', error, { page: 'posts' });
    logErrorToBackend(error, {
      level: 'error',
      context: { page: 'posts', digest: error.digest },
    });
  }, [error]);

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-bold text-neutral-900 dark:text-neutral-50">
          投稿の読み込みに失敗しました
        </h1>
        <p className="text-neutral-600 dark:text-neutral-400">
          投稿一覧の取得中にエラーが発生しました。
        </p>
      </div>

      <div className="rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950">
        <p className="text-sm text-red-800 dark:text-red-200">
          {error.message || '予期しないエラーが発生しました'}
        </p>
      </div>

      <div className="flex gap-3">
        <button
          onClick={reset}
          className="rounded-lg bg-blue-600 px-4 py-2 font-semibold text-white transition-colors hover:bg-blue-700 dark:hover:bg-blue-500"
        >
          もう一度試す
        </button>
        <Link
          href="/"
          className="rounded-lg border border-neutral-300 px-4 py-2 font-semibold text-neutral-900 transition-colors hover:bg-neutral-100 dark:border-neutral-700 dark:text-neutral-50 dark:hover:bg-neutral-900"
        >
          ホームに戻る
        </Link>
      </div>
    </div>
  );
}
