import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectGoogleAnalyticsSettingsForm } from "../ProjectGoogleAnalyticsSettingsForm";
import {
  clearProjectGoogleAnalyticsCredentialsAction,
  selectProjectGoogleAnalyticsPropertyAction,
  setProjectGoogleAnalyticsClientAction,
} from "../actions";

jest.mock("../actions", () => ({
  setProjectGoogleAnalyticsClientAction: jest.fn(),
  selectProjectGoogleAnalyticsPropertyAction: jest.fn(),
  clearProjectGoogleAnalyticsCredentialsAction: jest.fn(),
}));

/**
 * issue #1231: GA設定画面は「OAuthクライアント保存 → Googleアカウント連携 → プロパティ選択」の導線。
 * サービスアカウントJSONの入力欄は無く、保存済みのクライアントシークレット/リフレッシュトークンは
 * 再表示しない(受け入れ基準2・5)。
 */
const PROPERTIES = [
  { propertyId: "987654321", displayName: "E2E Stub Site", accountDisplayName: "E2E Account" },
  { propertyId: "555000111", displayName: "Second Site", accountDisplayName: "Other Account" },
];

function renderForm(overrides: Partial<React.ComponentProps<typeof ProjectGoogleAnalyticsSettingsForm>> = {}) {
  return render(
    <ProjectGoogleAnalyticsSettingsForm
      projectId={5}
      connected={false}
      propertyId={null}
      clientId={null}
      hasClientSecret={false}
      properties={[]}
      {...overrides}
    />
  );
}

describe("ProjectGoogleAnalyticsSettingsForm", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (setProjectGoogleAnalyticsClientAction as jest.Mock).mockResolvedValue({ success: true });
    (selectProjectGoogleAnalyticsPropertyAction as jest.Mock).mockResolvedValue({ success: true });
    (clearProjectGoogleAnalyticsCredentialsAction as jest.Mock).mockResolvedValue(undefined);
  });

  it("未連携なら「未設定」と表示し、サービスアカウントJSONの入力欄は無い", () => {
    renderForm();

    expect(screen.getByText("未設定", { exact: true })).toBeInTheDocument();
    expect(document.querySelector('textarea[name="serviceAccountJson"]')).toBeNull();
    expect(document.querySelector('input[name="propertyId"]')).toBeNull();
  });

  it("クライアントが未保存なら連携リンクの代わりに先にクライアントを保存する案内を出す", () => {
    renderForm();

    expect(screen.queryByRole("link", { name: "Googleアカウントと連携" })).not.toBeInTheDocument();
    expect(screen.getByText(/先にOAuthクライアントID\/シークレットを保存/)).toBeInTheDocument();
  });

  it("クライアントIDだけでシークレットが無ければ連携リンクは出ない", () => {
    renderForm({ clientId: "cid", hasClientSecret: false });

    expect(screen.queryByRole("link", { name: "Googleアカウントと連携" })).not.toBeInTheDocument();
  });

  it("クライアントが保存済みなら連携リンクが現れ、シークレットは設定済みとしてだけ示す", () => {
    renderForm({ clientId: "cid.apps.googleusercontent.com", hasClientSecret: true });

    expect(screen.getByRole("link", { name: "Googleアカウントと連携" })).toHaveAttribute(
      "href",
      "/connect/google-analytics/start?projectId=5"
    );
    expect(document.querySelector('input[name="clientSecret"]')).toHaveAttribute(
      "placeholder",
      "設定済み(変更する場合のみ入力)"
    );
    expect(document.querySelector('input[name="clientSecret"]')).toHaveValue("");
    expect(document.querySelector('input[name="clientId"]')).toHaveValue("cid.apps.googleusercontent.com");
  });

  it("シークレット未保存のときのplaceholderは「未設定」", () => {
    renderForm();

    expect(document.querySelector('input[name="clientSecret"]')).toHaveAttribute("placeholder", "未設定");
  });

  it("連携済みでプロパティ未選択なら「連携済み」と一覧を表示する", () => {
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    expect(screen.getByText("連携済み(プロパティ未選択)", { exact: true })).toBeInTheDocument();
    const select = document.querySelector('select[name="propertyId"]') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(screen.getByRole("option", { name: /E2E Stub Site.*987654321/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Second Site.*555000111/ })).toBeInTheDocument();
    expect(select.value).toBe("");
  });

  it("選択済みのプロパティが一覧で選ばれ、状態にプロパティIDが出る", () => {
    renderForm({
      connected: true,
      propertyId: "555000111",
      clientId: "cid",
      hasClientSecret: true,
      properties: PROPERTIES,
    });

    expect(screen.getByText("連携済み(プロパティID: 555000111)", { exact: true })).toBeInTheDocument();
    expect((document.querySelector('select[name="propertyId"]') as HTMLSelectElement).value).toBe("555000111");
  });

  it("プロパティを選んで保存するとアクションへ渡り、成功メッセージが出る", async () => {
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    await user.selectOptions(document.querySelector('select[name="propertyId"]') as HTMLSelectElement, "987654321");
    await user.click(screen.getByRole("button", { name: "プロパティを保存" }));

    expect(selectProjectGoogleAnalyticsPropertyAction).toHaveBeenCalledTimes(1);
    const formData = (selectProjectGoogleAnalyticsPropertyAction as jest.Mock).mock.calls[0][2] as FormData;
    expect(formData.get("propertyId")).toBe("987654321");
    expect(await screen.findByText("プロパティを保存しました。")).toBeInTheDocument();
  });

  it("プロパティ保存が失敗したらエラーを表示する", async () => {
    (selectProjectGoogleAnalyticsPropertyAction as jest.Mock).mockResolvedValue({ error: "保存に失敗" });
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    await user.click(screen.getByRole("button", { name: "プロパティを保存" }));

    expect(await screen.findByText("保存に失敗")).toBeInTheDocument();
  });

  it("連携済みでアクセスできるプロパティが無ければ、その旨を表示する", () => {
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: [] });

    expect(screen.getByText(/アクセスできるGA4プロパティがありません/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "プロパティを保存" })).not.toBeInTheDocument();
  });

  it("プロパティ一覧の取得に失敗したときは理由を表示する", () => {
    renderForm({
      connected: true,
      clientId: "cid",
      hasClientSecret: true,
      properties: [],
      propertiesError: "Googleに拒否されました",
    });

    expect(screen.getByText(/プロパティ一覧を取得できませんでした: Googleに拒否されました/)).toBeInTheDocument();
    expect(screen.queryByText(/アクセスできるGA4プロパティがありません/)).not.toBeInTheDocument();
  });

  it("未連携の間はプロパティ選択欄を出さない", () => {
    renderForm({ clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    expect(document.querySelector('select[name="propertyId"]')).toBeNull();
  });

  it("OAuthクライアントを保存すると成功メッセージが出る", async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(document.querySelector('input[name="clientId"]') as HTMLInputElement, "cid");
    await user.type(document.querySelector('input[name="clientSecret"]') as HTMLInputElement, "secret");
    await user.click(screen.getByRole("button", { name: "クライアントを保存" }));

    expect(setProjectGoogleAnalyticsClientAction).toHaveBeenCalledTimes(1);
    const formData = (setProjectGoogleAnalyticsClientAction as jest.Mock).mock.calls[0][2] as FormData;
    expect(formData.get("clientId")).toBe("cid");
    expect(formData.get("clientSecret")).toBe("secret");
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
  });

  it("クライアント保存が失敗したらエラーを表示する", async () => {
    (setProjectGoogleAnalyticsClientAction as jest.Mock).mockResolvedValue({ error: "クライアントIDを入力してください。" });
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole("button", { name: "クライアントを保存" }));

    expect(await screen.findByText("クライアントIDを入力してください。")).toBeInTheDocument();
  });

  it("連携完了/失敗のバナーを表示する", () => {
    const { unmount } = renderForm({ connectedBanner: true });
    expect(screen.getByText("Googleアカウントとの連携が完了しました。")).toBeInTheDocument();
    unmount();

    renderForm({ errorBanner: "access_denied" });
    expect(screen.getByText(/連携に失敗しました: access_denied/)).toBeInTheDocument();
  });

  it("バナーは指定が無ければ出ない", () => {
    renderForm();

    expect(screen.queryByText("Googleアカウントとの連携が完了しました。")).not.toBeInTheDocument();
    expect(screen.queryByText(/連携に失敗しました/)).not.toBeInTheDocument();
  });

  it("連携解除は確認のうえアクションを呼ぶ", async () => {
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    await user.click(screen.getByRole("button", { name: "連携を解除" }));

    expect(clearProjectGoogleAnalyticsCredentialsAction).toHaveBeenCalledWith(5);
    confirmSpy.mockRestore();
  });

  it("連携解除の確認でキャンセルしたら何もしない", async () => {
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    renderForm({ connected: true, clientId: "cid", hasClientSecret: true, properties: PROPERTIES });

    await user.click(screen.getByRole("button", { name: "連携を解除" }));

    expect(clearProjectGoogleAnalyticsCredentialsAction).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("クライアントだけ保存済み(未連携)でも連携を解除できる", () => {
    renderForm({ clientId: "cid", hasClientSecret: true });

    expect(screen.getByRole("button", { name: "連携を解除" })).toBeInTheDocument();
  });

  it("何も保存されていなければ連携を解除するボタンは出ない", () => {
    renderForm();

    expect(screen.queryByRole("button", { name: "連携を解除" })).not.toBeInTheDocument();
  });
});
