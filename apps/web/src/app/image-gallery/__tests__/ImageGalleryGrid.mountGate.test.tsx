import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)をこのファイル単独で検証する。理由は
 * SshKeyPairsPanel.mountGate.test.tsx の先頭コメントと同じ(`"use client"`を持つ
 * コンポーネントでは`jest.spyOn(React, "useEffect")`が効かず、`jest.mock("react", ...)`
 * によるモジュール差し替えが必要。他のテスト(この画像の設定をコピー等)を巻き添えに
 * しないためファイルを分離する)。
 *
 * `ImageGalleryGrid`は一覧のキャプション(:195)と詳細ダイアログ(:341)の2箇所で
 * `formatDateTime`を呼ぶため、両方のプレースホルダー表示をここで確かめる。
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

jest.mock("../actions", () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
}));

import { ImageGalleryGrid } from "../ImageGalleryGrid";
import { formatDateTime } from "@/lib/formatDate";
import * as actions from "../actions";

const SUMMARY: GeneratedImageSummary = {
  id: 1,
  projectId: 2,
  prompt: "a cute cat",
  checkpoint: "model.safetensors",
  createdAt: "2026-09-08T20:03:35",
  tags: [],
  provider: "COMFYUI",
};

const DETAIL: GeneratedImageDetail = {
  ...SUMMARY,
  negativePrompt: "blurry, low quality",
  steps: 20,
  cfgScale: 7,
  samplerName: "euler",
  scheduler: "normal",
  seed: 12345,
  width: 1920,
  height: 1080,
  batchSize: 1,
  batchIndex: 0,
  loraName: null,
  loraWeight: null,
};

describe("ImageGalleryGrid(マウント前)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL);
  });

  it("個人設定TZが未設定のとき、マウント前は一覧のキャプションに固定プレースホルダーを表示する(#1362と同じmountedフラグ方式、issue #1363)", () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_CREATED_AT")).not.toBeInTheDocument();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  });

  it("個人設定TZが未設定のとき、マウント前は詳細ダイアログの作成日時にも固定プレースホルダーを表示する(issue #1363)", async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />);

    fireEvent.click(screen.getByAltText("a cute cat"));

    let createdAtLabel: HTMLElement;
    await waitFor(() => {
      createdAtLabel = screen.getByText("作成日時");
    });

    expect(formatDateTime).not.toHaveBeenCalled();
    // `isPending`中に出る「読み込み中…」(取得中インジケータ、本Issueとは無関係)と
    // 文言が偶然一致するため、`getAllByText`ではなく「作成日時」の`<dt>`に隣接する
    // `<dd>`をピンポイントで見て取り違えを避ける。
    expect(createdAtLabel!.nextElementSibling?.textContent).toBe("読み込み中…");
  });
});
