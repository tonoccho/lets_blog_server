import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { EnvironmentSlot } from "../EnvironmentSlot";
import type { Site } from "@/lib/apiClient";

const unbindEnvironmentAction = jest.fn();

jest.mock("../actions", () => ({
  bindEnvironmentAction: jest.fn(),
  unbindEnvironmentAction: (...args: unknown[]) => unbindEnvironmentAction(...args),
}));

function buildSite(overrides: Partial<Site> = {}): Site {
  return {
    id: 10,
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

describe("EnvironmentSlot", () => {
  beforeEach(() => {
    unbindEnvironmentAction.mockReset().mockResolvedValue(undefined);
  });

  it("未紐付け(site=null)のとき、サイト選択のselectと候補一覧を表示する(issue #944: aria-label付き)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="local"
        site={null}
        candidateSites={[buildSite({ id: 10, name: "候補サイト", siteKey: "candidate" })]}
        adminPath="wp-admin"
      />
    );

    const select = screen.getByLabelText("ローカル環境に紐付けるサイト");
    expect(select).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "候補サイト (candidate)" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "切離し" })).not.toBeInTheDocument();
  });

  it("未紐付け(site=null)のとき、空欄ではなく「未設定」と明示する(issue #1500)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={null} candidateSites={[]} adminPath="wp-admin" />);

    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("紐付け済みのとき「未設定」は表示しない(issue #1500)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={buildSite()} candidateSites={[]} adminPath="wp-admin" />);

    expect(screen.queryByText("未設定")).not.toBeInTheDocument();
  });

  it("紐付け済み(siteあり)のとき、サイト情報と切離しボタンを表示する", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="production"
        site={buildSite({ name: "本番サイト", siteKey: "prod" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    expect(screen.getByText("prod")).toBeInTheDocument();
    expect(screen.getByText("本番サイト")).toBeInTheDocument();
    expect(screen.queryByLabelText("本番環境に紐付けるサイト")).not.toBeInTheDocument();
  });

  it("切離しボタンを押すとunbindEnvironmentActionが呼ばれる", async () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ siteKey: "test-site" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "切離し" }));

    await waitFor(() => {
      expect(unbindEnvironmentAction).toHaveBeenCalledWith(1, "test");
    });
  });

  it("紐付け済みのとき、公開URLを文字列として表示しない(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    expect(screen.queryByText("https://example.com")).not.toBeInTheDocument();
  });

  it("サイトを開くリンクは公開URLを新しいタブで開き、アイコンは支援技術から隠す(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    const link = screen.getByRole("link", { name: "テストサイト のサイトを開く" });
    expect(link).toHaveAttribute("href", "https://example.com");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
    expect(link.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("両アイコンリンクに遷移先 URL を title として付け、フォーカスリングのクラスを付ける(issue #1531)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    const site = screen.getByRole("link", { name: "テストサイト のサイトを開く" });
    const admin = screen.getByRole("link", { name: "テストサイト の管理画面を開く" });
    expect(site).toHaveAttribute("title", "https://example.com");
    expect(admin).toHaveAttribute("title", "https://example.com/wp-admin");
    for (const link of [site, admin]) {
      expect(link).toHaveClass(
        "focus-visible:outline-2",
        "focus-visible:outline-offset-2",
        "focus-visible:outline-blue-500"
      );
    }
  });

  it("管理画面を開くリンクは <公開URL>/<管理画面パス> を新しいタブで開く(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    const link = screen.getByRole("link", { name: "テストサイト の管理画面を開く" });
    expect(link).toHaveAttribute("href", "https://example.com/wp-admin");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
    expect(link.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("管理画面パスを変えると管理画面リンクの href に反映される(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="secret-admin"
      />
    );

    expect(screen.getByRole("link", { name: "テストサイト の管理画面を開く" })).toHaveAttribute(
      "href",
      "https://example.com/secret-admin"
    );
  });

  it("adminPath を省略したとき、既定の wp-admin で管理画面リンクを描画する(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
      />
    );

    expect(screen.getByRole("link", { name: "テストサイト の管理画面を開く" })).toHaveAttribute(
      "href",
      "https://example.com/wp-admin"
    );
  });

  it("管理画面URLが解決できないとき、管理画面リンクは描画しない(issue #1530)", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", baseUrl: "https://example.com" })}
        candidateSites={[]}
        adminPath="https://evil.example.org/wp-admin"
      />
    );

    expect(screen.getByRole("link", { name: "テストサイト のサイトを開く" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "テストサイト の管理画面を開く" })).not.toBeInTheDocument();
  });

  it("未紐付けのスロットにはサイト・管理画面リンクを表示しない(issue #1530)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={null} candidateSites={[]} adminPath="wp-admin" />);

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
