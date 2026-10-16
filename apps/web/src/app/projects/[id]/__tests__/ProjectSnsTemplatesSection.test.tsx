import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProjectSnsTemplatesSection } from "../ProjectSnsTemplatesSection";
import type { SnsTemplatesView } from "@/lib/apiClient";
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
 * issue #1583: 「SNS 告知」欄の告知文テンプレート。公開時と PV 達成時を別々の欄で編集して保存する。
 * 差し込み項目の案内、空なら既定の告知文を使う旨、本番サイトへの送信状態(失敗のときは「再送」)。
 * Server Action(`../snsTemplateActions`)・`@/lib/apiClient`・`@/lib/session` は実物を通し、差し替えるのは
 * Next.js の実行基盤と外部との通信(`fetch`)だけ(#1838)。
 */
const SAVE = "PUT /api/projects/5/sns/templates";
const RESEND = "POST /api/projects/5/sns/templates/resend";

const view: SnsTemplatesView = {
  publishTemplate: "【新着】{title} {url}",
  pvTemplate: "{period}で{threshold}PV達成 {url}",
  send: { state: "SENT", error: null, at: "2026-10-06T00:00:00Z" },
};

function renderSection(props: Partial<React.ComponentProps<typeof ProjectSnsTemplatesSection>> = {}) {
  return render(<ProjectSnsTemplatesSection projectId={5} view={view} {...props} />);
}

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [SAVE]: () => jsonResponse(view), [RESEND]: () => jsonResponse(view) });
});

describe("ProjectSnsTemplatesSection", () => {
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

    await waitFor(() => expect(bodies(backend, SAVE)).toHaveLength(1));
    expect(bodies(backend, SAVE)).toEqual([
      { publishTemplate: "公開: ", pvTemplate: "{period}で{threshold}PV達成 {url}" },
    ]);
  });

  it("保存が拒否されたら理由を示す", async () => {
    installBackend({ [SAVE]: failWith(400, "テンプレートは1000文字以内です") });
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テンプレートを保存" }));

    expect(await screen.findByText("APIエラー (400): テンプレートは1000文字以内です")).toBeInTheDocument();
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

    await waitFor(() => expect(calledRequests(backend)).toEqual([RESEND]));
  });

  it("失敗の理由が無くても送信失敗と示す", () => {
    renderSection({ view: { ...view, send: { state: "FAILED", error: null, at: null } } });

    expect(screen.getByTestId("sns-templates-send-status")).toHaveTextContent("送信失敗");
  });

  it("保存の通信そのものが失敗したら、その理由を示す", async () => {
    failFetchWith("boom");
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テンプレートを保存" }));

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });

  it("保存が通ると、エラーを出さずに保存できたままにする", async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole("button", { name: "テンプレートを保存" }));

    await waitFor(() => expect(bodies(backend, SAVE)).toHaveLength(1));
    expect(screen.queryByText(/APIエラー/)).not.toBeInTheDocument();
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
});
