"use client";

import { Cell, Legend, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { GoogleAnalyticsReport } from "@/lib/apiClient";

const CHANNEL_COLORS = ["#2563eb", "#16a34a", "#f59e0b", "#dc2626", "#7c3aed", "#0891b2"];

/**
 * Google Analyticsダッシュボードウィジェットの中身(issue #386)。configured=trueのとき
 * DashboardWidgetSlotのchildrenとして描画される。GA4取得自体に失敗した場合(errorMessageあり)は、
 * ページ全体をエラーにはせずウィジェット内にエラー文言のみ表示する。
 * dailyDataPoints/channelBreakdownがある場合は、日次推移の折れ線グラフとトラフィックソース別内訳の
 * 円グラフを表示する(issue #426)。
 */
export function GoogleAnalyticsWidget({ report }: { report: GoogleAnalyticsReport }) {
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
      {report.dailyDataPoints.length > 0 && (
        <div className="h-48 w-full" data-testid="ga-daily-chart">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={report.dailyDataPoints} margin={{ top: 4, right: 8, left: -24, bottom: 0 }}>
              <XAxis dataKey="date" tick={{ fontSize: 10 }} />
              <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 10 }} />
              <Line type="monotone" dataKey="sessions" name="セッション数" stroke="#2563eb" dot={false} />
              <Line type="monotone" dataKey="activeUsers" name="ユーザー数" stroke="#16a34a" dot={false} />
              <Line type="monotone" dataKey="pageViews" name="ページビュー" stroke="#f59e0b" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      {report.channelBreakdown.length > 0 && (
        <div className="space-y-1" data-testid="ga-channel-breakdown">
          <p className="text-xs text-neutral-500 dark:text-neutral-400">トラフィックソース別内訳(セッション数)</p>
          <div className="h-48 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={report.channelBreakdown} dataKey="sessions" nameKey="channel" outerRadius={60}>
                  {report.channelBreakdown.map((entry, index) => (
                    <Cell key={entry.channel} fill={CHANNEL_COLORS[index % CHANNEL_COLORS.length]} />
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
