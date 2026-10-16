import { renderToString } from "react-dom/server";
import type { ConnectedServiceStatusDetail } from "@/lib/apiClient";

/**
 * issue #1362: 「マウント前は固定プレースホルダーを表示する」(ThemeSwitcher.tsx:23-58と
 * 同じmountedフラグ方式)を単独のファイルで検証する。
 *
 * マウント前の描画は、`useEffect`を走らせないサーバー描画(`renderToString`)で得る。これなら
 * `react`も`@/lib/formatDate`も差し替えずに済む(#1832)。
 */
import { ConnectedServiceStatusPanel } from "../ConnectedServiceStatusPanel";

function detail(overrides: Partial<ConnectedServiceStatusDetail> = {}): ConnectedServiceStatusDetail {
  return {
    id: "1",
    name: "keycloak",
    status: "NORMAL",
    responseTimeMs: 12,
    httpStatus: 200,
    errorMessage: null,
    impact: null,
    computeDevice: null,
    targetUrl: "https://example.test/health",
    checkedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("ConnectedServiceStatusPanel(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は固定プレースホルダーを表示する(ThemeSwitcher.tsx:23-58と同じmountedフラグ方式、issue #1362)", () => {
    const html = renderToString(
      <ConnectedServiceStatusPanel initialStatuses={[]} initialDetail={[detail()]} personalTimeZone={null} />
    );

    expect(html).not.toContain("2026/9/8");
    expect(html).toContain("読み込み中…");
  });
});
