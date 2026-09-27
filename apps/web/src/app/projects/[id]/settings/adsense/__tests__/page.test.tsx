import { render, screen } from "@testing-library/react";
import ProjectAdSenseSettingsPage from "../page";
import { getProject, getProjectAdSenseStatus, listProjectAdSenseAccounts } from "@/lib/apiClient";
import { notFound } from "next/navigation";

/**
 * issue #1232: AdSense設定ページ(サーバーコンポーネント)。連携済みのときだけアカウント一覧を取得し、
 * 取得に失敗しても画面全体は落とさず理由をフォームへ渡す(手入力で復旧できる)。
 */
jest.mock("@/lib/apiClient", () => ({
  getProject: jest.fn(),
  getProjectAdSenseStatus: jest.fn(),
  listProjectAdSenseAccounts: jest.fn(),
}));
jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn() }));
jest.mock("next/navigation", () => ({
  notFound: jest.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => null }));
const formProps = jest.fn();
jest.mock("../../../ProjectAdSenseSettingsForm", () => ({
  ProjectAdSenseSettingsForm: (props: unknown) => {
    formProps(props);
    return <div data-testid="adsense-form" />;
  },
}));

const status = {
  configured: false,
  accountId: null,
  clientId: "cid",
  hasClientSecret: true,
  connected: false,
};

async function renderPage(searchParams: { connected?: string; error?: string } = {}) {
  const ui = await ProjectAdSenseSettingsPage({
    params: Promise.resolve({ id: "5" }),
    searchParams: Promise.resolve(searchParams),
  });
  render(ui);
}

describe("ProjectAdSenseSettingsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (getProject as jest.Mock).mockResolvedValue({ id: 5, name: "テストプロジェクト" });
    (getProjectAdSenseStatus as jest.Mock).mockResolvedValue(status);
  });

  it("未連携ならアカウント一覧を取得せず、状態をフォームへ渡す", async () => {
    await renderPage();

    expect(screen.getByText("テストプロジェクト — Google AdSense設定")).toBeInTheDocument();
    expect(listProjectAdSenseAccounts).not.toHaveBeenCalled();
    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: 5,
        connected: false,
        configured: false,
        clientId: "cid",
        hasClientSecret: true,
        accounts: [],
      })
    );
  });

  it("連携済みならアカウント一覧を取得してフォームへ渡す", async () => {
    (getProjectAdSenseStatus as jest.Mock).mockResolvedValue({ ...status, connected: true });
    const accounts = [{ accountId: "pub-1", displayName: "A" }];
    (listProjectAdSenseAccounts as jest.Mock).mockResolvedValue(accounts);

    await renderPage({ connected: "1" });

    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({ connected: true, accounts, connectedBanner: true })
    );
  });

  it("アカウント一覧の取得に失敗したら理由をフォームへ渡す(Errorでない例外も文字列化する)", async () => {
    (getProjectAdSenseStatus as jest.Mock).mockResolvedValue({ ...status, connected: true });
    (listProjectAdSenseAccounts as jest.Mock).mockRejectedValueOnce(new Error("拒否されました"));
    await renderPage({ error: "access_denied" });
    expect(formProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ accounts: [], accountsError: "拒否されました", errorBanner: "access_denied" })
    );

    (listProjectAdSenseAccounts as jest.Mock).mockRejectedValueOnce("失敗");
    await renderPage();
    expect(formProps).toHaveBeenLastCalledWith(expect.objectContaining({ accountsError: "失敗" }));
  });

  it("状態を取得できなければ未設定として扱う", async () => {
    (getProjectAdSenseStatus as jest.Mock).mockRejectedValue(new Error("down"));

    await renderPage();

    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({
        connected: false,
        configured: false,
        accountId: null,
        clientId: null,
        hasClientSecret: false,
      })
    );
  });

  it("プロジェクトが無ければ404", async () => {
    (getProject as jest.Mock).mockRejectedValue(new Error("not found"));

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFound).toHaveBeenCalled();
  });
});
