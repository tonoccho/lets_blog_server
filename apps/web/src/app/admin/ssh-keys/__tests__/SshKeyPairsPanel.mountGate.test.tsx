import { render, screen } from "@testing-library/react";
import type { SavedSshKeyPair } from "@/lib/apiClient";

/**
 * issue #1362: 「マウント前は固定プレースホルダーを表示する」(ThemeSwitcher.tsx:23-58と
 * 同じmountedフラグ方式)を単独のファイルで検証する。理由は
 * ConnectedServiceStatusPanel.mountGate.test.tsx の先頭コメントと同じ
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
  formatDateTime: jest.fn(() => "FORMATTED_CREATED_AT"),
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
});
