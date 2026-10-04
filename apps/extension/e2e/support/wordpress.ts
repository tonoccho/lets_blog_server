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

/** letsblog プラグインの状態(issue #1562)。サーバーが 導入済み / 未導入 / 要更新 と判定する3状態。 */
export type LetsblogPluginState = 'installed' | 'notInstalled' | 'needsUpdate';

function wpCompose(args: string[]): string {
  return execFileSync('docker', ['compose', 'exec', '-T', 'wordpress', ...args], {
    cwd: REPO_ROOT,
    stdio: 'pipe',
    timeout: 120_000,
    encoding: 'utf8',
  });
}

/**
 * サイトの letsblog プラグインを指定の状態にする。サーバー(導入状態の判定)は wp-cli の応答だけを見るので、
 * プラグインを有効化/停止すれば導入済み/未導入、プロトコル版を書き換えたコピーを有効化すれば要更新になる。
 * 管理APIにはプラグイン状態を操作する公開口が無いため、`wordpressPostStatus` と同じく wp-cli を直接叩く。
 */
export function setLetsblogPluginState(siteKey: string, state: LetsblogPluginState): void {
  const sitePath = `/var/www/html/sites/${siteKey}`;
  const pluginDir = `${sitePath}/wp-content/plugins/letsblog`;
  const wp = (...args: string[]) => wpCompose(['wp', ...args, `--path=${sitePath}`, '--allow-root']);
  if (state === 'notInstalled') {
    try {
      wp('plugin', 'deactivate', 'letsblog');
    } catch (e) {
      const stderr = String((e as { stderr?: unknown }).stderr ?? '');
      if (!/not installed|doesn't exist|was not found/i.test(stderr)) throw e;
    }
    return;
  }
  wpCompose(['mkdir', '-p', pluginDir]);
  execFileSync(
    'docker',
    ['compose', 'cp', 'infra/wordpress/letsblog-plugin/letsblog.php', `wordpress:${pluginDir}/letsblog.php`],
    { cwd: REPO_ROOT, stdio: 'pipe', timeout: 60_000 }
  );
  if (state === 'needsUpdate') {
    wpCompose(['sed', '-i', 's/^const LETSBLOG_PROTOCOL_VERSION = 1;/const LETSBLOG_PROTOCOL_VERSION = 999;/', `${pluginDir}/letsblog.php`]);
  }
  wp('plugin', 'activate', 'letsblog');
}
