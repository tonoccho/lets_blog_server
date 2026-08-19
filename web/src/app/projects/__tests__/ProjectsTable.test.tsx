import { render, screen } from "@testing-library/react";
import { ProjectsTable } from "../ProjectsTable";
import type { Project } from "@/lib/apiClient";

function project(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: "サンプルプロジェクト",
    slug: "sample-project",
    localSite: null,
    testSite: null,
    productionSite: null,
    masterEnvironment: "test",
    githubRepository: null,
    cssSelectorPrefix: null,
    defaultNegativePrompt: null,
    defaultQualityPrompt: null,
    defaultGeneratedImageWidth: null,
    defaultGeneratedImageHeight: null,
    defaultArticleImageLongEdgePx: null,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

describe("ProjectsTable", () => {
  it("プロジェクト名クリックでダッシュボードへ遷移するリンクになっている(issue #385)", () => {
    render(<ProjectsTable projects={[project()]} timezone={null} />);

    const nameLink = screen.getByRole("link", { name: "サンプルプロジェクト" });
    expect(nameLink).toHaveAttribute("href", "/projects/1/dashboard");
  });

  it("既存の計画・タグデザイン・詳細リンクは維持される", () => {
    render(<ProjectsTable projects={[project()]} timezone={null} />);

    expect(screen.getByRole("link", { name: "計画" })).toHaveAttribute("href", "/projects/1/plan");
    expect(screen.getByRole("link", { name: "タグデザイン" })).toHaveAttribute("href", "/projects/1/tag-design");
    expect(screen.getByRole("link", { name: "詳細" })).toHaveAttribute("href", "/projects/1");
  });
});
