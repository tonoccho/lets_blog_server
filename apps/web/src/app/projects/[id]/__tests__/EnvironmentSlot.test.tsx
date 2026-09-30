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
      />
    );

    const select = screen.getByLabelText("ローカル環境に紐付けるサイト");
    expect(select).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "候補サイト (candidate)" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "切離し" })).not.toBeInTheDocument();
  });

  it("未紐付け(site=null)のとき、空欄ではなく「未設定」と明示する(issue #1500)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={null} candidateSites={[]} />);

    expect(screen.getByText("未設定")).toBeInTheDocument();
  });

  it("紐付け済みのとき「未設定」は表示しない(issue #1500)", () => {
    render(<EnvironmentSlot projectId={1} environment="test" site={buildSite()} candidateSites={[]} />);

    expect(screen.queryByText("未設定")).not.toBeInTheDocument();
  });

  it("紐付け済み(siteあり)のとき、サイト情報と切離しボタンを表示する", () => {
    render(
      <EnvironmentSlot
        projectId={1}
        environment="production"
        site={buildSite({ name: "本番サイト", siteKey: "prod" })}
        candidateSites={[]}
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
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "切離し" }));

    await waitFor(() => {
      expect(unbindEnvironmentAction).toHaveBeenCalledWith(1, "test");
    });
  });
});
