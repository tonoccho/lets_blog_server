/** 実サイトの署名付きプレビューURL(issue #1562)のステップ。 */

import { AfterScenario, Given, Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { LetsblogPluginState, setLetsblogPluginState } from '../support/wordpress';

const STATES: Record<string, LetsblogPluginState> = {
  導入済み: 'installed',
  未導入: 'notInstalled',
  要更新: 'needsUpdate',
};

Given('サイトの letsblog プラグインが {string} である', (world, label) => {
  const state = STATES[label];
  if (!state) throw new Error(`未知のプラグイン状態です: ${label}`);
  setLetsblogPluginState(w(world).site.siteKey, state);
  (world as { pluginTouchedSite?: string }).pluginTouchedSite = w(world).site.siteKey;
});

/**
 * プラグイン状態を書き換えたシナリオは、成否にかかわらず導入済みへ戻す。戻さないと共有の
 * at16probe が「要更新」のまま残り、後続の公開系シナリオが 409 になる(issue #1691)。
 */
AfterScenario((_tags, world) => {
  const siteKey = (world as { pluginTouchedSite?: string }).pluginTouchedSite;
  if (siteKey) setLetsblogPluginState(siteKey, 'installed');
});

When('題名 {string} の記事の署名付きプレビューURLを取得する', async (world, title) => {
  const scope = w(world);
  (scope as { signed?: apiClient.SignedPreviewUrlResult }).signed = await apiClient.createSignedPreviewUrl(
    scope.token,
    scope.actor,
    scope.project.id,
    { siteId: scope.site.id, title, contentHtml: '<p>受け入れテストの本文です。</p>' }
  );
});

function signedOf(world: unknown): apiClient.SignedPreviewUrlResult {
  const signed = (world as { signed?: apiClient.SignedPreviewUrlResult }).signed;
  if (!signed) throw new Error('署名付きプレビューURLが取得されていません');
  return signed;
}

function unavailableOf(world: unknown): Extract<apiClient.SignedPreviewUrlResult, { kind: 'pluginUnavailable' }> {
  const signed = signedOf(world);
  if (signed.kind !== 'pluginUnavailable') throw new Error(`案内ではありません: ${JSON.stringify(signed)}`);
  return signed;
}

Then('署名付きプレビューURLが返り、有効期限は未来である', (world) => {
  const signed = signedOf(world);
  if (signed.kind !== 'ready') throw new Error(`URLが返りませんでした: ${JSON.stringify(signed)}`);
  if (!/^https?:\/\//.test(signed.url)) throw new Error(`URLの形式が不正です: ${signed.url}`);
  // 期限はエポック秒。ミリ秒で返る場合も未来であることだけを確かめる。
  const nowSeconds = Date.now() / 1000;
  if (!(signed.expiresAt > nowSeconds)) throw new Error(`有効期限が未来ではありません: ${signed.expiresAt}`);
});

Then('プラグインが使えない案内が返る', (world) => {
  unavailableOf(world);
});

Then('案内の文言に {string} が含まれる', (world, text) => {
  const { message } = unavailableOf(world);
  if (!message.includes(text)) throw new Error(`案内に ${text} がありません: ${message}`);
});

Then('案内は更新ではなく導入を求める', (world) => {
  if (unavailableOf(world).needsUpdate) throw new Error('更新を求める案内になっています');
});

Then('案内は更新を求める', (world) => {
  if (!unavailableOf(world).needsUpdate) throw new Error('導入を求める案内になっています');
});
