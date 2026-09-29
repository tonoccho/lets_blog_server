import { fireEvent, render, screen } from "@testing-library/react";
import type { TermComparisonPage, TermEnvironmentValue } from "@/lib/apiClient";

/**
 * issue #1414(#1413の横展開): 新規追加・編集フォームの送信ボタンは、ハイドレーション完了前は
 * 押せないようにする。useEffect を no-op にして「マウント前」を再現するため単独ファイルにする
 * (SshKeyPairsPanel.mountGate.test.tsx と同じ事情)。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("../actions", () => ({
  applyToEnvironmentAction: jest.fn(),
  syncTermToMasterAction: jest.fn(),
  deleteTermEverywhereAction: jest.fn(),
  fetchTermComparisonAction: jest.fn(),
  editTermAndSyncAction: jest.fn(),
  syncAllTermsToMasterAction: jest.fn(),
}));

import { TermComparisonTable } from "../TermComparisonTable";

const value: TermEnvironmentValue = {
  available: true,
  error: false,
  errorMessage: null,
  slug: "news",
  parentSlug: null,
  description: null,
};

const initialPage: TermComparisonPage = {
  items: [{ name: "お知らせ", slug: "news", local: value, test: value, production: value }],
  page: 0,
  size: 20,
  totalCount: 1,
  masterEnvironment: "production",
};

describe("TermComparisonTable(マウント前)", () => {
  it("マウント前は新規追加フォームの送信ボタンを押せない(issue #1414)", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={initialPage} />);

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));

    expect(screen.getByRole("button", { name: "マスター環境に追加" })).toBeDisabled();
  });

  it("マウント前は編集フォームの送信ボタンを押せない(issue #1414)", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={initialPage} />);

    fireEvent.click(screen.getByRole("button", { name: "編集" }));

    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeDisabled();
  });
});
