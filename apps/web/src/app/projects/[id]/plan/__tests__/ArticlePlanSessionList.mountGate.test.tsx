import { renderToStaticMarkup } from "react-dom/server";
import type { ArticlePlanSessionSummary } from "@/lib/apiClient";
import { TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import { ArticlePlanSessionList } from "../ArticlePlanSessionList";

/**
 * issue #1366(親issue #1261 分割B-2): 「マウント前は固定プレースホルダーを表示する」
 * (#1362・#1363と同じmountedフラグ方式)を検証する。
 *
 * サーバー側の描画(`renderToStaticMarkup`)は effect を実行しないので、`mounted` は false のまま、
 * つまりマウント前の出力になる。`react` も自前の formatDate も差し替えない(#1835)。
 * `ArticlePlanSessionList` は `{日付}-{s.title}` のように日付を文字列へ埋め込む形なので、プレースホルダーは
 * 日付部分だけを置き換え、`-{s.title}` はそのまま残る。
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

const markup = (timezone: string | null) =>
  renderToStaticMarkup(
    <ArticlePlanSessionList
      sessions={[session()]}
      activeSessionId={null}
      onSelect={() => {}}
      onNewChat={() => {}}
      isLoading={false}
      timezone={timezone}
    />
  );

describe("ArticlePlanSessionList(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は作成日部分に固定プレースホルダーを表示し、タイトル部分は変えない(#1362と同じmountedフラグ方式、issue #1366)", () => {
    const html = markup(null);

    expect(html).toContain(`${TIMEZONE_PENDING_PLACEHOLDER}-E2Eスタブの企画テーマ`);
    expect(html).not.toContain("20260908");
  });

  it("個人設定TZがあるときは、マウント前でも個人設定のTZの日付を出す(ゲートなし)", () => {
    const html = markup("Asia/Tokyo");

    expect(html).toContain("20260909-E2Eスタブの企画テーマ");
    expect(html).not.toContain(TIMEZONE_PENDING_PLACEHOLDER);
  });
});
