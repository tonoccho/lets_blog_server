/**
 * Layer 1(APIレベル受け入れテスト)の入口(issue #942 / AT-16)。
 *
 * `e2e/features/**\/*.feature` を jest のテストへ変換して実行する。実行前提と実行方法は
 * apps/extension/e2e/README.md、記法とタグの意味は docs/ACCEPTANCE_TESTING.md を参照。
 *
 * 1ファイルにまとめているのは、シナリオ同士が同じスタック・同じスタブの状態を共有するため。
 * jest はファイル単位で並列化するので、束ねることで直列実行を保証する。
 */

import * as path from 'path';
import { BeforeScenario, runFeatures } from './support/gherkin';
import { requireStubs } from './support/stubs';

// ステップ定義の登録。import した時点で Given/When/Then が登録される。
import './steps/common.steps';
import './steps/auth.steps';
import './steps/projects.steps';
import './steps/articles.steps';
import './steps/articleBranch.steps';
import './steps/articleSubmit.steps';
import './steps/urlPaste.steps';
import './steps/ai.steps';
import './steps/reviewSteps.steps';
import './steps/publishReview.steps';
import './steps/rejectionNotification.steps';
import './steps/previewReview.steps';
import './steps/media.steps';
import './steps/diagrams.steps';
import './steps/failures.steps';

/**
 * `@stub` が付いたシナリオは、スタブが起動していなければスキップではなく失敗させる
 * (docs/ACCEPTANCE_TESTING.md §9)。
 */
BeforeScenario(async (tags) => {
  if (tags.includes('@stub')) {
    await requireStubs();
  }
});

runFeatures(path.join(__dirname, 'features'));
