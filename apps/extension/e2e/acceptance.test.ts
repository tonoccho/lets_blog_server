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
import { adminAccessToken, loggedInAdminContext } from './support/env';
import { ensureManagedSite } from './support/api';
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
import './steps/rejectionEdit.steps';
import './steps/previewReview.steps';
import './steps/previewSignedUrl.steps';
import './steps/media.steps';
import './steps/diagrams.steps';
import './steps/failures.steps';

/**
 * 公開先のマネージドWordPressサイトは、全シナリオの前に1回だけここで用意する(issue #1309)。
 * 構築には数十秒〜数分かかるため、シナリオの中では行わない。ステップ
 * 「公開先のマネージドWordPressサイトが用意されている」は探して紐付けるだけで、
 * 無ければここで用意されていないことを示して失敗する。`test:at` と `test:at:fast` の両方で動く。
 * タイムアウトは jest.config.js の testTimeout が効く。
 */
beforeAll(async () => {
  const context = await loggedInAdminContext();
  const site = await ensureManagedSite(await adminAccessToken(context));
  console.log(`[beforeAll] マネージドWordPressサイトを用意しました: ${site.siteKey} (id=${site.id})`);
});

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
