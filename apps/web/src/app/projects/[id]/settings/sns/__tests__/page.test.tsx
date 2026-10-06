import { render, screen } from "@testing-library/react";
import ProjectSnsSettingsPage from "../page";
import {
  getProject,
  getProjectFacebookConnection,
  getProjectFacebookPages,
  getProjectPvRules,
  getProjectSnsTemplates,
  getProjectThreadsConnection,
  getProjectXConnection,
} from "@/lib/apiClient";

/**
 * issue #1574: SNS 告知の設定ページ(サーバーコンポーネント)。接続状態の取得に失敗しても画面全体は落とさず、
 * 欄の側で「取得できない」と示す。
 */
jest.mock("@/lib/apiClient", () => ({
  getProject: jest.fn(),
  getProjectXConnection: jest.fn(),
  getProjectThreadsConnection: jest.fn(),
  getProjectFacebookConnection: jest.fn(),
  getProjectFacebookPages: jest.fn(),
  getProjectPvRules: jest.fn(),
  getProjectSnsTemplates: jest.fn(),
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

const threadsSectionProps = jest.fn();
jest.mock("../../../ProjectSnsThreadsSection", () => ({
  ProjectSnsThreadsSection: (props: unknown) => {
    threadsSectionProps(props);
    return <div data-testid="threads-section" />;
  },
}));

const facebookSectionProps = jest.fn();
jest.mock("../../../ProjectSnsFacebookSection", () => ({
  ProjectSnsFacebookSection: (props: unknown) => {
    facebookSectionProps(props);
    return <div data-testid="facebook-section" />;
  },
}));

const pvSectionProps = jest.fn();
jest.mock("../../../ProjectPvRulesSection", () => ({
  ProjectPvRulesSection: (props: unknown) => {
    pvSectionProps(props);
    return <div data-testid="pv-section" />;
  },
}));

const templatesSectionProps = jest.fn();
jest.mock("../../../ProjectSnsTemplatesSection", () => ({
  ProjectSnsTemplatesSection: (props: unknown) => {
    templatesSectionProps(props);
    return <div data-testid="templates-section" />;
  },
}));

const templatesView = {
  publishTemplate: "【新着】{title} {url}",
  pvTemplate: "",
  send: { state: "SENT", error: null, at: null },
};

const pvView = {
  addable: true,
  reason: null,
  rules: [{ id: "r1", period: "daily", threshold: 100 }],
  send: { state: "SENT", error: null, at: null },
};

const view = { connectable: true, reason: null, siteName: "本番", status: null, log: null };

async function renderPage(searchParams: { connected?: string; error?: string; sns?: string; facebookState?: string } = {}) {
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
    (getProjectThreadsConnection as jest.Mock).mockResolvedValue(view);
    (getProjectFacebookConnection as jest.Mock).mockResolvedValue(view);
    (getProjectPvRules as jest.Mock).mockResolvedValue(pvView);
    (getProjectSnsTemplates as jest.Mock).mockResolvedValue(templatesView);
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

  it("取得した PV 達成ルールを PV 欄へ渡す(issue #1578)", async () => {
    await renderPage();

    expect(screen.getByTestId("pv-section")).toBeInTheDocument();
    expect(pvSectionProps).toHaveBeenCalledWith({ projectId: 5, view: pvView });
  });

  it("PV 達成ルールを取得できなくても画面は描き、PV 欄へはnullを渡す(issue #1578)", async () => {
    (getProjectPvRules as jest.Mock).mockRejectedValue(new Error("502"));

    await renderPage();

    expect(screen.getByTestId("sns-section")).toBeInTheDocument();
    expect(pvSectionProps).toHaveBeenCalledWith({ projectId: 5, view: null });
  });

  it("取得した告知文テンプレートをテンプレート欄へ渡す(issue #1583)", async () => {
    await renderPage();

    expect(screen.getByTestId("templates-section")).toBeInTheDocument();
    expect(templatesSectionProps).toHaveBeenCalledWith({ projectId: 5, view: templatesView });
  });

  it("告知文テンプレートを取得できなくても画面は描き、テンプレート欄へはnullを渡す(issue #1583)", async () => {
    (getProjectSnsTemplates as jest.Mock).mockRejectedValue(new Error("502"));

    await renderPage();

    expect(screen.getByTestId("sns-section")).toBeInTheDocument();
    expect(templatesSectionProps).toHaveBeenCalledWith({ projectId: 5, view: null });
  });

  it("プロジェクトが取得できなければ notFound になる", async () => {
    (getProject as jest.Mock).mockRejectedValue(new Error("404"));

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
  });
  describe("Threads の欄(issue #1579)", () => {
    it("取得した Threads の接続状態と Threads 用のコールバックURLを Threads 欄へ渡す", async () => {
      await renderPage();

      expect(screen.getByTestId("threads-section")).toBeInTheDocument();
      expect(threadsSectionProps).toHaveBeenCalledWith(
        expect.objectContaining({
          projectId: 5,
          view,
          callbackUrl: "https://localhost/connect/threads/callback",
          connectedBanner: false,
          errorBanner: undefined,
        })
      );
    });

    it("Threads の接続完了(connected=threads)は Threads 欄にだけバナーを出す", async () => {
      await renderPage({ connected: "threads" });

      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ connectedBanner: true }));
      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ connectedBanner: false }));
    });

    it("Threads の失敗(sns=threads)は Threads 欄にだけ理由を出し、X 欄には出さない", async () => {
      await renderPage({ error: "access_denied", sns: "threads" });

      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: "access_denied" }));
      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: undefined }));
    });

    it("X の失敗は X 欄にだけ理由を出す", async () => {
      await renderPage({ error: "invalid_state" });

      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: "invalid_state" }));
      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: undefined }));
    });

    it("Threads の接続状態を取得できなくても画面は描き、Threads 欄へはnullを渡す", async () => {
      (getProjectThreadsConnection as jest.Mock).mockRejectedValue(new Error("502"));

      await renderPage();

      expect(screen.getByTestId("sns-section")).toBeInTheDocument();
      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ view: null }));
    });
  });

  describe("Facebook ページの欄(issue #1580)", () => {
    it("取得した Facebook の接続状態と Facebook 用のコールバックURLを Facebook 欄へ渡す", async () => {
      await renderPage();

      expect(screen.getByTestId("facebook-section")).toBeInTheDocument();
      expect(facebookSectionProps).toHaveBeenCalledWith(
        expect.objectContaining({
          projectId: 5,
          view,
          callbackUrl: "https://localhost/connect/facebook/callback",
          connectedBanner: false,
          errorBanner: undefined,
          pageSelection: null,
        })
      );
      expect(getProjectFacebookPages).not.toHaveBeenCalled();
    });

    it("Facebook の接続完了(connected=facebook)は Facebook 欄にだけバナーを出す", async () => {
      await renderPage({ connected: "facebook" });

      expect(facebookSectionProps).toHaveBeenCalledWith(expect.objectContaining({ connectedBanner: true }));
      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ connectedBanner: false }));
      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ connectedBanner: false }));
    });

    it("Facebook の失敗(sns=facebook)は Facebook 欄にだけ理由を出す", async () => {
      await renderPage({ error: "access_denied", sns: "facebook" });

      expect(facebookSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: "access_denied" }));
      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: undefined }));
      expect(threadsSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: undefined }));
    });

    it("X の失敗は Facebook 欄には出さない", async () => {
      await renderPage({ error: "invalid_state" });

      expect(sectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: "invalid_state" }));
      expect(facebookSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: undefined }));
    });

    it("認可から戻った(facebookState)ときは、選べるページを取得して Facebook 欄へ渡す", async () => {
      const pages = { projectId: 5, pages: [{ id: "100", name: "ページA" }] };
      (getProjectFacebookPages as jest.Mock).mockResolvedValue(pages);

      await renderPage({ facebookState: "5.abc" });

      expect(getProjectFacebookPages).toHaveBeenCalledWith(5, "5.abc");
      expect(facebookSectionProps).toHaveBeenCalledWith(
        expect.objectContaining({ pageSelection: { state: "5.abc", pages: pages.pages } })
      );
    });

    it("選べるページを取得できなければ(期限切れ等)、選択欄は出さず理由を Facebook 欄に出す", async () => {
      (getProjectFacebookPages as jest.Mock).mockRejectedValue(new Error("認可の有効期限が切れました"));

      await renderPage({ facebookState: "5.abc" });

      expect(facebookSectionProps).toHaveBeenCalledWith(
        expect.objectContaining({ pageSelection: null, errorBanner: "認可の有効期限が切れました" })
      );
    });

    it("Errorでない失敗も文字列にして Facebook 欄に出す", async () => {
      (getProjectFacebookPages as jest.Mock).mockRejectedValue("boom");

      await renderPage({ facebookState: "5.abc" });

      expect(facebookSectionProps).toHaveBeenCalledWith(expect.objectContaining({ errorBanner: "boom" }));
    });

    it("Facebook の接続状態を取得できなくても画面は描き、Facebook 欄へはnullを渡す", async () => {
      (getProjectFacebookConnection as jest.Mock).mockRejectedValue(new Error("502"));

      await renderPage();

      expect(screen.getByTestId("sns-section")).toBeInTheDocument();
      expect(facebookSectionProps).toHaveBeenCalledWith(expect.objectContaining({ view: null }));
    });
  });
});
