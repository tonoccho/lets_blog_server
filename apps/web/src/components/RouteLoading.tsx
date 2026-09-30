/**
 * ルート単位の loading UI(各ルートの `loading.tsx`)が共通で描く、読み込み中の骨組み(issue #1475)。
 *
 * Server Component の全データ取得が終わるまで HTML が1バイトも返らないと、クリックしても
 * 画面が固まって見える。`loading.tsx` は Next.js が先にこの骨組みを配信し、取得完了後に
 * 最終表示へ置き換える。ページ遷移ごとに描くもので、`ProjectAiModelsPanel` などの
 * `TabLoading`(クライアント側のタブ切り替え用の文言だけの表示)とは役割が別で、置き換えではない。
 * 文言の既定値だけ `TabLoading` に揃えてある。
 *
 * `data-testid="route-loading"` は受け入れテスト(`e2e/features/ui-quality/route-loading.feature`)が
 * loading UI を見つける目印。
 */
export function RouteLoading({ label = "読み込み中…" }: { label?: string }) {
  return (
    <div role="status" aria-busy="true" data-testid="route-loading" className="space-y-6">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="space-y-4 motion-safe:animate-pulse">
        <div className="h-6 w-1/3 rounded bg-neutral-200 dark:bg-neutral-800" />
        <div className="h-4 w-1/4 rounded bg-neutral-200 dark:bg-neutral-800" />
        <div className="h-10 w-full rounded bg-neutral-200 dark:bg-neutral-800" />
        <div className="h-48 w-full rounded-lg bg-neutral-200 dark:bg-neutral-800" />
      </div>
    </div>
  );
}
