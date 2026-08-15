import type { SocialStats } from "@/lib/apiClient";

/**
 * ソーシャル統計ダッシュボードウィジェットの中身(issue #390)。GoogleAnalyticsWidget/AdSenseWidgetと
 * 同じ方針で、APIレベルの取得失敗(errorMessageあり)はページ全体をエラーにせずウィジェット内にのみ表示する。
 * GA/AdSenseと異なり期間ではなく、集計対象のBuffer送信済み投稿数(postCount)を表示する。
 */
export function SocialStatsWidget({ stats }: { stats: SocialStats }) {
  if (stats.errorMessage) {
    return <p className="text-sm text-red-600">取得に失敗しました: {stats.errorMessage}</p>;
  }

  return (
    <div className="space-y-2">
      {stats.postCount !== null && (
        <p className="text-xs text-neutral-500 dark:text-neutral-400">Buffer経由の送信済み投稿{stats.postCount}件が対象</p>
      )}
      <div className="grid grid-cols-4 gap-2 text-center">
        <div>
          <p className="text-lg font-semibold">{stats.likes?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">いいね</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{stats.shares?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">シェア</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{stats.comments?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">コメント</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{stats.clicks?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">クリック</p>
        </div>
      </div>
    </div>
  );
}
