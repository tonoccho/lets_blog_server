import { render, screen } from "@testing-library/react";

/**
 * issue #1364(親issue #1261 分割C): `/users`・`/projects`・`/posts`(サーバー
 * コンポーネント)が直接`formatDateTime`を呼んでいたため、閲覧者のブラウザTZに従えず、
 * ハイドレーション不一致にもなり得た。`ConnectedServiceStatusPanel.tsx`(issue #1362)・
 * `SshKeyPairsPanel.tsx`・`PostsTable.tsx`(issue #1363)がそれぞれ複製していた
 * ゲート(個人設定TZがあれば無条件、無ければマウント後にブラウザTZで表示)を
 * `ViewerDateTime`へ共通化した。マウント前のプレースホルダー表示は
 * `ViewerDateTime.mountGate.test.tsx`で別途検証する。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_DATETIME"),
  TIMEZONE_PENDING_PLACEHOLDER: "読み込み中…",
}));

import { ViewerDateTime } from "../ViewerDateTime";
import { formatDateTime } from "@/lib/formatDate";

describe("ViewerDateTime", () => {
  beforeEach(() => {
    (formatDateTime as jest.Mock).mockClear();
  });

  it("個人設定TZが設定されているとき、formatDateTimeにそのTZを渡す(issue #1364、gateなし)", () => {
    render(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone="Asia/Tokyo" />);

    expect(formatDateTime).toHaveBeenCalledWith("2026-09-08T20:03:35", "Asia/Tokyo");
    expect(screen.getByText("FORMATTED_DATETIME")).toBeInTheDocument();
  });

  it("個人設定TZが未設定のとき、マウント後はformatDateTimeをTZ引数無しで呼ぶ(issue #1364)", () => {
    render(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone={null} />);

    expect(formatDateTime).toHaveBeenCalledWith("2026-09-08T20:03:35");
    expect(screen.getByText("FORMATTED_DATETIME")).toBeInTheDocument();
  });
});
