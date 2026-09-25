import { render, screen } from "@testing-library/react";
import type { ArticlePlanSessionSummary } from "@/lib/apiClient";

/**
 * issue #1366(親issue #1261 分割B-2): 「マウント前は固定プレースホルダーを表示する」
 * (#1362・#1363と同じmountedフラグ方式)をこのファイル単独で検証する。理由は
 * PostsTable.mountGate.test.tsxの先頭コメントと同じ(`"use client"`を持つコンポーネント
 * では`jest.spyOn(React, "useEffect")`が効かず、`jest.mock("react", ...)`による
 * モジュール差し替えが必要。他のテストを巻き添えにしないためファイルを分離する)。
 *
 * `ArticlePlanSessionList`は`{formatSessionDate(s.createdAt)}-{s.title}`のように
 * `YYYYMMDD`を文字列へ埋め込む形(`ArticlePlanSessionList.tsx:62`)なので、プレースホルダーは
 * 日付部分だけを置き換え、`-{s.title}`はそのまま残る(Readiness Report参照)。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("@/lib/formatDate", () => ({
  formatDateYYYYMMDD: jest.fn(() => "FORMATTED_YYYYMMDD"),
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

describe("ArticlePlanSessionList(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は作成日部分に固定プレースホルダーを表示し、タイトル部分は変えない(#1362と同じmountedフラグ方式、issue #1366)", () => {
    render(
      <ArticlePlanSessionList
        sessions={[session()]}
        activeSessionId={null}
        onSelect={() => {}}
        onNewChat={() => {}}
        isLoading={false}
        timezone={null}
      />
    );

    expect(formatDateYYYYMMDD).not.toHaveBeenCalled();
    expect(screen.queryByText(/FORMATTED_YYYYMMDD/)).not.toBeInTheDocument();
    expect(screen.getByText(/読み込み中…-E2Eスタブの企画テーマ/)).toBeInTheDocument();
  });
});
