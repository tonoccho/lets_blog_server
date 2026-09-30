/**
 * ギャラリーの1ページぶんの件数(issue #1472)。
 *
 * 24 にしたのは、グリッドの列数(2 / 3 / 4、`ImageGalleryGrid` の `grid-cols-*`)のどれでも
 * 最後の行が欠けず割り切れ、かつ1画面に収まる枚数(4列で6行)を大きく超えない値だから。
 * `/api/generated-images` の limit 上限(100)以下であること。
 * 受け入れテスト(`e2e/steps/media.steps.ts` の `GALLERY_PAGE_SIZE`)と一致させる。
 *
 * `actions.ts`("use server")は async 関数しか export できないので、定数は別ファイルに置く。
 */
export const GALLERY_PAGE_SIZE = 24;
