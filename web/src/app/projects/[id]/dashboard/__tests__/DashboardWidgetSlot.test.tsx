import { render, screen } from "@testing-library/react";
import { DashboardWidgetSlot } from "../DashboardWidgetSlot";

describe("DashboardWidgetSlot", () => {
  it("未設定時は説明文と設定ページへのリンクを表示する(issue #385 acceptance criterion 5)", () => {
    render(
      <DashboardWidgetSlot
        title="Google Analytics"
        description="本番サイトのアクセス状況を表示します。"
        configured={false}
        settingsHref="/projects/1/settings/google-analytics"
        settingsLabel="Google Analyticsを設定"
      />
    );

    expect(screen.getByText("Google Analytics")).toBeInTheDocument();
    expect(screen.getByText("本番サイトのアクセス状況を表示します。")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Google Analyticsを設定" });
    expect(link).toHaveAttribute("href", "/projects/1/settings/google-analytics");
  });

  it("settingsHref未指定時はリンクではなくラベルのみ表示する(ソーシャル統計の連携先TBDに対応)", () => {
    render(
      <DashboardWidgetSlot
        title="ソーシャル統計"
        description="連携するSNSアカウントの統計情報を表示します。"
        configured={false}
        settingsLabel="連携方法は検討中です"
      />
    );

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("連携方法は検討中です")).toBeInTheDocument();
  });

  it("configuredがtrueでchildrenがある場合はchildrenを表示し説明文/リンクは表示しない", () => {
    render(
      <DashboardWidgetSlot
        title="Google Analytics"
        description="本番サイトのアクセス状況を表示します。"
        configured
        settingsHref="/projects/1/settings/google-analytics"
        settingsLabel="Google Analyticsを設定"
      >
        <p>セッション数: 1234</p>
      </DashboardWidgetSlot>
    );

    expect(screen.getByText("セッション数: 1234")).toBeInTheDocument();
    expect(screen.queryByText("本番サイトのアクセス状況を表示します。")).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
