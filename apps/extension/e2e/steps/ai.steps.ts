/** AI執筆支援のステップ(issue #942 / AT-16)。 */

import { Given, Then, When } from '../support/gherkin';
import { attempt, capturedError, w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { getConfiguredAiProvider, setConfiguredAiProvider } from '../../src/config';
import { asExtensionContext } from '../support/env';

When('モード {string} でAIに {string} を依頼する', async (world, mode, text) => {
  const scope = w(world);
  (scope as { result?: unknown }).result = await apiClient.askAi(
    scope.token,
    mode as 'draft' | 'proofread' | 'summarize',
    text,
    scope.actor
  );
});

When('本文 {string} からタグ候補を提案させる', async (world, text) => {
  const scope = w(world);
  (scope as { tags?: unknown }).tags = await apiClient.suggestTags(
    scope.token,
    text,
    scope.actor,
    undefined,
    scope.project.id
  );
});

Then('タグ候補が1件以上返る', (world) => {
  const tags = (world as { tags?: { categories: string[]; tags: string[] } }).tags;
  if (!tags || tags.tags.length === 0) {
    throw new Error(`タグ候補が返りませんでした: ${JSON.stringify(tags)}`);
  }
});

When('見出し {string} と直前の文脈を指定してセクション本文を生成する', async (world, heading) => {
  const scope = w(world);
  (scope as { result?: unknown }).result = await apiClient.generateSection(scope.token, scope.actor, {
    mode: 'body',
    heading,
    precedingContext: '## はじめに\n\n受け入れテストの導入文です。',
    articleTitle: 'AT16 受け入れテスト記事',
  });
});

Then('セクション本文が返る', (world) => {
  const result = (world as { result?: { result?: string } }).result;
  if (!result?.result?.trim()) throw new Error(`セクション本文が空です: ${JSON.stringify(result)}`);
});

When('{string} をWeb検索つきでAIへ質問する', async (world, question) => {
  const scope = w(world);
  (scope as { result?: unknown }).result = await apiClient.askAiSearch(scope.token, scope.actor, question);
});

Then('回答と出典URLの一覧が返る', (world) => {
  const result = (world as { result?: { result?: string; sources?: { url: string }[] } }).result;
  if (!result?.result?.trim()) throw new Error('回答が空です');
  if (!result.sources || result.sources.length === 0) {
    throw new Error(`出典が返りませんでした: ${JSON.stringify(result)}`);
  }
  for (const source of result.sources) {
    if (!/^https?:\/\//.test(source.url)) throw new Error(`出典URLが不正です: ${source.url}`);
  }
});

When('本文 {string} の校正チェックを実行する', async (world, text) => {
  const scope = w(world);
  (scope as { proofreadText?: string }).proofreadText = text;
  (scope as { proofread?: unknown }).proofread = await apiClient.proofreadContent(
    scope.token,
    scope.actor,
    text
  );
});

Then('指摘の該当箇所がすべて本文中の文字列である', (world) => {
  const scope = world as { proofreadText?: string; proofread?: { issues: { originalText: string }[] } };
  const issues = scope.proofread?.issues ?? [];
  if (issues.length === 0) {
    throw new Error(`校正の指摘が返りませんでした: ${JSON.stringify(scope.proofread)}`);
  }
  for (const issue of issues) {
    if (!scope.proofreadText?.includes(issue.originalText)) {
      throw new Error(`指摘の該当箇所が本文にありません: ${issue.originalText}`);
    }
  }
});

Given('AIプロバイダーを {string} へ切り替える', async (world, provider) => {
  await setConfiguredAiProvider(provider);
  if (getConfiguredAiProvider() !== provider) {
    throw new Error('AIプロバイダーの設定が反映されていません');
  }
  // 設定を読み直す拡張側と同じく、コンテキストの型で扱えることも確認する。
  asExtensionContext(w(world).context);
});

When('現在のプロバイダーでAIに下書きを依頼する', async (world) => {
  const scope = w(world);
  await attempt(world, () =>
    apiClient.askAi(scope.token, 'draft', '受け入れテストの本文です。', scope.actor, getConfiguredAiProvider())
  );
});

Then('CLAUDEが未設定であることを伝えるエラーになる', (world) => {
  const error = capturedError(world);
  const detail = `${error.message} ${(error as { responseBody?: string }).responseBody ?? ''}`;
  if (!detail.includes('CLAUDE')) {
    throw new Error(`CLAUDEに言及しないエラーでした: ${detail}`);
  }
});

When('AIに下書きを依頼する', async (world) => {
  const scope = w(world);
  await attempt(world, () => apiClient.askAi(scope.token, 'draft', '受け入れテストの本文です。', scope.actor));
});
