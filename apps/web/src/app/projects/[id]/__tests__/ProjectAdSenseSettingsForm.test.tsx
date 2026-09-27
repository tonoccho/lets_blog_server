import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectAdSenseSettingsForm } from "../ProjectAdSenseSettingsForm";
import {
  clearProjectAdSenseCredentialsAction,
  selectProjectAdSenseAccountAction,
  setProjectAdSenseSettingsAction,
} from "../actions";

jest.mock("../actions", () => ({
  setProjectAdSenseSettingsAction: jest.fn(),
  selectProjectAdSenseAccountAction: jest.fn(),
  clearProjectAdSenseCredentialsAction: jest.fn(),
}));

/**
 * issue #1232: AdSense設定画面。パブリッシャーIDは任意入力で、Googleアカウント連携後に
 * 自動取得される。複数件のときは一覧から選び、取得に失敗したときは理由を示して手入力で復旧できる。
 */
const ACCOUNTS = [
  { accountId: "pub-1111111111111111", displayName: "E2E Stub Publisher" },
  { accountId: "pub-2222222222222222", displayName: null },
];

function renderForm(overrides: Partial<React.ComponentProps<typeof ProjectAdSenseSettingsForm>> = {}) {
  return render(
    <ProjectAdSenseSettingsForm
      projectId={5}
      configured={false}
      connected={false}
      accountId={null}
      clientId={null}
      hasClientSecret={false}
      accounts={[]}
      {...overrides}
    />
  );
}

describe("ProjectAdSenseSettingsForm", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (setProjectAdSenseSettingsAction as jest.Mock).mockResolvedValue({ success: true });
    (selectProjectAdSenseAccountAction as jest.Mock).mockResolvedValue({ success: true });
    (clearProjectAdSenseCredentialsAction as jest.Mock).mockResolvedValue(undefined);
  });

  it("未連携なら「未設定」と表示し、パブリッシャーIDは任意入力である", () => {
    renderForm();

    expect(screen.getByText("未設定", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("AdSenseパブリッシャーID(任意)")).toBeInTheDocument();
    expect(document.querySelector('input[name="accountId"]')).not.toBeRequired();
  });

  it("クライアントが未保存なら連携リンクの代わりに先にクライアントを保存する案内を出す", () => {
    renderForm();

    expect(screen.queryByRole("link", { name: "Google AdSenseと連携" })).not.toBeInTheDocument();
    expect(screen.getByText(/先にOAuthクライアントID\/シークレットを保存/)).toBeInTheDocument();
  });

  it("パブリッシャーIDが空でもクライアントが保存済みなら連携リンクが現れる", () => {
    renderForm({ clientId: "cid.apps.googleusercontent.com", hasClientSecret: true });

    expect(screen.getByRole("link", { name: "Google AdSenseと連携" })).toHaveAttribute(
      "href",
      "/connect/adsense/start?projectId=5"
    );
    expect(document.querySelector('input[name="accountId"]')).toHaveValue("");
    expect(document.querySelector('input[name="clientSecret"]')).toHaveAttribute(
      "placeholder",
      "設定済み(変更する場合のみ入力)"
    );
  });

  it("クライアントIDだけでシークレットが無ければ連携リンクは出ず、シークレットのplaceholderは「未設定」", () => {
    renderForm({ clientId: "cid", hasClientSecret: false });

    expect(screen.queryByRole("link", { name: "Google AdSenseと連携" })).not.toBeInTheDocument();
    expect(document.querySelector('input[name="clientSecret"]')).toHaveAttribute("placeholder", "未設定");
  });

  it("パブリッシャーIDを取得済みなら「連携済み(パブリッシャーID: pub-…)」と表示する", () => {
    renderForm({
      configured: true,
      connected: true,
      accountId: "pub-1234567890123456",
      clientId: "cid",
      hasClientSecret: true,
      accounts: [{ accountId: "pub-1234567890123456", displayName: "Site" }],
    });

    expect(screen.getByText("連携済み(パブリッシャーID: pub-1234567890123456)", { exact: true })).toBeInTheDocument();
    expect(document.querySelector('input[name="accountId"]')).toHaveValue("pub-1234567890123456");
  });

  it("連携済みでパブリッシャーID未取得なら、その状態と選択肢を表示する", () => {
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    expect(screen.getByText("連携済み(パブリッシャーID未設定)", { exact: true })).toBeInTheDocument();
    const select = document.querySelector('select[name="selectedAccountId"]') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(screen.getByRole("option", { name: "E2E Stub Publisher (pub-1111111111111111)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "(名称なし) (pub-2222222222222222)" })).toBeInTheDocument();
    expect(select.value).toBe("");
  });

  it("保存済みのパブリッシャーIDが一覧で選ばれている", () => {
    renderForm({
      configured: true,
      connected: true,
      accountId: "pub-2222222222222222",
      clientId: "cid",
      hasClientSecret: true,
      accounts: ACCOUNTS,
    });

    expect((document.querySelector('select[name="selectedAccountId"]') as HTMLSelectElement).value).toBe(
      "pub-2222222222222222"
    );
  });

  it("アカウントを選んで保存するとアクションへ渡り、成功メッセージが出る", async () => {
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    await user.selectOptions(
      document.querySelector('select[name="selectedAccountId"]') as HTMLSelectElement,
      "pub-2222222222222222"
    );
    await user.click(screen.getByRole("button", { name: "パブリッシャーIDを保存" }));

    expect(selectProjectAdSenseAccountAction).toHaveBeenCalledTimes(1);
    const formData = (selectProjectAdSenseAccountAction as jest.Mock).mock.calls[0][2] as FormData;
    expect(formData.get("selectedAccountId")).toBe("pub-2222222222222222");
    expect(await screen.findByText("パブリッシャーIDを保存しました。")).toBeInTheDocument();
  });

  it("アカウント保存が失敗したらエラーを表示する", async () => {
    (selectProjectAdSenseAccountAction as jest.Mock).mockResolvedValue({ error: "保存に失敗" });
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    await user.click(screen.getByRole("button", { name: "パブリッシャーIDを保存" }));

    expect(await screen.findByText("保存に失敗")).toBeInTheDocument();
  });

  it("連携済みでアカウントが0件ならその旨を示し、手入力を案内する", () => {
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: [] });

    expect(screen.getByText(/利用できるAdSenseアカウントがありません/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "パブリッシャーIDを保存" })).not.toBeInTheDocument();
  });

  it("アカウント一覧の取得に失敗したときは理由を表示し、手入力で復旧できる", () => {
    renderForm({
      connected: true,
      clientId: "cid",
      hasClientSecret: true,
      accounts: [],
      accountsError: "AdSenseアカウント一覧の取得に失敗しました: 403 FORBIDDEN",
    });

    expect(
      screen.getByText(/アカウント一覧を取得できませんでした: AdSenseアカウント一覧の取得に失敗しました: 403 FORBIDDEN/)
    ).toBeInTheDocument();
    expect(screen.getByText(/パブリッシャーIDを手入力/)).toBeInTheDocument();
    expect(screen.queryByText(/利用できるAdSenseアカウントがありません/)).not.toBeInTheDocument();
    expect(document.querySelector('input[name="accountId"]')).toBeInTheDocument();
  });

  it("未連携の間はアカウント選択欄を出さない", () => {
    renderForm({ clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    expect(document.querySelector('select[name="selectedAccountId"]')).toBeNull();
  });

  it("パブリッシャーIDを空のままクライアントだけ保存できる", async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(document.querySelector('input[name="clientId"]') as HTMLInputElement, "cid");
    await user.type(document.querySelector('input[name="clientSecret"]') as HTMLInputElement, "secret");
    await user.click(screen.getByRole("button", { name: "まとめて保存" }));

    expect(setProjectAdSenseSettingsAction).toHaveBeenCalledTimes(1);
    const formData = (setProjectAdSenseSettingsAction as jest.Mock).mock.calls[0][2] as FormData;
    expect(formData.get("accountId")).toBe("");
    expect(formData.get("clientId")).toBe("cid");
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });

  it("まとめて保存が失敗したらエラーを表示する", async () => {
    (setProjectAdSenseSettingsAction as jest.Mock).mockResolvedValue({ error: "Google OAuthクライアントIDを入力してください。" });
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole("button", { name: "まとめて保存" }));

    expect(await screen.findByText("Google OAuthクライアントIDを入力してください。")).toBeInTheDocument();
  });

  it("連携完了/失敗のバナーは指定されたときだけ表示する", () => {
    const { unmount } = renderForm({ connectedBanner: true });
    expect(screen.getByText("Googleアカウントとの連携が完了しました。")).toBeInTheDocument();
    unmount();

    const second = renderForm({ errorBanner: "access_denied" });
    expect(screen.getByText(/連携に失敗しました: access_denied/)).toBeInTheDocument();
    second.unmount();

    renderForm();
    expect(screen.queryByText("Googleアカウントとの連携が完了しました。")).not.toBeInTheDocument();
    expect(screen.queryByText(/連携に失敗しました/)).not.toBeInTheDocument();
  });

  it("設定の削除は確認のうえアクションを呼ぶ", async () => {
    const user = userEvent.setup();
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    await user.click(screen.getByRole("button", { name: "設定を削除" }));

    expect(clearProjectAdSenseCredentialsAction).toHaveBeenCalledWith(5);
    confirmSpy.mockRestore();
  });

  it("設定の削除の確認でキャンセルしたら何もしない", async () => {
    const user = userEvent.setup();
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(false);
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, accounts: ACCOUNTS });

    await user.click(screen.getByRole("button", { name: "設定を削除" }));

    expect(clearProjectAdSenseCredentialsAction).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("何も連携していなければ設定を削除するボタンは出ない", () => {
    renderForm();

    expect(screen.queryByRole("button", { name: "設定を削除" })).not.toBeInTheDocument();
  });
});
