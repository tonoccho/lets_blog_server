import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect, test } from '@playwright/test';
import { prefetchAccessTokensForAllE2eAccounts } from '../helpers';

/**
 * 段階2: 受け入れテスト環境のシード(issue #945 / AT-19)。
 *
 * Playwright の「setup プロジェクト」として実行する。Gherkin にしていないのは、
 * これが受け入れ**基準**ではなく環境構築の手順であり、利用者から見たふるまいを
 * 持たないため。`.feature` に書くと「シナリオ一覧」に環境構築が混ざる。
 *
 * 実体は `scripts/seed-acceptance-env.sh`。ロジックをここへ写さないこと。
 * ホストのシェルから単独で実行できることに意味がある(単一ドメインのテストだけを
 * 回したいとき、Playwright を通さずシードできる)。
 *
 * 段階順: reset(スクリプト) → at-setup → **at-seed** → at-provision → at-main
 *
 * issue #1295フォローアップ(レビュー指摘、note 7363): シード完了直後、両方のE2E合成
 * アカウントのアクセストークンをここで一度だけ先取りする(`prefetchAccessTokensForAllE2eAccounts`)。
 * `at-seed`は単一プロセス・単一テストとして必ずこの後続の並列ワーカー(`at-provision`/
 * `at-main`)より先に完了するため、ここで書いた共有キャッシュファイルにより、後続の
 * 全ワーカーが起動直後から実HTTPリクエストなしでトークンを再利用できる。これにより
 * 「複数ワーカーがほぼ同時に起動し、同じアカウントへ独立にトークンを要求する」という
 * 障害モード(#1295本体の再現条件)そのものを回避する。詳細は`../token-cache`のコメントと
 * 実装報告(issue #1295コメント)参照。
 */
const REPO_ROOT = path.resolve(__dirname, '../../../..');

test('受け入れテスト環境にシードを投入する', async ({ request }) => {
  // provision-e2e-keycloak-users.sh は Keycloak Admin CLI とユーザー作成APIを叩くため、
  // 30秒では終わらないことがある。
  test.setTimeout(180_000);

  const missing = [
    'E2E_TEST_PASSWORD',
    'E2E_ADMIN_PASSWORD',
    'E2E_PROVISION_ADMIN_EMAIL',
    'E2E_PROVISION_ADMIN_PASSWORD',
  ].filter((name) => !process.env[name]);

  expect(
    missing,
    `シードに必要な環境変数が未設定です: ${missing.join(', ')}\n`
      + '~/.config/lets-blog-e2e.env を source してから実行してください'
      + '(docs/ACCEPTANCE_TESTING.md の「クリーンスレート実行」節)。'
  ).toEqual([]);

  const output = execFileSync('./scripts/seed-acceptance-env.sh', {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  console.log(output);

  // シードでアカウントが作成された直後(=作成前に呼ぶと401になる)、後続の並列ワーカーが
  // 起動する前にここで両アカウント分のトークンを一度だけ取得しておく(#1295フォローアップ)。
  console.log('[e2e] 両E2Eアカウントのアクセストークンを先取りします(ワーカー起動前)');
  await prefetchAccessTokensForAllE2eAccounts(request);
});
