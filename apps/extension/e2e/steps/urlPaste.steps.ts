/**
 * URL貼り付け時のカード情報先読み(letsBlog.pasteAsLink)のステップ(issue #1071)。
 *
 * `extension.ts`のcommandPasteAsLinkは vscode の Progress/エディタ操作を挟むため、
 * 他のLayer 1ステップ(articles.steps.ts等)と同じく、検証対象の呼び出しである
 * apiClient.resolveContentCache / urlPaste.buildStandardLink を直接呼び、
 * commandPasteAsLinkと同じ組み立て・フォールバック手順をここで再現する。
 */

import { Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { buildStandardLink } from '../../src/urlPaste';

/**
 * commandPasteAsLink(extension.ts)と同じ手順。resolveContentCacheの応答からタイトル・
 * サイト名を取り出してリンク記法を組み立て、失敗すればURLそのものを結果とする。
 */
async function pasteAsLink(world: unknown, url: string): Promise<string> {
  const scope = w(world as never);
  try {
    const result = await apiClient.resolveContentCache(scope.token, scope.actor, url);
    const title = result.type === 'AMAZON' ? result.data.productName : result.data.title;
    const siteName = result.type === 'AMAZON' ? undefined : result.data.siteName;
    return buildStandardLink(new URL(url), title ?? undefined, siteName ?? undefined);
  } catch {
    return url;
  }
}

When('{string} のカード情報を先読みしてリンク記法を組み立てる', async (world, url) => {
  (w(world) as unknown as { linkNotation?: string }).linkNotation = await pasteAsLink(world, url);
});

When(
  '{string} のカード情報の先読みに失敗させてリンク記法を組み立てる',
  async (world, url) => {
    (w(world) as unknown as { linkNotation?: string }).linkNotation = await pasteAsLink(world, url);
  }
);

Then('リンク記法に取得したタイトルが含まれる', (world) => {
  const linkNotation = (world as { linkNotation?: string }).linkNotation ?? '';
  if (!/^\[Example Domain \| /.test(linkNotation)) {
    throw new Error(`リンク記法に取得したタイトルが含まれていません: ${linkNotation}`);
  }
});

Then('リンク記法はURLのみになる', (world) => {
  const linkNotation = (world as { linkNotation?: string }).linkNotation ?? '';
  if (linkNotation !== 'http://169.254.169.254/latest/meta-data/') {
    throw new Error(`URLのみへフォールバックしていません: ${linkNotation}`);
  }
});
