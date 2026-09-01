import { render, screen } from "@testing-library/react";
import { GoogleAnalyticsWidget } from "../GoogleAnalyticsWidget";

describe("GoogleAnalyticsWidget", () => {
  it("正常時はセッション数・ユーザー数・ページビューと期間を表示する(issue #386)", () => {
    render(
      <GoogleAnalyticsWidget
        report={{
          eligible: true,
          sessions: 1234,
          activeUsers: 567,
          pageViews: 8901,
          periodLabel: "過去28日間",
          errorMessage: null,
          dailyDataPoints: [],
          channelBreakdown: [],
        }}
      />
    );

    expect(screen.getByText("1,234")).toBeInTheDocument();
    expect(screen.getByText("567")).toBeInTheDocument();
    expect(screen.getByText("8,901")).toBeInTheDocument();
    expect(screen.getByText("過去28日間")).toBeInTheDocument();
    expect(screen.queryByTestId("ga-daily-chart")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ga-channel-breakdown")).not.toBeInTheDocument();
  });

  it("errorMessageがある場合は数値ではなくエラー文言を表示する(issue #386)", () => {
    render(
      <GoogleAnalyticsWidget
        report={{
          eligible: true,
          sessions: null,
          activeUsers: null,
          pageViews: null,
          periodLabel: null,
          errorMessage: "Google Analytics Data APIの呼び出しに失敗しました: 403",
          dailyDataPoints: [],
          channelBreakdown: [],
        }}
      />
    );

    expect(screen.getByText(/取得に失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText("過去28日間")).not.toBeInTheDocument();
  });

  it("日次データがある場合は時系列グラフを表示する(issue #426)", () => {
    render(
      <GoogleAnalyticsWidget
        report={{
          eligible: true,
          sessions: 1234,
          activeUsers: 567,
          pageViews: 8901,
          periodLabel: "過去28日間",
          errorMessage: null,
          dailyDataPoints: [
            { date: "2024-01-01", sessions: 100, activeUsers: 50, pageViews: 200 },
            { date: "2024-01-02", sessions: 110, activeUsers: 55, pageViews: 210 },
          ],
          channelBreakdown: [],
        }}
      />
    );

    expect(screen.getByTestId("ga-daily-chart")).toBeInTheDocument();
  });

  it("内訳データがある場合はトラフィックソース別の円グラフを表示する(issue #426)", () => {
    render(
      <GoogleAnalyticsWidget
        report={{
          eligible: true,
          sessions: 1234,
          activeUsers: 567,
          pageViews: 8901,
          periodLabel: "過去28日間",
          errorMessage: null,
          dailyDataPoints: [],
          channelBreakdown: [
            { channel: "Organic Search", sessions: 800, activeUsers: 400, pageViews: 1600 },
            { channel: "Direct", sessions: 434, activeUsers: 167, pageViews: 7301 },
          ],
        }}
      />
    );

    expect(screen.getByTestId("ga-channel-breakdown")).toBeInTheDocument();
  });
});
