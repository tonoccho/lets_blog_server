import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectPvRulesSection } from "../ProjectPvRulesSection";
import {
  addProjectPvRuleAction,
  deleteProjectPvRuleAction,
  resendProjectPvRulesAction,
} from "../pvRuleActions";
import type { PvRulesView } from "@/lib/apiClient";

jest.mock("../pvRuleActions", () => ({
  addProjectPvRuleAction: jest.fn(),
  deleteProjectPvRuleAction: jest.fn(),
  resendProjectPvRulesAction: jest.fn(),
}));

/**
 * issue #1578: 「SNS 告知」欄の PV 達成ルール。一覧と追加・削除、GA 未連携のときは追加できず理由を示す、
 * 本番サイトへの送信状態(失敗のときは「再送」)、GA の集計遅れの注記。
 */
const view: PvRulesView = {
  addable: true,
  reason: null,
  rules: [
    { id: "r1", period: "daily", threshold: 100 },
    { id: "r2", period: "total", threshold: 5000 },
  ],
  send: { state: "SENT", error: null, at: "2026-10-05T00:00:00Z" },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectPvRulesSection>> = {}) {
  return render(<ProjectPvRulesSection projectId={5} view={view} {...props} />);
}

describe("ProjectPvRulesSection", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (addProjectPvRuleAction as jest.Mock).mockResolvedValue({ success: true });
    (deleteProjectPvRuleAction as jest.Mock).mockResolvedValue({ success: true });
    (resendProjectPvRulesAction as jest.Mock).mockResolvedValue({ success: true });
  });

  it("見出しと、GA の集計遅れで告知が遅れることがある旨を示す", () => {
    renderSection();

    expect(screen.getByRole("heading", { name: "PV 達成ルール", level: 3 })).toBeInTheDocument();
    expect(screen.getByTestId("pv-lag-note")).toHaveTextContent("集計");
    expect(screen.getByTestId("pv-lag-note")).toHaveTextContent("遅れ");
  });

  it("ルールを期間と閾値の読みやすい文言で一覧する", () => {
    renderSection();

    const items = screen.getAllByTestId("pv-rule-item");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("1日で100PV");
    expect(items[1]).toHaveTextContent("累計5000PV");
  });

  it("ルールが無ければ無いと示す", () => {
    renderSection({ view: { ...view, rules: [] } });

    expect(screen.queryByTestId("pv-rule-item")).not.toBeInTheDocument();
    expect(screen.getByText("PV 達成ルールはまだありません。")).toBeInTheDocument();
  });

  it("期間(1日 / 累計)と閾値を指定してルールを追加する", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.selectOptions(screen.getByRole("combobox"), "total");
    await user.type(screen.getByRole("spinbutton"), "3000");
    await user.click(screen.getByRole("button", { name: "ルールを追加" }));

    expect(addProjectPvRuleAction).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = (addProjectPvRuleAction as jest.Mock).mock.calls[0];
    expect(projectId).toBe(5);
    expect(formData.get("period")).toBe("total");
    expect(formData.get("threshold")).toBe("3000");
  });

  it("期間の選択肢は 1日 と 累計", () => {
    renderSection();

    const options = within(screen.getByRole("combobox")).getAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["1日", "累計"]);
  });

  it("追加が拒否されたら理由を示す", async () => {
    (addProjectPvRuleAction as jest.Mock).mockResolvedValue({ error: "閾値は1以上の整数で入力してください。" });
    const user = userEvent.setup();
    renderSection();

    await user.type(screen.getByRole("spinbutton"), "5");
    await user.click(screen.getByRole("button", { name: "ルールを追加" }));

    expect(await screen.findByText("閾値は1以上の整数で入力してください。")).toBeInTheDocument();
  });

  it("GA が未連携なら追加ボタンは押せず、理由を示す", () => {
    renderSection({
      view: { ...view, addable: false, reason: "GA が連携されていません。GA 設定で連携してください", rules: [] },
    });

    expect(screen.getByRole("button", { name: "ルールを追加" })).toBeDisabled();
    expect(screen.getByTestId("pv-rules-reason")).toHaveTextContent("GA が連携されていません");
  });

  it("追加できるときは理由を出さない", () => {
    renderSection();

    expect(screen.queryByTestId("pv-rules-reason")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "ルールを追加" })).toBeEnabled();
  });

  it("ルールを削除する", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(within(screen.getAllByTestId("pv-rule-item")[1]).getByRole("button", { name: "削除" }));

    expect(deleteProjectPvRuleAction).toHaveBeenCalledWith(5, "r2");
  });

  it("削除が拒否されたら理由を示す", async () => {
    (deleteProjectPvRuleAction as jest.Mock).mockResolvedValue({ error: "ルールが見つかりません" });
    const user = userEvent.setup();
    renderSection();

    await user.click(within(screen.getAllByTestId("pv-rule-item")[0]).getByRole("button", { name: "削除" }));

    expect(await screen.findByText("ルールが見つかりません")).toBeInTheDocument();
  });

  it("送信済みなら送信済みと示し、再送は出さない", () => {
    renderSection();

    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("送信済み");
    expect(screen.queryByRole("button", { name: "再送" })).not.toBeInTheDocument();
  });

  it("まだ送っていなければ未送信と示す", () => {
    renderSection({ view: { ...view, send: { state: "NONE", error: null, at: null } } });

    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("未送信");
  });

  it("送信に失敗していれば失敗と理由を示し、再送できる", async () => {
    const user = userEvent.setup();
    renderSection({
      view: { ...view, send: { state: "FAILED", error: "本番サイトに届きません", at: null } },
    });

    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("送信失敗");
    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("本番サイトに届きません");
    await user.click(screen.getByRole("button", { name: "再送" }));

    expect(resendProjectPvRulesAction).toHaveBeenCalledWith(5);
  });

  it("失敗の理由が無くても送信失敗と示す", () => {
    renderSection({ view: { ...view, send: { state: "FAILED", error: null, at: null } } });

    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("送信失敗");
  });

  it("再送がまた失敗したら理由を示す", async () => {
    (resendProjectPvRulesAction as jest.Mock).mockResolvedValue({ error: "まだ届きません" });
    const user = userEvent.setup();
    renderSection({ view: { ...view, send: { state: "FAILED", error: "本番サイトに届きません", at: null } } });

    await user.click(screen.getByRole("button", { name: "再送" }));

    expect(await screen.findByText("まだ届きません")).toBeInTheDocument();
  });

  it("取得できなければ取得できないと示し、画面は壊れない(追加はできない)", () => {
    renderSection({ view: null });

    expect(screen.getByRole("heading", { name: "PV 達成ルール", level: 3 })).toBeInTheDocument();
    expect(screen.getByTestId("pv-rules-reason")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "ルールを追加" })).toBeDisabled();
    expect(screen.queryByTestId("pv-send-status")).not.toBeInTheDocument();
  });
});
