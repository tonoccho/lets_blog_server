import { render } from "@testing-library/react";
import type { PostSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B)。`PostsTable`は親から`timezone: string | null`を
 * 素通しで受け取り、`formatDateTime`をゲート無しで呼んでいたため、個人設定TZが未設定
 * (null)のとき閲覧者のブラウザTZへ落ちる経路が無かった(#1362と同じ形)。
 *
 * 「マウント前は固定プレースホルダーを表示する」は PostsTable.mountGate.test.tsx が担う
 * (理由は同ファイルの先頭コメント参照)。ここでは残り3分岐
 * (個人設定TZあり/未設定・マウント後/日時が無い)を確かめる。
 */
jest.mock("@/components/ViewerDateTime", () => ({
  ViewerDateTime: ({ iso, personalTimeZone }: { iso: string; personalTimeZone: string | null }) => (
    <span data-testid="viewer-datetime">{`VDT(${iso}|${personalTimeZone})`}</span>
  ),
}));

import { PostsTable } from "../PostsTable";

function post(overrides: Partial<PostSummary> = {}): PostSummary {
  return {
    id: 1,
    siteId: 1,
    siteName: "site-a",
    wpPostId: "10",
    slug: "hello-world",
    status: "publish",
    categories: [],
    lastPublishedAt: "2026-09-08T20:03:35",
    publishScheduledAt: "2026-09-09T05:00:00",
    ...overrides,
  };
}

/** 日時セル(最終投稿日時・公開予定日時)だけを、他列の "-" と取り違えずに取り出す。 */
function dateCells(container: HTMLElement): [string, string] {
  const cells = container.querySelectorAll("tbody tr td");
  return [(cells[5]?.textContent ?? "").trim(), (cells[6]?.textContent ?? "").trim()];
}

describe("PostsTable", () => {
  it("個人設定TZが設定されているとき、最終投稿日時・公開予定日時ともViewerDateTimeへそのTZを渡す(#1367)", () => {
    const p = post();
    const { container } = render(<PostsTable posts={[p]} timezone="Asia/Tokyo" />);

    expect(dateCells(container)).toEqual([
      `VDT(${p.lastPublishedAt}|Asia/Tokyo)`,
      `VDT(${p.publishScheduledAt}|Asia/Tokyo)`,
    ]);
  });

  it("個人設定TZが未設定のとき、ViewerDateTimeへnullを渡す(#1367)", () => {
    const p = post();
    const { container } = render(<PostsTable posts={[p]} timezone={null} />);

    expect(dateCells(container)).toEqual([
      `VDT(${p.lastPublishedAt}|null)`,
      `VDT(${p.publishScheduledAt}|null)`,
    ]);
  });

  it("最終投稿日時・公開予定日時が無いときはTZ設定に関わらず「-」を表示する(既存の挙動、issue #282)", () => {
    const p = post({ lastPublishedAt: null, publishScheduledAt: null });
    const { container } = render(<PostsTable posts={[p]} timezone="Asia/Tokyo" />);

    expect(dateCells(container)).toEqual(["-", "-"]);
  });
});
