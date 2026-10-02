/**
 * WordPress の実体(wp_posts の行)をテストから直接観測する(issue #1412)。
 *
 * content-service の `GET /api/posts/{site}/by-slug/{slug}` は自前DBを返すだけで、WordPress へは
 * 問い合わせない。「WordPress の行が恒久削除されたのに content-service の記録だけ trash」という
 * 状態はそこからは見えないため、wordpress コンテナの wp-cli を `docker compose exec` で叩く。
 *
 * 製品に本番用途の無い内部APIを足さないための選択である。同じ経路には
 * `apps/web/e2e/helpers.ts` の `composeServiceControl` という前例がある。
 */

import { execFileSync } from 'child_process';
import * as path from 'path';

const REPO_ROOT = path.resolve(__dirname, '../../../..');

/**
 * 投稿の `post_status` を返す。行が存在しない(恒久削除済み)ときは `null`。
 * wp-cli の失敗が「対象なし」以外の理由なら、`null` と取り違えないよう例外にする。
 */
export function wordpressPostStatus(siteKey: string, postId: string): string | null {
  try {
    const out = execFileSync(
      'docker',
      [
        'compose', 'exec', '-T', 'wordpress',
        'wp', 'post', 'get', postId, '--field=post_status',
        `--path=/var/www/html/sites/${siteKey}`, '--allow-root',
      ],
      { cwd: REPO_ROOT, stdio: 'pipe', timeout: 60_000, encoding: 'utf8' }
    );
    return out.trim();
  } catch (e) {
    const stderr = String((e as { stderr?: unknown }).stderr ?? '');
    if (/Could not find the post/i.test(stderr)) {
      return null;
    }
    throw new Error(`WordPress の投稿状態を取得できませんでした: ${stderr || String(e)}`);
  }
}
