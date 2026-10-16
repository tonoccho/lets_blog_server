import { renderToString } from "react-dom/server";
import type { SavedSshKeyPair } from "@/lib/apiClient";

/**
 * issue #1362: 「マウント前は固定プレースホルダーを表示する」(ThemeSwitcher.tsx:23-58と
 * 同じmountedフラグ方式)を検証する。
 *
 * マウント前の状態は、`react` の `useEffect` を差し替えて作らず、サーバー描画(`renderToString`。
 * effect は走らない)で作る。実際にサーバーが返す HTML と同じ状態を見ることになる(#1833)。
 *
 * issue #1363: `TIMEZONE_PENDING_PLACEHOLDER` は `formatDate.ts` に共有化されている。
 */
// 自前の @/lib/apiClient・@/lib/session・react・ViewerDateTimeは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { SshKeyPairsPanel } from "../SshKeyPairsPanel";

function keyPair(overrides: Partial<SavedSshKeyPair> = {}): SavedSshKeyPair {
  return {
    id: 1,
    name: "production-deploy",
    comment: null,
    publicKeyLine: "ssh-ed25519 AAAA... production-deploy",
    createdAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("SshKeyPairsPanel(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は固定プレースホルダーを表示する(ThemeSwitcher.tsx:23-58と同じmountedフラグ方式、issue #1362)", () => {
    const html = renderToString(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    expect(html).not.toContain("2026/9/8");
    expect(html).toContain("読み込み中…");
  });

  it("個人設定TZがあれば、マウント前から個人設定TZで日時を表示する", () => {
    const html = renderToString(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone="Asia/Tokyo" />);

    expect(html).toContain("2026/9/9 5:03:35");
    expect(html).not.toContain("読み込み中…");
  });

  /**
   * issue #1413: ハイドレーション完了前は生成ボタンを押せないようにする。
   *
   * `<form onSubmit={handleGenerate} method="post">` の `method="post"` は #1051 の
   * 緩和策(JS未実行時に素のGET送信へフォールバックして入力値がURL・アクセスログ・
   * Refererへ漏れるのを防ぐ)であって、JS無効時に機能させるためのものではない。
   * `/admin/ssh-keys` にPOSTハンドラは無く、#1051 本文も「サーバー側は GET を処理
   * しないため、利用者から見ると『作成ボタンを押しても何も起きない』だけ」と述べている。
   *
   * ハイドレーション前は `onSubmit` が未結線なので、クリックはネイティブPOSTになり
   * ページが遷移し、鍵は生成されず入力値だけが失われる。リリース検証 run 11
   * (`20260924T194725Z-3220380`)はこれで停止した — Playwrightのログに
   * `navigated to "https://localhost/admin/ssh-keys"` が残っている。
   *
   * 受け入れテスト側の再試行では直せない。`retryClick.ts` の `clickUntilVisible` は
   * ヘルパー自身が「べき等な操作にのみ使うこと。送信系に使うと二重実行になる」と
   * 明記しており、鍵ペア生成は非べき等だからである。
   *
   * Playwright の actionability チェックは `enabled` を待つため、製品側でここを塞げば
   * 受け入れテストは無変更のまま競合が消える。
   */
  it("マウント前は生成ボタンを押せない(ネイティブPOSTへのフォールバックを塞ぐ、issue #1413)", () => {
    const html = renderToString(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>SSH鍵ペアを生成<\/button>/);
  });
});
