import { render, screen, fireEvent } from "@testing-library/react";
import { ProjectsTable } from "../ProjectsTable";
import type { Project, Site } from "@/lib/apiClient";

function site(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    name: "サイト",
    siteKey: "site-key",
    cmsType: "WORDPRESS",
    baseUrl: "https://example.com",
    createdAt: "",
    updatedAt: "",
    connectionCheckStatus: null,
    managedWordpress: true,
    sshConfigured: false,
    ...overrides,
  } as Site;
}

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

  it("環境が紐付いていれば、それぞれの環境バッジに色が付く(issue #944: 未紐付け側の配色調整)", () => {
    render(
      <ProjectsTable
        projects={[
          project({
            localSite: site({ siteKey: "local" }),
            testSite: site({ siteKey: "test" }),
            productionSite: site({ siteKey: "prod" }),
          }),
        ]}
        timezone={null}
      />
    );

    expect(screen.getByText("local")).toBeInTheDocument();
    expect(screen.getByText("test")).toBeInTheDocument();
    expect(screen.getByText("production")).toBeInTheDocument();
  });

  it("プロジェクトが1件も無ければ空状態のメッセージを表示する", () => {
    render(<ProjectsTable projects={[]} timezone={null} />);
    expect(screen.getByText("登録済みプロジェクトはありません")).toBeInTheDocument();
  });

  it("列見出しクリックで並び替え、もう一度押すと昇順/降順が切り替わる", () => {
    render(
      <ProjectsTable
        projects={[project({ id: 1, name: "B" }), project({ id: 2, name: "A" })]}
        timezone={null}
      />
    );

    const nameHeader = screen.getByText("名前").closest("th") as HTMLElement;
    fireEvent.click(nameHeader);
    fireEvent.click(nameHeader);

    const createdAtHeader = screen.getByText("作成日").closest("th") as HTMLElement;
    fireEvent.click(createdAtHeader);

    expect(screen.getAllByRole("link", { name: /^[AB]$/ }).length).toBe(2);
  });
});
