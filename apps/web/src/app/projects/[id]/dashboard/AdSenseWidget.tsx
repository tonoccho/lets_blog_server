"use client";

import { Cell, Legend, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { AdSenseReport } from "@/lib/apiClient";

const PLATFORM_COLORS = ["#2563eb", "#16a34a", "#f59e0b", "#dc2626", "#7c3aed", "#0891b2"];

/**
 * Google AdSenseダッシュボードウィジェットの中身(issue #387)。GoogleAnalyticsWidgetと同じ方針で、
 * APIレベルの取得失敗(errorMessageあり)はページ全体をエラーにせずウィジェット内にのみ表示する。
 * dailyDataPoints/platformBreakdownがある場合は、日次推移の折れ線グラフとプラットフォーム別収益内訳の
 * 円グラフを表示する(issue #426)。
 */
export function AdSenseWidget({ report }: { report: AdSenseReport }) {
  if (report.errorMessage) {
    return <p className="text-sm text-red-600">取得に失敗しました: {report.errorMessage}</p>;
  }

  return (
    <div className="space-y-4">
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
      {report.dailyDataPoints.length > 0 && (
        <div className="h-48 w-full" data-testid="adsense-daily-chart">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={report.dailyDataPoints} margin={{ top: 4, right: 8, left: -24, bottom: 0 }}>
              <XAxis dataKey="date" tick={{ fontSize: 10 }} />
              <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 10 }} />
              <Line type="monotone" dataKey="clicks" name="クリック数" stroke="#2563eb" dot={false} />
              <Line type="monotone" dataKey="impressions" name="表示回数" stroke="#f59e0b" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      {report.platformBreakdown.length > 0 && (
        <div className="space-y-1" data-testid="adsense-platform-breakdown">
          <p className="text-xs text-neutral-500 dark:text-neutral-400">収益内訳(プラットフォーム別・クリック数)</p>
          <div className="h-48 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={report.platformBreakdown} dataKey="clicks" nameKey="platform" outerRadius={60}>
                  {report.platformBreakdown.map((entry, index) => (
                    <Cell key={entry.platform} fill={PLATFORM_COLORS[index % PLATFORM_COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 10 }} />
              </PieChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}
    </div>
  );
}
