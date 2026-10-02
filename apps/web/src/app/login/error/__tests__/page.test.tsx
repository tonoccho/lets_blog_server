import { render, screen } from "@testing-library/react";
import LoginErrorPage from "../page";

/**
 * issue #1392: NextAuthのsignIn()がクライアント側fetch失敗等で/api/auth/errorへ進むと、
 * 既定のエラーページで行き止まりになっていた。pages.error(auth.ts)でこのページへ差し替え、
 * 追加操作なしで /login へ戻れる導線(もう一度ログインする)を必ず出す。
 */
describe("LoginErrorPage(issue #1392)", () => {
  it("エラーの旨と「もう一度ログインする」リンクを表示する", () => {
    render(<LoginErrorPage />);

    expect(screen.getByRole("heading", { name: "ログインに失敗しました" })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "もう一度ログインする" });
    expect(link).toHaveAttribute("href", "/login");
    expect(link).toHaveAttribute("data-testid", "login-retry-link");
  });
});
