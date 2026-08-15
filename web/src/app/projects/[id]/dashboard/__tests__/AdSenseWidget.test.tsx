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
        }}
      />
    );

    expect(screen.getByText("12.34")).toBeInTheDocument();
    expect(screen.getByText("100")).toBeInTheDocument();
    expect(screen.getByText("5,000")).toBeInTheDocument();
    expect(screen.getByText("過去30日間")).toBeInTheDocument();
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
        }}
      />
    );

    expect(screen.getByText(/取得に失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText("過去30日間")).not.toBeInTheDocument();
  });
});
