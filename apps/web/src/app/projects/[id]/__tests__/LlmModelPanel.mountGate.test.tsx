import { render, screen } from "@testing-library/react";

/**
 * issue #1414(#1413の横展開): ハイドレーション完了前は「保存」ボタンを押せないようにする。
 * useEffect を no-op にして「マウント前」を再現するため単独ファイルにする
 * (SshKeyPairsPanel.mountGate.test.tsx と同じ事情)。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("../actions", () => ({
  fetchLlmModelsAction: jest.fn(),
  selectLlmModelAction: jest.fn(),
}));

import { LlmModelPanel } from "../LlmModelPanel";

describe("LlmModelPanel(マウント前)", () => {
  it("マウント前は保存ボタンを押せない(ネイティブPOSTへのフォールバックを塞ぐ、issue #1414)", () => {
    render(<LlmModelPanel projectId={1} initialData={{ selected: "gpt-4o-mini", availableModels: [] }} />);

    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
  });
});
