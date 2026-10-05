import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsXSection } from "../ProjectSnsXSection";
import { startProjectXConnectionAction, testProjectXPostAction } from "../snsXActions";
import type { XConnectionView } from "@/lib/apiClient";

jest.mock("../snsXActions", () => ({
  startProjectXConnectionAction: jest.fn(),
  testProjectXPostAction: jest.fn(),
}));

/**
 * issue #1574: 設定画面の「SNS 告知」欄。接続状態・接続できない理由・テスト投稿・告知履歴を示す。
 * 本番サイトに届かないときは「取得できない」と表示し、画面は壊れない。トークンもクライアントの秘密も再表示しない。
 */
const CALLBACK_URL = "https://localhost/connect/x/callback";

const connectedView: XConnectionView = {
  connectable: true,
  reason: null,
  siteName: "本番サイト",
  status: { available: true, state: "CONNECTED", accountName: "lets_blog", error: null },
  log: {
    available: true,
    error: null,
    entries: [
      { kind: "test", success: true, error: null, at: "2026-10-04T00:00:00+00:00" },
      { kind: "publish", success: false, error: "X の投稿に失敗しました", at: "2026-10-04T01:00:00+00:00" },
    ],
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsXSection>> = {}) {
  return render(
    <ProjectSnsXSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} {...props} />
  );
}

describe("ProjectSnsXSection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (startProjectXConnectionAction as jest.Mock).mockResolvedValue({});
    (testProjectXPostAction as jest.Mock).mockResolvedValue({ success: true });
  });

  it("見出しと、X のアプリに登録するコールバックURLを示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "SNS 告知", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(CALLBACK_URL)).toBeInTheDocument();
  });

  it("接続済みなら接続済みとアカウント名を示し、本番サイト名も示す", () => {
    renderSection();

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("@lets_blog");
    expect(screen.getByText(/本番サイト/)).toBeInTheDocument();
    expect(screen.queryByTestId("sns-x-reason")).not.toBeInTheDocument();
  });

  it("アカウント名が無い接続済みでも接続済みとだけ示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "CONNECTED", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-x-status")).not.toHaveTextContent("@");
  });

  it("未設定なら未接続と示し、テスト投稿はできない", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("未接続");
    expect(screen.getByRole("button", { name: "テスト投稿" })).toBeDisabled();
  });

  it("要再接続ならそう示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "RECONNECT", accountName: "a", error: null } },
    });

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("要再接続");
  });

  it("接続状態を取得できなければ「取得できない」と示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: false, state: null, accountName: null, error: "接続失敗" } },
    });

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("接続失敗");
  });

  it("接続できないときは理由を示し、接続ボタンは押せない", () => {
    renderSection({
      view: { connectable: false, reason: "本番サイトが設定されていません", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-x-reason")).toHaveTextContent("本番サイトが設定されていません");
    expect(screen.getByRole("button", { name: "X と接続" })).toBeDisabled();
    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("未接続");
    expect(screen.getByRole("button", { name: "テスト投稿" })).toBeDisabled();
  });

  it("画面の情報そのものを取得できなければ、状態も履歴も「取得できない」と示して接続は止める", () => {
    renderSection({ view: null });

    expect(screen.getByTestId("sns-x-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-x-log")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "X と接続" })).toBeDisabled();
  });

  it("告知履歴を種類・成功/失敗・理由つきで示す", () => {
    renderSection();

    const entries = screen.getAllByTestId("sns-x-log-entry");
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent("テスト投稿");
    expect(entries[0]).toHaveTextContent("成功");
    expect(entries[1]).toHaveTextContent("記事公開");
    expect(entries[1]).toHaveTextContent("失敗");
    expect(entries[1]).toHaveTextContent("X の投稿に失敗しました");
  });

  it("知らない種類の履歴はそのままの名前で示す", () => {
    renderSection({
      view: {
        ...connectedView,
        log: { available: true, error: null, entries: [{ kind: "milestone", success: true, error: null, at: "a" }] },
      },
    });

    expect(screen.getByTestId("sns-x-log-entry")).toHaveTextContent("milestone");
  });

  it("履歴が空ならまだ無いと示す", () => {
    renderSection({ view: { ...connectedView, log: { available: true, entries: [], error: null } } });

    expect(screen.getByTestId("sns-x-log")).toHaveTextContent("まだありません");
    expect(screen.queryByTestId("sns-x-log-entry")).not.toBeInTheDocument();
  });

  it("履歴を取得できなければ「取得できない」と示す", () => {
    renderSection({ view: { ...connectedView, log: { available: false, entries: [], error: "届かない" } } });

    expect(screen.getByTestId("sns-x-log")).toHaveTextContent("取得できない");
    expect(screen.queryByTestId("sns-x-log-entry")).not.toBeInTheDocument();
  });

  it("接続できる状態でクライアントの情報を入れて押すと、認可の開始アクションへ渡る", async () => {
    const user = userEvent.setup();
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    await user.type(document.querySelector('input[name="clientId"]') as HTMLInputElement, "cid");
    await user.type(document.querySelector('input[name="clientSecret"]') as HTMLInputElement, "csecret");
    await user.click(screen.getByRole("button", { name: "X と接続" }));

    expect(startProjectXConnectionAction).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = (startProjectXConnectionAction as jest.Mock).mock.calls[0];
    expect(projectId).toBe(5);
    expect((formData as FormData).get("clientId")).toBe("cid");
    expect((formData as FormData).get("clientSecret")).toBe("csecret");
  });

  it("クライアントシークレットは伏せ字の入力欄で、保存済みの値を再表示しない", () => {
    renderSection();

    expect(document.querySelector('input[name="clientSecret"]')).toHaveAttribute("type", "password");
    expect(document.querySelector('input[name="clientSecret"]')).toHaveValue("");
    expect(document.querySelector('input[name="clientId"]')).toHaveValue("");
  });

  it("認可の開始に失敗したら理由を示す", async () => {
    (startProjectXConnectionAction as jest.Mock).mockResolvedValue({ error: "本番サイトが設定されていません" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "X と接続" }));

    expect(await screen.findByText("本番サイトが設定されていません")).toBeInTheDocument();
  });

  it("接続済みでテスト投稿を押すと、投稿アクションを呼び成功を示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テスト投稿" }));

    expect(testProjectXPostAction).toHaveBeenCalledWith(5);
    expect(await screen.findByText("テスト投稿を送りました。")).toBeInTheDocument();
  });

  it("テスト投稿が失敗したら理由を示す", async () => {
    (testProjectXPostAction as jest.Mock).mockResolvedValue({ error: "X の投稿に失敗しました(HTTP 403)" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テスト投稿" }));

    expect(await screen.findByText(/HTTP 403/)).toBeInTheDocument();
  });

  it("接続完了と失敗のバナーを示す", () => {
    const { rerender } = renderSection({ connectedBanner: true });
    expect(screen.getByText("X アカウントを接続しました。")).toBeInTheDocument();

    rerender(<ProjectSnsXSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} errorBanner="invalid_state" />);
    expect(screen.getByText(/接続に失敗しました: invalid_state/)).toBeInTheDocument();
    expect(within(document.body).queryByText("X アカウントを接続しました。")).not.toBeInTheDocument();
  });
});
