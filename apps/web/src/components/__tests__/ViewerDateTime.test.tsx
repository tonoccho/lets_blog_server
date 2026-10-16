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
import { ViewerDateTime } from "../ViewerDateTime";

describe("ViewerDateTime", () => {
  it("個人設定TZが設定されているとき、formatDateTimeにそのTZを渡す(issue #1364、gateなし)", () => {
    render(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone="Asia/Tokyo" />);

    // オフセット無しはUTCとして解釈され、個人設定TZ(Asia/Tokyo, +9)の壁時計で出る。
    expect(screen.getByText("2026/9/9 5:03:35")).toBeInTheDocument();
  });

  it("個人設定TZが未設定のとき、マウント後はformatDateTimeをTZ引数無しで呼ぶ(issue #1364)", () => {
    render(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone={null} />);

    // jest はTZをUTC固定で動かす(jest.config.ts)ので、ブラウザ既定TZ=UTCの壁時計で出る。
    expect(screen.getByText("2026/9/8 20:03:35")).toBeInTheDocument();
  });
});
