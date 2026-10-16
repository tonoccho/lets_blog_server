import { fireEvent, render } from "@testing-library/react";
import type { PostSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B)。`PostsTable`は親から`timezone: string | null`を
 * 受け取り、日時の整形を `ViewerDateTime` に任せる。
 *
 * 「マウント前は固定プレースホルダーを表示する」は PostsTable.mountGate.test.tsx が担う。
 * ここでは、個人設定TZあり/未設定・マウント後/日時が無い場合と、並べ替えを確かめる。
 * 自前の ViewerDateTime・formatDate は差し替えず、実物の整形結果を見る(#1834)。
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

/** 日時セル(最終投稿日時・公開予定日時)だけを、他列の "-" と取り違えずに取り出す。 */
function dateCells(container: HTMLElement): [string, string] {
  const cells = container.querySelectorAll("tbody tr td");
  return [(cells[5]?.textContent ?? "").trim(), (cells[6]?.textContent ?? "").trim()];
}

function siteNames(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll("tbody tr")).map((row) => row.querySelector("td")?.textContent ?? "");
}

describe("PostsTable", () => {
  it("個人設定TZが設定されているとき、最終投稿日時・公開予定日時とも、そのTZで整形して表示する(#1367)", () => {
    const { container } = render(<PostsTable posts={[post()]} timezone="Asia/Tokyo" />);

    expect(dateCells(container)).toEqual(["2026/9/9 5:03:35", "2026/9/9 14:00:00"]);
  });

  it("個人設定TZが未設定のとき、マウント後はブラウザのTZ(テストはUTC)で整形する(#1367)", () => {
    const { container } = render(<PostsTable posts={[post()]} timezone={null} />);

    expect(dateCells(container)).toEqual(["2026/9/8 20:03:35", "2026/9/9 5:00:00"]);
  });

  it("最終投稿日時・公開予定日時が無いときはTZ設定に関わらず「-」を表示する(既存の挙動、issue #282)", () => {
    const p = post({ lastPublishedAt: null, publishScheduledAt: null });
    const { container } = render(<PostsTable posts={[p]} timezone="Asia/Tokyo" />);

    expect(dateCells(container)).toEqual(["-", "-"]);
  });

  it("投稿が無いときは空の案内を表示する", () => {
    const { container } = render(<PostsTable posts={[]} timezone={null} />);

    expect(container.textContent).toContain("投稿履歴はまだありません");
  });

  it("スラッグが無い投稿は「-」、カテゴリがあればカンマ区切りで表示する", () => {
    const { container } = render(<PostsTable posts={[post({ slug: null, categories: ["a", "b"] })]} timezone={null} />);

    const cells = Array.from(container.querySelectorAll("tbody tr td")).map((c) => c.textContent);
    expect(cells[2]).toBe("-");
    expect(cells[4]).toBe("a, b");
  });

  it("カテゴリが無い投稿は「-」を表示する", () => {
    const { container } = render(<PostsTable posts={[post({ categories: [] })]} timezone={null} />);

    expect(container.querySelectorAll("tbody tr td")[4].textContent).toBe("-");
  });

  describe("列見出しによる並べ替え", () => {
    const posts = [
      post({ id: 1, siteName: "b-site", status: "publish", lastPublishedAt: "2026-09-02T00:00:00" }),
      post({ id: 2, siteName: "a-site", status: "draft", lastPublishedAt: null }),
      post({ id: 3, siteName: "c-site", status: "future", lastPublishedAt: "2026-09-01T00:00:00" }),
    ];

    it("初期表示は受け取った順のまま並べ替えの印が無い", () => {
      const { container, getByText } = render(<PostsTable posts={posts} timezone={null} />);

      expect(siteNames(container)).toEqual(["b-site", "a-site", "c-site"]);
      expect(getByText("サイト").textContent).toBe("サイト");
    });

    it("サイト列: 1回目は昇順(↑)、2回目は降順(↓)", () => {
      const { container, getByText } = render(<PostsTable posts={posts} timezone={null} />);

      fireEvent.click(getByText("サイト"));
      expect(siteNames(container)).toEqual(["a-site", "b-site", "c-site"]);
      expect(getByText(/^サイト ↑$/)).toBeInTheDocument();

      fireEvent.click(getByText(/^サイト ↑$/));
      expect(siteNames(container)).toEqual(["c-site", "b-site", "a-site"]);
      expect(getByText(/^サイト ↓$/)).toBeInTheDocument();
    });

    it("同じ列を3回押すと昇順に戻る", () => {
      const { container, getByText } = render(<PostsTable posts={posts} timezone={null} />);

      fireEvent.click(getByText("サイト"));
      fireEvent.click(getByText(/^サイト ↑$/));
      fireEvent.click(getByText(/^サイト ↓$/));

      expect(siteNames(container)).toEqual(["a-site", "b-site", "c-site"]);
      expect(getByText(/^サイト ↑$/)).toBeInTheDocument();
    });

    it("ステータス列で並べ替え、別の列に切り替えると昇順に戻り、前の列の印は消える", () => {
      const { container, getByText, queryByText } = render(<PostsTable posts={posts} timezone={null} />);

      fireEvent.click(getByText("ステータス"));
      expect(siteNames(container)).toEqual(["a-site", "c-site", "b-site"]); // draft, future, publish
      fireEvent.click(getByText(/^ステータス ↑$/));
      expect(siteNames(container)).toEqual(["b-site", "c-site", "a-site"]);

      fireEvent.click(getByText("サイト"));
      expect(siteNames(container)).toEqual(["a-site", "b-site", "c-site"]);
      expect(queryByText(/^ステータス/)).toHaveTextContent("ステータス");
      expect(queryByText(/^ステータス [↑↓]$/)).toBeNull();
    });

    it("最終投稿日時列: 日時が無い投稿は最も古いものとして扱う", () => {
      const { container, getByText } = render(<PostsTable posts={posts} timezone={null} />);

      fireEvent.click(getByText("最終投稿日時"));
      expect(siteNames(container)).toEqual(["a-site", "c-site", "b-site"]);

      fireEvent.click(getByText(/^最終投稿日時 ↑$/));
      expect(siteNames(container)).toEqual(["b-site", "c-site", "a-site"]);
    });
  });
});
