import { render, screen } from "@testing-library/react";
import { ProjectSectionNav } from "../ProjectSectionNav";

describe("ProjectSectionNav", () => {
  it("ダッシュボードタブが追加されている(issue #385)", () => {
    render(<ProjectSectionNav projectId={1} active="dashboard" />);

    const dashboardLink = screen.getByRole("link", { name: "ダッシュボード" });
    expect(dashboardLink).toHaveAttribute("href", "/projects/1/dashboard");
    expect(dashboardLink).toHaveAttribute("aria-current", "page");
  });

  it("既存のセクション(詳細・計画・タグ・投稿履歴)は維持される", () => {
    render(<ProjectSectionNav projectId={1} active="detail" />);

    expect(screen.getByRole("link", { name: "詳細" })).toHaveAttribute("href", "/projects/1");
    expect(screen.getByRole("link", { name: "計画" })).toHaveAttribute("href", "/projects/1/plan");
    expect(screen.getByRole("link", { name: "タグ" })).toHaveAttribute("href", "/projects/1/tags");
    expect(screen.getByRole("link", { name: "投稿履歴" })).toHaveAttribute("href", "/projects/1/posts");
  });

  it("activeでないタブにはaria-currentが付与されない", () => {
    render(<ProjectSectionNav projectId={1} active="dashboard" />);

    expect(screen.getByRole("link", { name: "詳細" })).not.toHaveAttribute("aria-current");
  });
});
