import { createBdd, test as base } from 'playwright-bdd';

/**
 * シナリオ内でステップ間の値を受け渡すための入れ物(issue #929)。
 *
 * ステップ定義をモジュール変数で繋ぐと、同じファイル内のシナリオが並列実行されたときに
 * 互いの値を壊す。テストごとに作られるフィクスチャに持たせることで、その事故を構造的に防ぐ。
 *
 * 型は意図的にゆるくしてある。ドメインごとに必要な項目は増えるが、ここへ共用の型を
 * 積み上げると全ドメインのステップ定義が1つの型に結合してしまう。
 */
export type ScenarioContext = Record<string, unknown>;

type Fixtures = {
  /** このシナリオ限りの作業領域。ステップ間の受け渡しに使う。 */
  ctx: ScenarioContext;
};

/**
 * 全ステップ定義が共有する `test` インスタンス(issue #926 / AT-0)。
 *
 * playwright-bdd は `.feature` から Playwright のテストファイルを生成する。生成物は
 * ステップ定義が使っている `test`(= このファイルの `test`)を import するため、
 * ドメイン固有のフィクスチャ(ログイン済みページ、プロジェクトのフィクスチャ等)を
 * 足すときは必ずここの `test` を `test.extend()` で拡張すること。ステップ定義ファイルごとに
 * 別々の `test` から `createBdd()` を呼ぶと、生成時にどれを import すべきか決まらず落ちる。
 */
export const test = base.extend<Fixtures>({
  ctx: async ({}, use) => {
    await use({});
  },
});

export const { Given, When, Then, Step, Before, After, BeforeAll, AfterAll } = createBdd(test);
