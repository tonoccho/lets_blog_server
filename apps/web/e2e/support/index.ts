/**
 * 受け入れテスト(BDD)からの既存 E2E 資産への入口(issue #926 / AT-0)。
 *
 * `web/e2e/helpers.ts` は Playwright 直書きの13 specが依存している。AT-3〜AT-18 で
 * spec を `.feature` へ段階移行していく間、helpers は両方から使われる。そこで helpers を
 * `support/` へ「移設」せず、ここから再エクスポートするだけに留める。
 *
 * - 既存 spec は `./helpers` を、ステップ定義は `../support` を参照する
 * - helpers の中身が AT-19 等で整理されても、参照点はこの1ファイルで吸収できる
 * - 移行が完了した時点で helpers.ts の実体をこのディレクトリへ移せばよい
 *
 * ステップ定義から Playwright の生API(`expect` 等)を使う場合も、ここを経由させる。
 */
export * from '../helpers';
export { expect } from '@playwright/test';
