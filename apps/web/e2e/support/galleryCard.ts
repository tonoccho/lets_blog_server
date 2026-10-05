import type { Locator, Page } from '@playwright/test';

/**
 * issue #1614: 生成画像ギャラリーのカード。カード本体のクリックは選択の切り替え、詳細は右上の
 * 虫眼鏡ボタン(アクセシブルネーム「<画像名>の詳細を表示」)から開く。
 */

/** サムネイル画像を含むカード(`ImageGalleryGrid` の `relative` なコンテナ)。 */
export function galleryCardOf(thumbnail: Locator): Locator {
  return thumbnail.locator('xpath=ancestor::div[contains(@class,"relative")][1]');
}

/** サムネイルのカードの虫眼鏡ボタン。 */
export function magnifierOf(thumbnail: Locator): Locator {
  return galleryCardOf(thumbnail).getByRole('button', { name: /の詳細を表示$/ });
}

/** 「N件選択中」の表示。 */
export function selectedCountText(page: Page, count: number): Locator {
  return page.getByText(`${count}件選択中`, { exact: true });
}
