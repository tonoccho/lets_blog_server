import { render, screen } from "@testing-library/react";
import ProjectGoogleAnalyticsSettingsPage from "../page";
import {
  getProject,
  getProjectGoogleAnalyticsStatus,
  listProjectGoogleAnalyticsProperties,
} from "@/lib/apiClient";
import { notFound } from "next/navigation";

/**
 * issue #1231: GA設定ページ(サーバーコンポーネント)。連携済みのときだけプロパティ一覧を取得し、
 * 取得に失敗しても画面全体は落とさず理由を出す。
 */
jest.mock("@/lib/apiClient", () => ({
  getProject: jest.fn(),
  getProjectGoogleAnalyticsStatus: jest.fn(),
  listProjectGoogleAnalyticsProperties: jest.fn(),
}));
jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn() }));
jest.mock("next/navigation", () => ({
  notFound: jest.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => null }));
const formProps = jest.fn();
jest.mock("../../../ProjectGoogleAnalyticsSettingsForm", () => ({
  ProjectGoogleAnalyticsSettingsForm: (props: unknown) => {
    formProps(props);
    return <div data-testid="ga-form" />;
  },
}));

const status = {
  configured: false,
  propertyId: null,
  clientId: "cid",
  hasClientSecret: true,
  connected: false,
};

async function renderPage(searchParams: { connected?: string; error?: string } = {}) {
  const ui = await ProjectGoogleAnalyticsSettingsPage({
    params: Promise.resolve({ id: "5" }),
    searchParams: Promise.resolve(searchParams),
  });
  render(ui);
}

describe("ProjectGoogleAnalyticsSettingsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (getProject as jest.Mock).mockResolvedValue({ id: 5, name: "テストプロジェクト" });
    (getProjectGoogleAnalyticsStatus as jest.Mock).mockResolvedValue(status);
  });

  it("未連携ならプロパティ一覧を取得せず、状態をフォームへ渡す", async () => {
    await renderPage();

    expect(screen.getByText("テストプロジェクト — Google Analytics設定")).toBeInTheDocument();
    expect(listProjectGoogleAnalyticsProperties).not.toHaveBeenCalled();
    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 5, connected: false, clientId: "cid", hasClientSecret: true, properties: [] })
    );
  });

  it("連携済みならプロパティ一覧を取得してフォームへ渡す", async () => {
    (getProjectGoogleAnalyticsStatus as jest.Mock).mockResolvedValue({ ...status, connected: true });
    const properties = [{ propertyId: "1", displayName: "A", accountDisplayName: "Acc" }];
    (listProjectGoogleAnalyticsProperties as jest.Mock).mockResolvedValue(properties);

    await renderPage({ connected: "1" });

    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({ connected: true, properties, connectedBanner: true })
    );
  });

  it("プロパティ一覧の取得に失敗したら理由をフォームへ渡す(Errorでない例外も文字列化する)", async () => {
    (getProjectGoogleAnalyticsStatus as jest.Mock).mockResolvedValue({ ...status, connected: true });
    (listProjectGoogleAnalyticsProperties as jest.Mock).mockRejectedValueOnce(new Error("拒否されました"));
    await renderPage({ error: "access_denied" });
    expect(formProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ properties: [], propertiesError: "拒否されました", errorBanner: "access_denied" })
    );

    (listProjectGoogleAnalyticsProperties as jest.Mock).mockRejectedValueOnce("失敗");
    await renderPage();
    expect(formProps).toHaveBeenLastCalledWith(expect.objectContaining({ propertiesError: "失敗" }));
  });

  it("状態を取得できなければ未設定として扱う", async () => {
    (getProjectGoogleAnalyticsStatus as jest.Mock).mockRejectedValue(new Error("down"));

    await renderPage();

    expect(formProps).toHaveBeenCalledWith(
      expect.objectContaining({ connected: false, propertyId: null, clientId: null, hasClientSecret: false })
    );
  });

  it("プロジェクトが無ければ404", async () => {
    (getProject as jest.Mock).mockRejectedValue(new Error("not found"));

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFound).toHaveBeenCalled();
  });
});
