import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsFacebookSection } from "../ProjectSnsFacebookSection";
import {
  disconnectProjectFacebookAction,
  startProjectFacebookConnectionAction,
  testProjectFacebookPostAction,
  selectProjectFacebookPageAction,
} from "../snsFacebookActions";
import type { XConnectionView } from "@/lib/apiClient";

jest.mock("../snsFacebookActions", () => ({
  startProjectFacebookConnectionAction: jest.fn(),
  selectProjectFacebookPageAction: jest.fn(),
  testProjectFacebookPostAction: jest.fn(),
  disconnectProjectFacebookAction: jest.fn(),
}));

/**
 * issue #1580: 設定画面の Facebook 欄。接続・切断・テスト投稿・接続状態・告知履歴(更新できず投稿しなかった理由を含む)を示す。
 * 本番サイトに届かないときは「取得できない」と示し、アプリシークレットやトークンは再表示しない。
 */
const CALLBACK_URL = "https://localhost/connect/facebook/callback";

const connectedView: XConnectionView = {
  connectable: true,
  reason: null,
  siteName: "本番サイト",
  status: { available: true, state: "CONNECTED", accountName: "公式ページ", error: null },
  log: {
    available: true,
    error: null,
    entries: [
      { kind: "test", success: true, error: null, at: "2026-10-04T00:00:00+00:00" },
      {
        kind: "publish",
        success: false,
        error: "投稿先の Facebook ページが選ばれていません",
        at: "2026-10-04T01:00:00+00:00",
      },
    ],
  },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsFacebookSection>> = {}) {
  return render(
    <ProjectSnsFacebookSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} {...props} />
  );
}

describe("ProjectSnsFacebookSection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (startProjectFacebookConnectionAction as jest.Mock).mockResolvedValue({});
    (testProjectFacebookPostAction as jest.Mock).mockResolvedValue({ success: true });
    (disconnectProjectFacebookAction as jest.Mock).mockResolvedValue({ success: true });
  });

  it("見出しと、Facebook のアプリに登録するリダイレクトURLを示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "Facebook", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(CALLBACK_URL)).toBeInTheDocument();
  });

  it("接続済みなら接続済みとアカウント名を示し、本番サイト名も示す", () => {
    renderSection();

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("公式ページ");
    expect(screen.getByTestId("sns-facebook-status")).not.toHaveTextContent("@");
    expect(screen.getByText(/本番サイト/)).toBeInTheDocument();
    expect(screen.queryByTestId("sns-facebook-reason")).not.toBeInTheDocument();
  });

  it("アカウント名が無い接続済みでも接続済みとだけ示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "CONNECTED", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("接続済み");
    expect(screen.getByTestId("sns-facebook-status")).not.toHaveTextContent("@");
  });

  it("未設定なら未接続と示し、テスト投稿と切断はできない", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("未接続");
    expect(screen.getByRole("button", { name: "Facebook 投稿テスト" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Facebook を切断" })).toBeDisabled();
  });

  it("要再接続ならそう示し、切断はできる", () => {
    renderSection({
      view: { ...connectedView, status: { available: true, state: "RECONNECT", accountName: "a", error: null } },
    });

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("要再接続");
    expect(screen.getByRole("button", { name: "Facebook を切断" })).toBeEnabled();
  });

  it("接続状態を取得できなければ「取得できない」と示す", () => {
    renderSection({
      view: { ...connectedView, status: { available: false, state: null, accountName: null, error: "接続失敗" } },
    });

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("接続失敗");
  });

  it("接続できないときは理由を示し、接続ボタンは押せない", () => {
    renderSection({
      view: { connectable: false, reason: "本番サイトが設定されていません", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-facebook-reason")).toHaveTextContent("本番サイトが設定されていません");
    expect(screen.getByRole("button", { name: "Facebook と接続" })).toBeDisabled();
    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("未接続");
  });

  it("画面の情報そのものを取得できなければ、状態も履歴も「取得できない」と示して接続は止める", () => {
    renderSection({ view: null });

    expect(screen.getByTestId("sns-facebook-status")).toHaveTextContent("取得できない");
    expect(screen.getByTestId("sns-facebook-log")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "Facebook と接続" })).toBeDisabled();
  });

  it("告知履歴を種類・成功/失敗・理由つきで示す(更新できず投稿しなかった理由も)", () => {
    renderSection();

    const entries = screen.getAllByTestId("sns-facebook-log-entry");
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent("テスト投稿");
    expect(entries[0]).toHaveTextContent("成功");
    expect(entries[1]).toHaveTextContent("記事公開");
    expect(entries[1]).toHaveTextContent("失敗");
    expect(entries[1]).toHaveTextContent("ページが選ばれていません");
  });

  it("知らない種類の履歴はそのままの名前で示し、失敗の理由が無ければ失敗とだけ示す", () => {
    renderSection({
      view: {
        ...connectedView,
        log: { available: true, error: null, entries: [{ kind: "milestone", success: false, error: null, at: "a" }] },
      },
    });

    const entry = screen.getByTestId("sns-facebook-log-entry");
    expect(entry).toHaveTextContent("milestone");
    expect(entry).toHaveTextContent("失敗");
    expect(entry).not.toHaveTextContent("失敗:");
  });

  it("履歴が空ならまだ無いと示す", () => {
    renderSection({ view: { ...connectedView, log: { available: true, entries: [], error: null } } });

    expect(screen.getByTestId("sns-facebook-log")).toHaveTextContent("まだありません");
  });

  it("履歴を取得できなければ「取得できない」と示す", () => {
    renderSection({ view: { ...connectedView, log: { available: false, entries: [], error: "届かない" } } });

    expect(screen.getByTestId("sns-facebook-log")).toHaveTextContent("取得できない");
    expect(screen.queryByTestId("sns-facebook-log-entry")).not.toBeInTheDocument();
  });

  it("履歴が null(接続できない状態)なら履歴は空として示す", () => {
    renderSection({
      view: { connectable: false, reason: "未導入", siteName: null, status: null, log: null },
    });

    expect(screen.getByTestId("sns-facebook-log")).toHaveTextContent("まだありません");
  });

  it("アプリの情報を入れて押すと、認可の開始アクションへ渡る", async () => {
    const user = userEvent.setup();
    renderSection({
      view: { ...connectedView, status: { available: true, state: "UNSET", accountName: null, error: null } },
    });

    await user.type(document.querySelector('input[name="facebookAppId"]') as HTMLInputElement, "app-id");
    await user.type(document.querySelector('input[name="facebookAppSecret"]') as HTMLInputElement, "app-secret");
    await user.click(screen.getByRole("button", { name: "Facebook と接続" }));

    expect(startProjectFacebookConnectionAction).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = (startProjectFacebookConnectionAction as jest.Mock).mock.calls[0];
    expect(projectId).toBe(5);
    expect((formData as FormData).get("facebookAppId")).toBe("app-id");
    expect((formData as FormData).get("facebookAppSecret")).toBe("app-secret");
  });

  it("アプリシークレットは伏せ字の入力欄で、保存済みの値を再表示しない", () => {
    renderSection();

    expect(document.querySelector('input[name="facebookAppSecret"]')).toHaveAttribute("type", "password");
    expect(document.querySelector('input[name="facebookAppSecret"]')).toHaveValue("");
    expect(document.querySelector('input[name="facebookAppId"]')).toHaveValue("");
  });

  it("認可の開始に失敗したら理由を示す", async () => {
    (startProjectFacebookConnectionAction as jest.Mock).mockResolvedValue({ error: "本番サイトが設定されていません" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Facebook と接続" }));

    expect(await screen.findByText("本番サイトが設定されていません")).toBeInTheDocument();
  });

  it("接続済みでテスト投稿を押すと、投稿アクションを呼び成功を示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Facebook 投稿テスト" }));

    expect(testProjectFacebookPostAction).toHaveBeenCalledWith(5);
    expect(await screen.findByText("Facebook へ投稿テストを送りました。")).toBeInTheDocument();
  });

  it("テスト投稿が失敗したら理由を示す", async () => {
    (testProjectFacebookPostAction as jest.Mock).mockResolvedValue({ error: "Facebook の投稿に失敗しました(HTTP 403)" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Facebook 投稿テスト" }));

    expect(await screen.findByText(/HTTP 403/)).toBeInTheDocument();
  });

  it("接続済みで切断を押すと、切断アクションを呼び切断したことを示す", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Facebook を切断" }));

    expect(disconnectProjectFacebookAction).toHaveBeenCalledWith(5);
    expect(await screen.findByText("Facebook の接続を切断しました。")).toBeInTheDocument();
  });

  it("切断に失敗したら理由を示す", async () => {
    (disconnectProjectFacebookAction as jest.Mock).mockResolvedValue({ error: "本番サイトに届かない" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "Facebook を切断" }));

    expect(await screen.findByText("本番サイトに届かない")).toBeInTheDocument();
  });

  it("接続完了と失敗のバナーを示す", () => {
    const { rerender } = renderSection({ connectedBanner: true });
    expect(screen.getByText("Facebook ページを接続しました。")).toBeInTheDocument();

    rerender(
      <ProjectSnsFacebookSection projectId={5} view={connectedView} callbackUrl={CALLBACK_URL} errorBanner="invalid_state" />
    );
    expect(screen.getByText(/接続に失敗しました: invalid_state/)).toBeInTheDocument();
    expect(screen.queryByText("Facebook ページを接続しました。")).not.toBeInTheDocument();
  });

  describe("投稿先ページの選択(個人アカウントには投稿しない)", () => {
    const selection = {
      state: "5.abc",
      pages: [
        { id: "100", name: "公式ページ" },
        { id: "200", name: "別のページ" },
      ],
    };

    beforeEach(() => {
      (selectProjectFacebookPageAction as jest.Mock).mockResolvedValue({});
    });

    it("選択の途中でなければ、ページの選択欄は出さない", () => {
      renderSection();

      expect(screen.queryByTestId("sns-facebook-page-select")).not.toBeInTheDocument();
    });

    it("認可のあとは管理しているページを選択肢として示し、選ぶまで接続を完了させない", () => {
      renderSection({ pageSelection: selection });

      const select = screen.getByTestId("sns-facebook-page-select");
      expect(select).toHaveTextContent("公式ページ");
      expect(select).toHaveTextContent("別のページ");
      expect(screen.getByRole("button", { name: "このページを接続" })).toBeInTheDocument();
    });

    it("選んだページをアクションへ渡す", async () => {
      const user = userEvent.setup();
      renderSection({ pageSelection: selection });

      await user.click(screen.getByLabelText("別のページ"));
      await user.click(screen.getByRole("button", { name: "このページを接続" }));

      expect(selectProjectFacebookPageAction).toHaveBeenCalledTimes(1);
      const [projectId, state, , formData] = (selectProjectFacebookPageAction as jest.Mock).mock.calls[0];
      expect(projectId).toBe(5);
      expect(state).toBe("5.abc");
      expect((formData as FormData).get("facebookPageId")).toBe("200");
    });

    it("選択に失敗したら理由を示す", async () => {
      (selectProjectFacebookPageAction as jest.Mock).mockResolvedValue({ error: "接続失敗" });
      const user = userEvent.setup();
      renderSection({ pageSelection: selection });

      await user.click(screen.getByRole("button", { name: "このページを接続" }));

      expect(await screen.findByText("接続失敗")).toBeInTheDocument();
    });

    it("ページが1件も無ければ選択欄は出さず、その旨を示す", () => {
      renderSection({ pageSelection: { state: "5.abc", pages: [] } });

      expect(screen.queryByRole("button", { name: "このページを接続" })).not.toBeInTheDocument();
      expect(screen.getByTestId("sns-facebook-page-select")).toHaveTextContent("管理しているページがありません");
    });
  });
});
