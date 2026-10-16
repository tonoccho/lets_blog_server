import { renderToString } from "react-dom/server";

/**
 * issue #1364(親issue #1261 分割C): 「マウント前は固定プレースホルダーを表示する」
 * (#1362/#1363 と同じ mounted フラグ方式)をこのファイル単独で検証する。
 *
 * マウント前の描画は、`useEffect`を走らせないサーバー描画(`renderToString`)で得る。`react`や
 * `@/lib/formatDate`を差し替える必要は無い(#1832)。
 */
import { ViewerDateTime } from "../ViewerDateTime";

describe("ViewerDateTime(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は固定プレースホルダーを表示する(ThemeSwitcher.tsx:23-58と同じmountedフラグ方式、issue #1364)", () => {
    const html = renderToString(<ViewerDateTime iso="2026-09-08T20:03:35" personalTimeZone={null} />);

    expect(html).not.toContain("2026/9/8");
    expect(html).toContain("読み込み中…");
  });
});
