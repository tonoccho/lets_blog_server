import { render, screen } from "@testing-library/react";
import type { PostSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)をこのファイル単独で検証する。理由は
 * SshKeyPairsPanel.mountGate.test.tsx の先頭コメントと同じ(`"use client"`を持つ
 * コンポーネントでは`jest.spyOn(React, "useEffect")`が効かず、`jest.mock("react", ...)`
 * によるモジュール差し替えが必要。他のテストを巻き添えにしないためファイルを分離する)。
 *
 * `PostsTable`は最終投稿日時(:87)と公開予定日時(:90)の2箇所で`formatDateTime`を
 * 呼ぶため、両方のプレースホルダー表示をここで確かめる。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_DATETIME"),
  TIMEZONE_PENDING_PLACEHOLDER: "読み込み中…",
}));

import { PostsTable } from "../PostsTable";
import { formatDateTime } from "@/lib/formatDate";

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
    render(<PostsTable posts={[post()]} timezone={null} />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_DATETIME")).not.toBeInTheDocument();
    expect(screen.getAllByText("読み込み中…")).toHaveLength(2);
  });
});
