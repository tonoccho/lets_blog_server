import { render, screen } from "@testing-library/react";
import { ArticlePlanIssueListError } from "../ArticlePlanIssueListError";

/** issue #1771: Issue一覧の取得失敗の原因を、ウェブ側の固定文だけで示す。 */
describe("ArticlePlanIssueListError", () => {
  it("トークン未設定の固定文を role=alert で出す", () => {
    render(<ArticlePlanIssueListError reason="GITHUB_TOKEN_NOT_CONFIGURED" />);
    expect(screen.getByRole("alert").textContent).toBe(
      "記事計画Issue一覧を取得できませんでした。GitHub トークンが設定されていません。プロジェクト詳細の「GitHub Personal Access Token」か、ご自身のユーザー設定でトークンを設定してください。",
    );
  });

  it("認証失敗の固定文を role=alert で出す", () => {
    render(<ArticlePlanIssueListError reason="GITHUB_AUTH_FAILED" />);
    expect(screen.getByRole("alert").textContent).toBe(
      "記事計画Issue一覧を取得できませんでした。GitHub の認証に失敗しました。トークンが無効、期限切れ、または権限不足の可能性があります。プロジェクト詳細かユーザー設定でトークンを設定し直してください。",
    );
  });
});
