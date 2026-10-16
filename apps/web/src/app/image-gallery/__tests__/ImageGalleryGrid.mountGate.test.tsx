import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)をこのファイル単独で検証する。
 *
 * マウント前の状態は、`react` の `useEffect` や `@/lib/formatDate` を差し替えて作らず、
 * サーバー描画(`renderToString`。effect は走らない)と、詳細ダイアログが開いた直後の DOM の変化の記録で見る(#1834)。
 * 自前の Server Action・ViewerDateTime・formatDate は差し替えない。差し替えるのは Next.js の実行基盤と fetch だけ。
 *
 * `ImageGalleryGrid`は一覧のキャプションと詳細ダイアログの2箇所で日時を整形するため、両方のプレースホルダー表示をここで確かめる。
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

describe("ImageGalleryGrid(マウント前)", () => {
  beforeEach(() => {
    installGallery();
    backend.getImage.mockResolvedValue(DETAIL);
  });

  it("個人設定TZが未設定のとき、マウント前は一覧のキャプションに固定プレースホルダーを表示する(#1362と同じmountedフラグ方式、issue #1363)", () => {
    const html = renderToString(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />);

    expect(html).toContain("読み込み中…");
    expect(html).not.toContain("2026/9/8");
  });

  it("個人設定TZが未設定のとき、詳細ダイアログの作成日時は、描画の直後にはプレースホルダーで、マウント後に整形した日時になる(issue #1363)", async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />);
    // マウント済みの一覧のあとで開くダイアログの日時が、最初の描画で何を出したかを DOM の変化から読む。
    const placeholders: string[] = [];
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        // 日時の文字列は、同じ文字ノードの書き換え(characterData)として変わる。
        if (record.type === "characterData" && record.target.parentElement?.tagName === "DD") {
          placeholders.push(record.oldValue ?? "");
        }
      }
    });
    observer.observe(document.body, { characterData: true, characterDataOldValue: true, subtree: true });

    fireEvent.click(screen.getByRole("button", { name: "a cute catの詳細を表示" }));
    const createdAtLabel = await screen.findByText("作成日時");
    await waitFor(() => expect(createdAtLabel.nextElementSibling?.textContent).toBe("2026/9/8 20:03:35"));
    observer.disconnect();

    expect(placeholders).toContain("読み込み中…");
  });
});
