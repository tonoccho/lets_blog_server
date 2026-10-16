import { fireEvent, render, screen } from "@testing-library/react";
import type { ArticlePlanSessionSummary } from "@/lib/apiClient";
import { ArticlePlanSessionList } from "../ArticlePlanSessionList";

/**
 * issue #1366(親issue #1261 分割B-2)。作成日は個人設定TZ(あればそれ)、無ければマウント後にブラウザのTZで組み立てる。
 * 「マウント前は固定プレースホルダーを表示する」は ArticlePlanSessionList.mountGate.test.tsx が担う。
 * 自前の formatDate は差し替えない(テストは UTC 固定)。
 */
function session(overrides: Partial<ArticlePlanSessionSummary> = {}): ArticlePlanSessionSummary {
  return {
    id: 1,
    title: "E2Eスタブの企画テーマ",
    githubIssueNumber: null,
    createdAt: "2026-09-08T20:03:35",
    updatedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

function renderList(over: Partial<React.ComponentProps<typeof ArticlePlanSessionList>> = {}) {
  const onSelect = jest.fn();
  const onNewChat = jest.fn();
  const view = render(
    <ArticlePlanSessionList
      sessions={[session()]}
      activeSessionId={null}
      onSelect={onSelect}
      onNewChat={onNewChat}
      isLoading={false}
      timezone="Asia/Tokyo"
      {...over}
    />
  );
  return { ...view, onSelect, onNewChat };
}

describe("ArticlePlanSessionList", () => {
  it("個人設定TZが設定されているとき、そのTZの日付で作成日を出す(UTC の 9/8 20:03 は東京の 9/9)", () => {
    const { container } = renderList({ timezone: "Asia/Tokyo" });
    expect(container.textContent).toContain("20260909-E2Eスタブの企画テーマ");
  });

  it("個人設定TZが未設定のとき、マウント後はブラウザのTZ(テストは UTC)の日付で出す", () => {
    const { container } = renderList({ timezone: null });
    expect(container.textContent).toContain("20260908-E2Eスタブの企画テーマ");
  });

  it("セッションが無いときは案内文を表示する", () => {
    const { container } = renderList({ sessions: [] });
    expect(container.textContent).toContain("まだ壁打ちセッションはありません。");
  });

  it("Issue に紐付くセッションには Issue 番号のバッジを付け、選択中のセッションは強調する", () => {
    renderList({
      sessions: [session({ id: 1, githubIssueNumber: 3 }), session({ id: 2, title: "別" })],
      activeSessionId: 1,
    });
    expect(screen.getByText("#3")).toBeInTheDocument();
    const [active, other] = screen.getAllByRole("button", { name: /E2E|別/ });
    expect(active.className).toContain("bg-neutral-900");
    expect(other.className).not.toContain("bg-neutral-900");
  });

  it("セッションを押すと選択を、「新規チャットを開始」を押すと新規開始を知らせる", () => {
    const { onSelect, onNewChat } = renderList();
    fireEvent.click(screen.getByRole("button", { name: /E2Eスタブ/ }));
    expect(onSelect).toHaveBeenCalledWith(1);
    fireEvent.click(screen.getByRole("button", { name: "新規チャットを開始" }));
    expect(onNewChat).toHaveBeenCalledTimes(1);
  });

  it("読み込み中はセッションを押せず、エラーがあれば表示する", () => {
    renderList({ isLoading: true, error: "開けませんでした" });
    expect(screen.getByRole("button", { name: /E2Eスタブ/ })).toBeDisabled();
    expect(screen.getByText("開けませんでした")).toBeInTheDocument();
  });
});
