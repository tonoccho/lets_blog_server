import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";

/**
 * issue #1363: `timezone`(個人設定TZ)が設定されているとき、一覧のキャプション(:195)と
 * 詳細ダイアログ(:341)の両方が`formatDateTime`にそのTZをそのまま渡すこと(ゲート無し)を
 * 確かめる。「未設定のとき、マウント後はTZ引数無しで呼ぶ」分岐は
 * `ImageGalleryGrid.test.tsx`(既存、常に`timezone={null}`でレンダリングしている)側で
 * 既にカバーされているため、ここでは追加しない(#1362の
 * ConnectedServiceStatusPanel.test.tsxと同じ役割分担)。
 */
jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn((iso: string, tz?: string | null) => `FORMATTED(${iso}|${tz})`),
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
  folderId: null,
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

describe("ImageGalleryGrid 個人設定TZあり(issue #1363)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL);
  });

  it("一覧のキャプションはformatDateTimeに個人設定TZを渡す", () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone="Asia/Tokyo" />);

    expect(formatDateTime).toHaveBeenCalledWith(SUMMARY.createdAt, "Asia/Tokyo");
    expect(screen.getByText(`FORMATTED(${SUMMARY.createdAt}|Asia/Tokyo)`)).toBeInTheDocument();
  });

  it("詳細ダイアログの作成日時もformatDateTimeに個人設定TZを渡す", async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone="Asia/Tokyo" />);

    fireEvent.click(screen.getByAltText("a cute cat"));

    await waitFor(() => {
      expect(screen.getByText("作成日時")).toBeInTheDocument();
    });

    expect(formatDateTime).toHaveBeenCalledWith(DETAIL.createdAt, "Asia/Tokyo");
  });
});
