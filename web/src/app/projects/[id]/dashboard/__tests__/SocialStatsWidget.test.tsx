import { render, screen } from "@testing-library/react";
import { SocialStatsWidget } from "../SocialStatsWidget";

describe("SocialStatsWidget", () => {
  it("正常時はいいね・シェア・コメント・クリックと対象投稿数を表示する(issue #390)", () => {
    render(
      <SocialStatsWidget
        stats={{
          eligible: true,
          postCount: 3,
          likes: 10,
          shares: 5,
          comments: 2,
          clicks: 20,
          errorMessage: null,
        }}
      />
    );

    expect(screen.getByText("10")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("20")).toBeInTheDocument();
    expect(screen.getByText(/送信済み投稿3件が対象/)).toBeInTheDocument();
  });

  it("errorMessageがある場合は数値ではなくエラー文言を表示する(issue #390)", () => {
    render(
      <SocialStatsWidget
        stats={{
          eligible: true,
          postCount: null,
          likes: null,
          shares: null,
          comments: null,
          clicks: null,
          errorMessage: "Buffer統計取得に失敗しました: 403",
        }}
      />
    );

    expect(screen.getByText(/取得に失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText(/送信済み投稿/)).not.toBeInTheDocument();
  });
});
