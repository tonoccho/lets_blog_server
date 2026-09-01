import { render, screen } from "@testing-library/react";
import { AdSenseWidget } from "../AdSenseWidget";

describe("AdSenseWidget", () => {
  it("正常時は推定収益・クリック数・表示回数と期間を表示する(issue #387)", () => {
    render(
      <AdSenseWidget
        report={{
          eligible: true,
          estimatedEarnings: "12.34",
          clicks: 100,
          impressions: 5000,
          periodLabel: "過去30日間",
          errorMessage: null,
          dailyDataPoints: [],
          platformBreakdown: [],
        }}
      />
    );

    expect(screen.getByText("12.34")).toBeInTheDocument();
    expect(screen.getByText("100")).toBeInTheDocument();
    expect(screen.getByText("5,000")).toBeInTheDocument();
    expect(screen.getByText("過去30日間")).toBeInTheDocument();
    expect(screen.queryByTestId("adsense-daily-chart")).not.toBeInTheDocument();
    expect(screen.queryByTestId("adsense-platform-breakdown")).not.toBeInTheDocument();
  });

  it("errorMessageがある場合は数値ではなくエラー文言を表示する(issue #387)", () => {
    render(
      <AdSenseWidget
        report={{
          eligible: true,
          estimatedEarnings: null,
          clicks: null,
          impressions: null,
          periodLabel: null,
          errorMessage: "AdSense Management APIの呼び出しに失敗しました: 403",
          dailyDataPoints: [],
          platformBreakdown: [],
        }}
      />
    );

    expect(screen.getByText(/取得に失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText("過去30日間")).not.toBeInTheDocument();
  });

  it("日次データがある場合は時系列グラフを表示する(issue #426)", () => {
    render(
      <AdSenseWidget
        report={{
          eligible: true,
          estimatedEarnings: "12.34",
          clicks: 100,
          impressions: 5000,
          periodLabel: "過去30日間",
          errorMessage: null,
          dailyDataPoints: [
            { date: "2024-01-01", estimatedEarnings: "6.00", clicks: 50, impressions: 2500 },
            { date: "2024-01-02", estimatedEarnings: "6.34", clicks: 50, impressions: 2500 },
          ],
          platformBreakdown: [],
        }}
      />
    );

    expect(screen.getByTestId("adsense-daily-chart")).toBeInTheDocument();
  });

  it("内訳データがある場合はプラットフォーム別の円グラフを表示する(issue #426)", () => {
    render(
      <AdSenseWidget
        report={{
          eligible: true,
          estimatedEarnings: "12.34",
          clicks: 100,
          impressions: 5000,
          periodLabel: "過去30日間",
          errorMessage: null,
          dailyDataPoints: [],
          platformBreakdown: [
            { platform: "Desktop", estimatedEarnings: "8.00", clicks: 60, impressions: 3000 },
            { platform: "Mobile web", estimatedEarnings: "4.34", clicks: 40, impressions: 2000 },
          ],
        }}
      />
    );

    expect(screen.getByTestId("adsense-platform-breakdown")).toBeInTheDocument();
  });
});
