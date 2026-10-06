import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsTemplatesSection } from "../ProjectSnsTemplatesSection";
import { resendProjectSnsTemplatesAction, saveProjectSnsTemplatesAction } from "../snsTemplateActions";
import type { SnsTemplatesView } from "@/lib/apiClient";

jest.mock("../snsTemplateActions", () => ({
  saveProjectSnsTemplatesAction: jest.fn(),
  resendProjectSnsTemplatesAction: jest.fn(),
}));

/**
 * issue #1583: 「SNS 告知」欄の告知文テンプレート。公開時と PV 達成時を別々の欄で編集して保存する。
 * 差し込み項目の案内、空なら既定の告知文を使う旨、本番サイトへの送信状態(失敗のときは「再送」)。
 */
const view: SnsTemplatesView = {
  publishTemplate: "【新着】{title} {url}",
  pvTemplate: "{period}で{threshold}PV達成 {url}",
  send: { state: "SENT", error: null, at: "2026-10-06T00:00:00Z" },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsTemplatesSection>> = {}) {
  return render(<ProjectSnsTemplatesSection projectId={5} view={view} {...props} />);
}

describe("ProjectSnsTemplatesSection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (saveProjectSnsTemplatesAction as jest.Mock).mockResolvedValue({ success: true });
    (resendProjectSnsTemplatesAction as jest.Mock).mockResolvedValue({ success: true });
  });

  it("見出しと、差し込み項目(公開時は title・url、PV 達成時はさらに period・threshold)と、空なら既定の告知文を使う旨を示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "告知文テンプレート", level: 3 })).toBeInTheDocument();
    expect(screen.getByTestId("sns-templates-publish-hint")).toHaveTextContent("{title}");
    expect(screen.getByTestId("sns-templates-publish-hint")).toHaveTextContent("{url}");
    expect(screen.getByTestId("sns-templates-publish-hint")).not.toHaveTextContent("{period}");
    expect(screen.getByTestId("sns-templates-pv-hint")).toHaveTextContent("{period}");
    expect(screen.getByTestId("sns-templates-pv-hint")).toHaveTextContent("{threshold}");
    expect(screen.getByTestId("sns-templates-empty-note")).toHaveTextContent("既定");
  });

  it("保存済みのテンプレートを、公開時と PV 達成時の別々の欄に入れて見せる", () => {
    renderSection();

    expect(screen.getByLabelText("公開時の告知文")).toHaveValue("【新着】{title} {url}");
    expect(screen.getByLabelText("PV 達成時の告知文")).toHaveValue("{period}で{threshold}PV達成 {url}");
  });

  it("取得できなければ空の欄を出す", () => {
    renderSection({ view: null });

    expect(screen.getByLabelText("公開時の告知文")).toHaveValue("");
    expect(screen.getByLabelText("PV 達成時の告知文")).toHaveValue("");
    expect(screen.getByTestId("sns-templates-unavailable")).toHaveTextContent("取得できない");
    expect(screen.queryByTestId("sns-templates-send-status")).not.toBeInTheDocument();
  });

  it("取得できたときは取得できない旨を出さない", () => {
    renderSection();

    expect(screen.queryByTestId("sns-templates-unavailable")).not.toBeInTheDocument();
  });

  it("編集して保存すると、二つの欄の値をフォームで送る", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.clear(screen.getByLabelText("公開時の告知文"));
    await user.type(screen.getByLabelText("公開時の告知文"), "公開: ");
    await user.click(screen.getByRole("button", { name: "テンプレートを保存" }));

    expect(saveProjectSnsTemplatesAction).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = (saveProjectSnsTemplatesAction as jest.Mock).mock.calls[0];
    expect(projectId).toBe(5);
    expect(formData.get("publishTemplate")).toBe("公開: ");
    expect(formData.get("pvTemplate")).toBe("{period}で{threshold}PV達成 {url}");
  });

  it("保存が拒否されたら理由を示す", async () => {
    (saveProjectSnsTemplatesAction as jest.Mock).mockResolvedValue({ error: "テンプレートは1000文字以内です" });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テンプレートを保存" }));

    expect(await screen.findByText("テンプレートは1000文字以内です")).toBeInTheDocument();
  });

  it("送信済みなら送信済みと示し、再送は出さない", () => {
    renderSection();

    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("送信済み");
    expect(screen.queryByRole("button", { name: "再送" })).not.toBeInTheDocument();
  });

  it("まだ送っていなければ未送信と示す", () => {
    renderSection({ view: { ...view, send: { state: "NONE", error: null, at: null } } });

    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("未送信");
  });

  it("送信に失敗していれば失敗と理由を示し、再送できる", async () => {
    const user = userEvent.setup();
    renderSection({ view: { ...view, send: { state: "FAILED", error: "本番サイトに届きません", at: null } } });

    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("送信失敗");
    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("本番サイトに届きません");
    await user.click(screen.getByRole("button", { name: "再送" }));

    expect(resendProjectSnsTemplatesAction).toHaveBeenCalledWith(5);
  });

  it("失敗の理由が無くても送信失敗と示す", () => {
    renderSection({ view: { ...view, send: { state: "FAILED", error: null, at: null } } });

    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("送信失敗");
  });

  it("再送がまた失敗したら理由を示す", async () => {
    (resendProjectSnsTemplatesAction as jest.Mock).mockResolvedValue({ error: "まだ届きません" });
    const user = userEvent.setup();
    renderSection({ view: { ...view, send: { state: "FAILED", error: "本番サイトに届きません", at: null } } });

    await user.click(screen.getByRole("button", { name: "再送" }));

    expect(await screen.findByText("まだ届きません")).toBeInTheDocument();
  });
});
