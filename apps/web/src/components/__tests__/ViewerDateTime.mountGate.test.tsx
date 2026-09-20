import { render, screen } from "@testing-library/react";

/**
 * issue #1364(親issue #1261 分割C): 「マウント前は固定プレースホルダーを表示する」
 * (#1362/#1363 と同じ mounted フラグ方式)をこのファイル単独で検証する。理由は
 * `ConnectedServiceStatusPanel.mountGate.test.tsx` の先頭コメントと同じ
 * (`"use client"`を持つコンポーネントでは`jest.spyOn(React, "useEffect")`が効かず、
 * `jest.mock("react", ...)`によるモジュール差し替えが必要。他のテストを巻き添えに
 * しないためファイルを分離する)。
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

import { ViewerDateTime } from "../ViewerDateTime";
import { formatDateTime } from "@/lib/formatDate";

describe("ViewerDateTime(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は固定プレースホルダーを表示する(ThemeSwitcher.tsx:23-58と同じmountedフラグ方式、issue #1364)", () => {
    render(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone={null} />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_DATETIME")).not.toBeInTheDocument();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  });
});
