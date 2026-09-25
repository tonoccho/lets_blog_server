import { render, screen } from "@testing-library/react";

/**
 * issue #1366(親issue #1261 分割B-2): `plan/page.tsx` → `ArticlePlanWorkspace` →
 * `ArticlePlanSessionList` にタイムゾーンを渡す経路を足す。このファイルは
 * `ArticlePlanWorkspace`が受け取った`timezone`propを`ArticlePlanSessionList`へ
 * そのまま素通しすることだけを確かめる(`"use client"`同士の受け渡しなのでprop渡し、
 * `PostsTable`と同じ形)。
 *
 * `"./actions"`は`@/lib/apiClient`(`server-only`)を経由するため、jsdom環境で実モジュールを
 * 読み込むと`server-only`パッケージが例外を投げる(`admin/ssh-keys/__tests__/page.test.tsx`と
 * 同じ理由)。ここでは呼び出されないため丸ごとモックする。チャット・提案・構成の各子部品は
 * 検証対象ではないため`() => null`にし、`ArticlePlanSessionList`だけ受け取ったpropsを
 * data属性に出す簡易モックにする。
 */
jest.mock("../actions", () => ({
  sendPlanChatMessage: jest.fn(),
  loadPlanSessions: jest.fn(),
  loadPlanSession: jest.fn(),
  loadIssueDescription: jest.fn(),
}));

jest.mock("../ArticlePlanChat", () => ({ ArticlePlanChat: () => null }));
jest.mock("../ArticlePlanProposals", () => ({ ArticlePlanProposals: () => null }));
jest.mock("../ArticlePlanStructureProposal", () => ({ ArticlePlanStructureProposal: () => null }));
jest.mock("../ArticlePlanSessionList", () => ({
  ArticlePlanSessionList: (props: { timezone: string | null }) => (
    <div data-testid="session-list" data-timezone={props.timezone === null ? "null" : props.timezone} />
  ),
}));

import { ArticlePlanWorkspace } from "../ArticlePlanWorkspace";

describe("ArticlePlanWorkspace のタイムゾーン受け渡し(issue #1366)", () => {
  it("個人設定TZが設定されているとき、そのままArticlePlanSessionListへ渡す", () => {
    render(
      <ArticlePlanWorkspace
        projectId={1}
        initialSessions={[]}
        initialIssueNumber={null}
        initialIssueTitle={null}
        initialIssueSession={null}
        initialIssueStructure={null}
        timezone="Asia/Tokyo"
      />
    );

    expect(screen.getByTestId("session-list")).toHaveAttribute("data-timezone", "Asia/Tokyo");
  });

  it("個人設定TZが未設定(null)のとき、nullのままArticlePlanSessionListへ渡す", () => {
    render(
      <ArticlePlanWorkspace
        projectId={1}
        initialSessions={[]}
        initialIssueNumber={null}
        initialIssueTitle={null}
        initialIssueSession={null}
        initialIssueStructure={null}
        timezone={null}
      />
    );

    expect(screen.getByTestId("session-list")).toHaveAttribute("data-timezone", "null");
  });
});
