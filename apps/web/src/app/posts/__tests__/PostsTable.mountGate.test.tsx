import { renderToString } from "react-dom/server";
import type { PostSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)を検証する。
 *
 * マウント前の状態は、`react` の `useEffect` や `@/lib/formatDate` を差し替えて作らず、
 * サーバー描画(`renderToString`。effect は走らない)で作る。実際にサーバーが返す HTML と同じ状態を見る(#1834)。
 *
 * `PostsTable`は最終投稿日時と公開予定日時の2箇所で日時を整形するため、両方のプレースホルダー表示を確かめる。
 */
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

describe("PostsTable(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は最終投稿日時・公開予定日時の両方に固定プレースホルダーを表示する(#1362と同じmountedフラグ方式、issue #1363)", () => {
    const html = renderToString(<PostsTable posts={[post()]} timezone={null} />);

    expect(html).not.toContain("2026/9/");
    expect(html.match(/読み込み中…/g)).toHaveLength(2);
  });
});
