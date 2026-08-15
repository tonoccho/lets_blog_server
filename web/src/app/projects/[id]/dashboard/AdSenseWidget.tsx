import type { AdSenseReport } from "@/lib/apiClient";

/**
 * Google AdSenseダッシュボードウィジェットの中身(issue #387)。GoogleAnalyticsWidgetと同じ方針で、
 * APIレベルの取得失敗(errorMessageあり)はページ全体をエラーにせずウィジェット内にのみ表示する。
 */
export function AdSenseWidget({ report }: { report: AdSenseReport }) {
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
          <p className="text-lg font-semibold">{report.estimatedEarnings ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">推定収益</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{report.clicks?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">クリック数</p>
        </div>
        <div>
          <p className="text-lg font-semibold">{report.impressions?.toLocaleString() ?? "-"}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">表示回数</p>
        </div>
      </div>
    </div>
  );
}
