import { test as base } from 'playwright-bdd';
import { createBdd } from 'playwright-bdd';

/**
 * 全ステップ定義が共有する `test` インスタンス(issue #926 / AT-0)。
 *
 * playwright-bdd は `.feature` から Playwright のテストファイルを生成する。生成物が
 * どの `test` を import するかは `playwright.config.ts` の `importTestFrom` が決めるため、
 * ドメイン固有のフィクスチャ(ログイン済みページ、プロジェクトのフィクスチャ等)を
 * 足すときは必ずこのファイルの `test` を拡張すること。ステップ定義ごとに
 * `createBdd()` を呼び直すと、フィクスチャの型が食い違って生成時に落ちる。
 */
export const test = base;

export const { Given, When, Then, Before, After, BeforeAll, AfterAll } = createBdd(test);
