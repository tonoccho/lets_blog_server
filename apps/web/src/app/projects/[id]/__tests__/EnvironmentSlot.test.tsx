import { act, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { EnvironmentSlot } from "../EnvironmentSlot";
import type { Site } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { bodies } from "./projectCoreRuntime";
import { ADMIN_SESSION, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1836)。
jest.mock('server-only', () => ({}))
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule)
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule)
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule)
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule)
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))

const UNBIND = "DELETE /api/projects/1/environments/test";
const BIND = "POST /api/projects/1/environments";

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
    resetServerRuntime(ADMIN_SESSION);
    installFetchByPath({ [UNBIND]: () => jsonResponse({}), [BIND]: () => jsonResponse({}) });
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

  it("切離しボタンを押すと環境の切離しがバックエンドへ送られる", async () => {
    const fetchMock = installFetchByPath({ [UNBIND]: () => jsonResponse({}) });
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
      expect(calledRequests(fetchMock)).toEqual([UNBIND]);
    });
  });

  it("切離しの往復の間は「切離し中…」を示して押せなくする", async () => {
    let release: (r: Response) => void = () => {};
    installFetchByPath({ [UNBIND]: () => new Promise<Response>((resolve) => (release = resolve)) });
    render(<EnvironmentSlot projectId={1} environment="test" site={buildSite()} candidateSites={[]} adminPath="wp-admin" />);

    fireEvent.click(screen.getByRole("button", { name: "切離し" }));

    expect(await screen.findByRole("button", { name: "切離し中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({}));
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "切離し" })).toBeEnabled());
  });

  it("未紐付けでサイトを選んで紐付けると、選んだサイトが環境へ紐付けられる", async () => {
    const fetchMock = installFetchByPath({ [BIND]: () => jsonResponse({}) });
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={null}
        candidateSites={[buildSite({ id: 10, name: "候補サイト", siteKey: "candidate" })]}
        adminPath="wp-admin"
      />
    );

    fireEvent.change(screen.getByLabelText("テスト環境に紐付けるサイト"), { target: { value: "10" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: "紐付ける" }).closest("form") as HTMLFormElement);
    });

    expect(calledRequests(fetchMock)).toEqual([BIND]);
    expect(bodies(fetchMock, BIND)).toEqual([{ environment: "test", siteId: 10 }]);
  });

  it("紐付けの往復の間は「紐付中…」を示し、失敗するとその理由を示す", async () => {
    let release: (r: Response) => void = () => {};
    installFetchByPath({ [BIND]: () => new Promise<Response>((resolve) => (release = resolve)) });
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={null}
        candidateSites={[buildSite({ id: 10, name: "候補サイト", siteKey: "candidate" })]}
        adminPath="wp-admin"
      />
    );
    fireEvent.change(screen.getByLabelText("テスト環境に紐付けるサイト"), { target: { value: "10" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: "紐付ける" }).closest("form") as HTMLFormElement);
    });

    expect(await screen.findByRole("button", { name: "紐付中…" })).toBeDisabled();
    await act(async () => {
      release(textResponse("すでに紐付いています", 409));
    });
    expect(await screen.findByText("APIエラー (409): すでに紐付いています")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "紐付ける" })).toBeEnabled();
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

  it.each([
    ["サイト個別パスあり", "secret-login", "https://example.com/secret-login"],
    ["サイト個別パスが null", null, "https://example.com/wp-admin"],
    ["サイト個別パスが空文字(クリア済み)", "", "https://example.com/wp-admin"],
  ])("管理画面リンクの href: %s(issue #1534)", (_label, siteAdminPath, expected) => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="test"
        site={buildSite({ name: "テストサイト", adminPath: siteAdminPath })}
        candidateSites={[]}
        adminPath="wp-admin"
      />
    );

    expect(screen.getByRole("link", { name: "テストサイト の管理画面を開く" })).toHaveAttribute("href", expected);
  });

  it("未紐付けのスロットにはサイト・管理画面リンクを表示しない(issue #1530)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={null} candidateSites={[]} adminPath="wp-admin" />);

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
