import type { GoogleAnalyticsReport } from "@/lib/apiClient";

/**
 * Google Analyticsダッシュボードウィジェットの中身(issue #386)。configured=trueのとき
 * DashboardWidgetSlotのchildrenとして描画される。GA4取得自体に失敗した場合(errorMessageあり)は、
 * ページ全体をエラーにはせずウィジェット内にエラー文言のみ表示する。
 */
export function GoogleAnalyticsWidget({ report }: { report: GoogleAnalyticsReport }) {
  if (report.errorMessage) {
    return <p className="text-sm text-red-600">取得に失敗しました: {report.errorMessage}</p>;
  }

  return (
    <div className="space-y-2">
      {report.periodLabel && (
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{report.periodLabel}</p>
      )}
      <div className="grid grid-cols-3 gap-2 text-center">
        <div>
          <p className="text-lg font-semibold">{report.sessions?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">セッション数</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{report.activeUsers?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">ユーザー数</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{report.pageViews?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">ページビュー</p>
        </div>
      </div>
    </div>
  );
}
