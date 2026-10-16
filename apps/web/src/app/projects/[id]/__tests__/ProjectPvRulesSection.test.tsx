import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectPvRulesSection } from "../ProjectPvRulesSection";
import type { PvRulesView } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies, calledRequests, failFetchWith, failWith, installBackend, jsonResponse } from "./projectCoreRuntime";

jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

/**
 * issue #1578: 「SNS 告知」欄の PV 達成ルール。一覧と追加・削除、GA 未連携のときは追加できず理由を示す、
 * 本番サイトへの送信状態(失敗のときは「再送」)、GA の集計遅れの注記。
 * Server Action(`../pvRuleActions`)・`@/lib/apiClient`・`@/lib/session` は実物を通し、差し替えるのは
 * Next.js の実行基盤と外部との通信(`fetch`)だけ(#1838)。
 */
const ADD = "POST /api/projects/5/sns/pv/rules";
const DELETE_R1 = "DELETE /api/projects/5/sns/pv/rules/r1";
const DELETE_R2 = "DELETE /api/projects/5/sns/pv/rules/r2";
const RESEND = "POST /api/projects/5/sns/pv/resend";

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

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [ADD]: () => jsonResponse(view),
    [DELETE_R1]: () => jsonResponse(view),
    [DELETE_R2]: () => jsonResponse(view),
    [RESEND]: () => jsonResponse(view),
  });
});

describe("ProjectPvRulesSection", () => {
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

    await waitFor(() => expect(bodies(backend, ADD)).toHaveLength(1));
    expect(bodies(backend, ADD)).toEqual([{ period: "total", threshold: 3000 }]);
  });

  it("期間の選択肢は 1日 と 累計", () => {
    renderSection();

    const options = within(screen.getByRole("combobox")).getAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["1日", "累計"]);
  });

  it("追加が拒否されたら理由を示す", async () => {
    installBackend({ [ADD]: failWith(409, "GA が連携されていません") });
    const user = userEvent.setup();
    renderSection();

    await user.type(screen.getByRole("spinbutton"), "5");
    await user.click(screen.getByRole("button", { name: "ルールを追加" }));

    expect(await screen.findByText("APIエラー (409): GA が連携されていません")).toBeInTheDocument();
  });

  it("閾値が空のまま追加を送信すると、バックエンドへ送らず入力を求める", async () => {
    renderSection();

    fireEvent.submit(screen.getByRole("button", { name: "ルールを追加" }).closest("form") as HTMLFormElement);

    expect(await screen.findByText("閾値は 1 以上の整数で入力してください。")).toBeInTheDocument();
    expect(bodies(backend, ADD)).toEqual([]);
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

    await waitFor(() => expect(calledRequests(backend)).toEqual([DELETE_R2]));
  });

  it("削除が拒否されたら理由を示す", async () => {
    installBackend({ [DELETE_R1]: failWith(404, "ルールが見つかりません") });
    const user = userEvent.setup();
    renderSection();

    await user.click(within(screen.getAllByTestId("pv-rule-item")[0]).getByRole("button", { name: "削除" }));

    expect(await screen.findByText("APIエラー (404): ルールが見つかりません")).toBeInTheDocument();
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

    await waitFor(() => expect(calledRequests(backend)).toEqual([RESEND]));
  });

  it("失敗の理由が無くても送信失敗と示す", () => {
    renderSection({ view: { ...view, send: { state: "FAILED", error: null, at: null } } });

    expect(screen.getByTestId("pv-send-status")).toHaveTextContent("送信失敗");
  });

  it("再送がまた失敗したら理由を示す", async () => {
    installBackend({
      [RESEND]: () => jsonResponse({ ...view, send: { state: "FAILED", error: "まだ届きません", at: null } }),
    });
    const user = userEvent.setup();
    renderSection({ view: { ...view, send: { state: "FAILED", error: "本番サイトに届きません", at: null } } });

    await user.click(screen.getByRole("button", { name: "再送" }));

    expect(await screen.findByText("まだ届きません")).toBeInTheDocument();
  });

  it("削除の通信そのものが失敗したら、その理由を示す", async () => {
    failFetchWith("boom");
    const user = userEvent.setup();
    renderSection();

    await user.click(within(screen.getAllByTestId("pv-rule-item")[0]).getByRole("button", { name: "削除" }));

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });

  it("取得できなければ取得できないと示し、画面は壊れない(追加はできない)", () => {
    renderSection({ view: null });

    expect(screen.getByRole("heading", { name: "PV 達成ルール", level: 3 })).toBeInTheDocument();
    expect(screen.getByTestId("pv-rules-reason")).toHaveTextContent("取得できない");
    expect(screen.getByRole("button", { name: "ルールを追加" })).toBeDisabled();
    expect(screen.queryByTestId("pv-send-status")).not.toBeInTheDocument();
  });
});
