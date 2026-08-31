import { createBdd, test as base } from 'playwright-bdd';

/**
 * 全ステップ定義が共有する `test` インスタンス(issue #926 / AT-0)。
 *
 * playwright-bdd は `.feature` から Playwright のテストファイルを生成する。生成物は
 * ステップ定義が使っている `test`(= このファイルの `test`)を import するため、
 * ドメイン固有のフィクスチャ(ログイン済みページ、プロジェクトのフィクスチャ等)を
 * 足すときは必ずここの `test` を `test.extend()` で拡張すること。ステップ定義ファイルごとに
 * 別々の `test` から `createBdd()` を呼ぶと、生成時にどれを import すべきか決まらず落ちる。
 */
export const test = base;

export const { Given, When, Then, Before, After, BeforeAll, AfterAll } = createBdd(test);
