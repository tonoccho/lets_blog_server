import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsLinkedInSection } from "../ProjectSnsLinkedInSection";
import {
  disconnectProjectLinkedInAction,
  startProjectLinkedInConnectionAction,
  testProjectLinkedInPostAction,
} from "../snsLinkedInActions";
import type { XConnectionView } from "@/lib/apiClient";

jest.mock("../snsLinkedInActions", () => ({
  startProjectLinkedInConnectionAction: jest.fn(),
  testProjectLinkedInPostAction: jest.fn(),
  disconnectProjectLinkedInAction: jest.fn(),
}));

/**
 * issue #1581: 設定画面の LinkedIn 欄。接続・切断・テスト投稿・接続状態・告知履歴(更新できず投稿しなかった理由を含む)を示す。
 * 本番サイトに届かないときは「取得できない」と示し、Client Secretやトークンは再表示しない。
 */
const CALLBACK_URL = "https://localhost/connect/linkedin/callback";

const connectedView: XConnectionView = {
  connectable: true,
  reason: null,
  siteName: "本番サイト",
  status: { available: true, state: "CONNECTED", accountName: "Let's Blog E2E", error: null },
  log: {
    available: true,
    error: null,
    entries: [
      { kind: "test", success: true, error: null, at: "2026-10-04T00:00:00+00:00" },
      {
        kind: "publish",
        success: false,
        error: "LinkedIn のトークンを更新できません(期限切れのため LinkedIn の再接続が必要です)",
        at: "2026-10-04T01:00:00+00:00",
      },
    ],
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsLinkedInSection>> = {}) {
  return render(
    <ProjectSnsLinkedInSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} {...props} />
  );
}

describe("ProjectSnsLinkedInSection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (startProjectLinkedInConnectionAction as jest.Mock).mockResolvedValue({});
    (testProjectLinkedInPostAction as jest.Mock).mockResolvedValue({ success: true });
    (disconnectProjectLinkedInAction as jest.Mock).mockResolvedValue({ success: true });
  });

  it("見出しと、LinkedIn のアプリに登録するリダイレクトURLを示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "LinkedIn", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(CALLBACK_URL)).toBeInTheDocument();
  });

  it("接続済みなら接続済みとアカウント名を示し、本番サイト名も示す", () => {
    renderSection();

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("Let's Blog E2E");
    expect(screen.getByText(/本番サイト/)).toBeInTheDocument();
    expect(screen.queryByTestId("sns-linkedin-reason")).not.toBeInTheDocument();
  });

  it("アカウント名が無い接続済みでも接続済みとだけ示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "CONNECTED", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-linkedin-status")).not.toHaveTextContent("(");
  });

  it("未設定なら未接続と示し、テスト投稿と切断はできない", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("未接続");
    expect(screen.getByRole("button", { name: "LinkedIn 投稿テスト" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "LinkedIn を切断" })).toBeDisabled();
  });

  it("要再接続ならそう示し、切断はできる", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "RECONNECT", accountName: "a", error: null } },
    });

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("要再接続");
    expect(screen.getByRole("button", { name: "LinkedIn を切断" })).toBeEnabled();
  });

  it("接続状態を取得できなければ「取得できない」と示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: false, state: null, accountName: null, error: "接続失敗" } },
    });

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("接続失敗");
  });

  it("接続できないときは理由を示し、接続ボタンは押せない", () => {
    renderSection({
      view: { connectable: false, reason: "本番サイトが設定されていません", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-linkedin-reason")).toHaveTextContent("本番サイトが設定されていません");
    expect(screen.getByRole("button", { name: "LinkedIn と接続" })).toBeDisabled();
    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("未接続");
  });

  it("画面の情報そのものを取得できなければ、状態も履歴も「取得できない」と示して接続は止める", () => {
    renderSection({ view: null });

    expect(screen.getByTestId("sns-linkedin-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-linkedin-log")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "LinkedIn と接続" })).toBeDisabled();
  });

  it("告知履歴を種類・成功/失敗・理由つきで示す(更新できず投稿しなかった理由も)", () => {
    renderSection();

    const entries = screen.getAllByTestId("sns-linkedin-log-entry");
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent("テスト投稿");
    expect(entries[0]).toHaveTextContent("成功");
    expect(entries[1]).toHaveTextContent("記事公開");
    expect(entries[1]).toHaveTextContent("失敗");
    expect(entries[1]).toHaveTextContent("期限切れのため LinkedIn の再接続が必要です");
  });

  it("知らない種類の履歴はそのままの名前で示し、失敗の理由が無ければ失敗とだけ示す", () => {
    renderSection({
      view: {
        ...connectedView,
        log: { available: true, error: null, entries: [{ kind: "milestone", success: false, error: null, at: "a" }] },
      },
    });

    const entry = screen.getByTestId("sns-linkedin-log-entry");
    expect(entry).toHaveTextContent("milestone");
    expect(entry).toHaveTextContent("失敗");
    expect(entry).not.toHaveTextContent("失敗:");
  });

  it("履歴が空ならまだ無いと示す", () => {
    renderSection({ view: { ...connectedView, log: { available: true, entries: [], error: null } } });

    expect(screen.getByTestId("sns-linkedin-log")).toHaveTextContent("まだありません");
  });

  it("履歴を取得できなければ「取得できない」と示す", () => {
    renderSection({ view: { ...connectedView, log: { available: false, entries: [], error: "届かない" } } });

    expect(screen.getByTestId("sns-linkedin-log")).toHaveTextContent("取得できない");
    expect(screen.queryByTestId("sns-linkedin-log-entry")).not.toBeInTheDocument();
  });

  it("履歴が null(接続できない状態)なら履歴は空として示す", () => {
    renderSection({
      view: { connectable: false, reason: "未導入", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-linkedin-log")).toHaveTextContent("まだありません");
  });

  it("アプリの情報を入れて押すと、認可の開始アクションへ渡る", async () => {
    const user = userEvent.setup();
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    await user.type(document.querySelector('input[name="linkedinAppId"]') as HTMLInputElement, "app-id");
    await user.type(document.querySelector('input[name="linkedinAppSecret"]') as HTMLInputElement, "app-secret");
    await user.click(screen.getByRole("button", { name: "LinkedIn と接続" }));

    expect(startProjectLinkedInConnectionAction).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = (startProjectLinkedInConnectionAction as jest.Mock).mock.calls[0];
    expect(projectId).toBe(5);
    expect((formData as FormData).get("linkedinAppId")).toBe("app-id");
    expect((formData as FormData).get("linkedinAppSecret")).toBe("app-secret");
  });

  it("Client Secretは伏せ字の入力欄で、保存済みの値を再表示しない", () => {
    renderSection();

    expect(document.querySelector('input[name="linkedinAppSecret"]')).toHaveAttribute("type", "password");
    expect(document.querySelector('input[name="linkedinAppSecret"]')).toHaveValue("");
    expect(document.querySelector('input[name="linkedinAppId"]')).toHaveValue("");
  });

  it("認可の開始に失敗したら理由を示す", async () => {
    (startProjectLinkedInConnectionAction as jest.Mock).mockResolvedValue({ error: "本番サイトが設定されていません" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "LinkedIn と接続" }));

    expect(await screen.findByText("本番サイトが設定されていません")).toBeInTheDocument();
  });

  it("接続済みでテスト投稿を押すと、投稿アクションを呼び成功を示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "LinkedIn 投稿テスト" }));

    expect(testProjectLinkedInPostAction).toHaveBeenCalledWith(5);
    expect(await screen.findByText("LinkedIn へ投稿テストを送りました。")).toBeInTheDocument();
  });

  it("テスト投稿が失敗したら理由を示す", async () => {
    (testProjectLinkedInPostAction as jest.Mock).mockResolvedValue({ error: "LinkedIn の投稿に失敗しました(HTTP 403)" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "LinkedIn 投稿テスト" }));

    expect(await screen.findByText(/HTTP 403/)).toBeInTheDocument();
  });

  it("接続済みで切断を押すと、切断アクションを呼び切断したことを示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "LinkedIn を切断" }));

    expect(disconnectProjectLinkedInAction).toHaveBeenCalledWith(5);
    expect(await screen.findByText("LinkedIn の接続を切断しました。")).toBeInTheDocument();
  });

  it("切断に失敗したら理由を示す", async () => {
    (disconnectProjectLinkedInAction as jest.Mock).mockResolvedValue({ error: "本番サイトに届かない" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "LinkedIn を切断" }));

    expect(await screen.findByText("本番サイトに届かない")).toBeInTheDocument();
  });

  it("接続完了と失敗のバナーを示す", () => {
    const { rerender } = renderSection({ connectedBanner: true });
    expect(screen.getByText("LinkedIn アカウントを接続しました。")).toBeInTheDocument();

    rerender(
      <ProjectSnsLinkedInSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} errorBanner="invalid_state" />
    );
    expect(screen.getByText(/接続に失敗しました: invalid_state/)).toBeInTheDocument();
    expect(screen.queryByText("LinkedIn アカウントを接続しました。")).not.toBeInTheDocument();
  });
});
