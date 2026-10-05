import { render, screen } from "@testing-library/react";
import ProjectSnsSettingsPage from "../page";
import { getProject, getProjectXConnection } from "@/lib/apiClient";

/**
 * issue #1574: SNS 告知の設定ページ(サーバーコンポーネント)。接続状態の取得に失敗しても画面全体は落とさず、
 * 欄の側で「取得できない」と示す。
 */
jest.mock("@/lib/apiClient", () => ({
  getProject: jest.fn(),
  getProjectXConnection: jest.fn(),
}));
jest.mock("@/lib/session", () => ({ requireAdminSession: jest.fn() }));
jest.mock("next/navigation", () => ({
  notFound: jest.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));
jest.mock("@/components/Breadcrumb", () => ({ Breadcrumb: () => null }));
const sectionProps = jest.fn();
jest.mock("../../../ProjectSnsXSection", () => ({
  ProjectSnsXSection: (props: unknown) => {
    sectionProps(props);
    return <div data-testid="sns-section" />;
  },
}));

const view = { connectable: true, reason: null, siteName: "本番", status: null, log: null };

async function renderPage(searchParams: { connected?: string; error?: string } = {}) {
  render(
    await ProjectSnsSettingsPage({
      params: Promise.resolve({ id: "5" }),
      searchParams: Promise.resolve(searchParams),
    })
  );
}

describe("ProjectSnsSettingsPage", () => {
  const originalUrl = process.env.NEXTAUTH_URL;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env.NEXTAUTH_URL = "https://localhost";
    (getProject as jest.Mock).mockResolvedValue({ id: 5, name: "テストプロジェクト" });
    (getProjectXConnection as jest.Mock).mockResolvedValue(view);
  });

  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  it("取得した接続状態とコールバックURLを欄へ渡す", async () => {
    await renderPage({ connected: "1" });

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("テストプロジェクト — SNS 告知設定");
    expect(sectionProps).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: 5,
        view,
        callbackUrl: "https://localhost/connect/x/callback",
        connectedBanner: true,
        errorBanner: undefined,
      })
    );
  });

  it("失敗の理由をバナーとして渡す", async () => {
    await renderPage({ error: "invalid_state" });

    expect(sectionProps).toHaveBeenCalledWith(
      expect.objectContaining({ connectedBanner: false, errorBanner: "invalid_state" })
    );
  });

  it("接続状態を取得できなくても画面は描き、欄へはnullを渡す", async () => {
    (getProjectXConnection as jest.Mock).mockRejectedValue(new Error("502"));

    await renderPage();

    expect(screen.getByTestId("sns-section")).toBeInTheDocument();
    expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ view: null }));
  });

  it("プロジェクトが取得できなければ notFound になる", async () => {
    (getProject as jest.Mock).mockRejectedValue(new Error("404"));

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
  });
});
