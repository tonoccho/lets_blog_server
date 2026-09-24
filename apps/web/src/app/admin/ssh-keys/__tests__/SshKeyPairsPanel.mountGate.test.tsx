import { render, screen } from "@testing-library/react";
import type { SavedSshKeyPair } from "@/lib/apiClient";

/**
 * issue #1362: 「マウント前は固定プレースホルダーを表示する」(ThemeSwitcher.tsx:23-58と
 * 同じmountedフラグ方式)を単独のファイルで検証する。理由は
 * ConnectedServiceStatusPanel.mountGate.test.tsx の先頭コメントと同じ
 * (`"use client"`を持つコンポーネントでは`jest.spyOn(React, "useEffect")`が効かず、
 * `jest.mock("react", ...)`によるモジュール差し替えが必要。他のテストを巻き添えに
 * しないためファイルを分離する)。
 *
 * issue #1363: `TIMEZONE_PENDING_PLACEHOLDER` はこのファイルが元々ローカル定数として
 * 持っていたが、`ImageGalleryGrid.tsx` 等3つ目以降の利用先が増えたため `formatDate.ts` へ
 * 共有化した(Requirement 2)。このモックは実モジュールを差し替えるため、
 * 実装がその値を import できるよう明示的に含める。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CREATED_AT"),
  TIMEZONE_PENDING_PLACEHOLDER: "読み込み中…",
}));

// SshKeyPairsPanel.tsx が実際に import する ./actions は next/cache 経由でReactの内部APIに
// 触れるため、上の react モジュール差し替え(スプレッドで内部プロパティが欠落しうる)と
// 組み合わせると壊れる。SshKeyPairsPanel.test.tsx と同じくモックで置き換える
// (このテストは生成・削除アクションを一切呼ばないため実体は不要)。
jest.mock("../actions", () => ({
  createSshKeyPairAction: jest.fn(),
  deleteSshKeyPairAction: jest.fn(),
}));

import { SshKeyPairsPanel } from "../SshKeyPairsPanel";
import { formatDateTime } from "@/lib/formatDate";

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
    const pair = keyPair();
    render(<SshKeyPairsPanel keyPairs={[pair]} personalTimeZone={null} />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_CREATED_AT")).not.toBeInTheDocument();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
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
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(screen.getByRole("button", { name: "SSH鍵ペアを生成" })).toBeDisabled();
  });
});
