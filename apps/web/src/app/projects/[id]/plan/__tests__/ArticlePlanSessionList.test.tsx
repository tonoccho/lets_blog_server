import { render } from "@testing-library/react";
import type { ArticlePlanSessionSummary } from "@/lib/apiClient";

/**
 * issue #1366(親issue #1261 分割B-2)。`ArticlePlanSessionList`は個人設定TZを受け取る
 * 経路が無く、独自の`formatSessionDate()`(`new Date(iso).getFullYear()`等)で作成日を
 * 組み立てていた(ローカル解釈・ローカル取り出しのため実行環境TZには依存しないが、
 * 個人設定TZにもブラウザTZにも従わない、issue #1279の実測)。
 *
 * 「マウント前は固定プレースホルダーを表示する」はArticlePlanSessionList.mountGate.test.tsx
 * が担う(理由は同ファイルの先頭コメント参照)。ここでは残り2分岐
 * (個人設定TZあり/未設定・マウント後)を確かめる。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateYYYYMMDD: jest.fn((iso: string, tz?: string | null) => `FORMATTED(${iso}|${tz})`),
  TIMEZONE_PENDING_PLACEHOLDER: "読み込み中…",
}));

import { ArticlePlanSessionList } from "../ArticlePlanSessionList";
import { formatDateYYYYMMDD } from "@/lib/formatDate";

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

describe("ArticlePlanSessionList", () => {
  it("個人設定TZが設定されているとき、formatDateYYYYMMDDにそのTZを渡す(gateなし)", () => {
    const s = session();
    const { container } = render(
      <ArticlePlanSessionList
        sessions={[s]}
        activeSessionId={null}
        onSelect={() => {}}
        onNewChat={() => {}}
        isLoading={false}
        timezone="Asia/Tokyo"
      />
    );

    expect(formatDateYYYYMMDD).toHaveBeenCalledWith(s.createdAt, "Asia/Tokyo");
    expect(container.textContent).toContain(`FORMATTED(${s.createdAt}|Asia/Tokyo)-${s.title}`);
  });

  it("個人設定TZが未設定のとき、マウント後はformatDateYYYYMMDDをTZ引数無しで呼ぶ(ブラウザTZへフォールバック)", () => {
    const s = session();
    const { container } = render(
      <ArticlePlanSessionList
        sessions={[s]}
        activeSessionId={null}
        onSelect={() => {}}
        onNewChat={() => {}}
        isLoading={false}
        timezone={null}
      />
    );

    expect(formatDateYYYYMMDD).toHaveBeenCalledWith(s.createdAt);
    expect(container.textContent).toContain(`FORMATTED(${s.createdAt}|undefined)-${s.title}`);
  });

  it("セッションが無いときは既存どおり案内文を表示する(既存の挙動)", () => {
    const { container } = render(
      <ArticlePlanSessionList
        sessions={[]}
        activeSessionId={null}
        onSelect={() => {}}
        onNewChat={() => {}}
        isLoading={false}
        timezone="Asia/Tokyo"
      />
    );

    expect(container.textContent).toContain("まだ壁打ちセッションはありません。");
  });
});
