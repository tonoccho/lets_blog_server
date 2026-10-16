import { fireEvent, render, screen } from "@testing-library/react";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";

/**
 * issue #1363: `timezone`(個人設定TZ)が設定されているとき、一覧のキャプション(:195)と
 * 詳細ダイアログ(:341)の両方が`formatDateTime`にそのTZをそのまま渡すこと(ゲート無し)を
 * 確かめる。「未設定のとき、マウント後はTZ引数無しで呼ぶ」分岐は
 * `ImageGalleryGrid.test.tsx`(既存、常に`timezone={null}`でレンダリングしている)側で
 * 既にカバーされているため、ここでは追加しない(#1362の
 * ConnectedServiceStatusPanel.test.tsxと同じ役割分担)。
 */
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

import { ImageGalleryGrid } from "../ImageGalleryGrid";
import { backend, installGallery } from "./galleryBackend";

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
    installGallery();
    backend.getImage.mockResolvedValue(DETAIL);
  });

  it("一覧のキャプションはformatDateTimeに個人設定TZを渡す", () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone="Asia/Tokyo" />);

    // 2026-09-08T20:03:35(オフセット無しは UTC)は Asia/Tokyo では翌日 5:03:35。
    expect(screen.getByText("2026/9/9 5:03:35")).toBeInTheDocument();
  });

  it("詳細ダイアログの作成日時もformatDateTimeに個人設定TZを渡す", async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone="Asia/Tokyo" />);

    fireEvent.click(screen.getByRole('button', { name: 'a cute catの詳細を表示' }));

    const createdAtLabel = await screen.findByText("作成日時");
    expect(createdAtLabel.nextElementSibling?.textContent).toBe("2026/9/9 5:03:35");
  });
});
