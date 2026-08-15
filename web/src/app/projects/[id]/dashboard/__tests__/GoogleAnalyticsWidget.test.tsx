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
        }}
      />
    );

    expect(screen.getByText("1,234")).toBeInTheDocument();
    expect(screen.getByText("567")).toBeInTheDocument();
    expect(screen.getByText("8,901")).toBeInTheDocument();
    expect(screen.getByText("過去28日間")).toBeInTheDocument();
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
        }}
      />
    );

    expect(screen.getByText(/取得に失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText("過去28日間")).not.toBeInTheDocument();
  });
});
