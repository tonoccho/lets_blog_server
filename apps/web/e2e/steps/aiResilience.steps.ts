import type { APIRequestContext, APIResponse } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { forceStubDelay, forceStubStatus, stubRequestCount } from '../support/stubs';

/**
 * AI生成の異常系(レート制限・タイムアウト・接続設定不備)のステップ定義
 * (issue #1149、親issue #934のシナリオ14・15・16、issue #934の子)。
 *
 * 正常系の `aiGeneration.steps.ts` とは独立させ、同ファイルの
 * `ctx.draftResult`(`response.ok()` を前提で握りつぶす)を再利用しない。
 * ここでは失敗レスポンスそのものを検証するため、`ctx.draftFailureResponse` に
 * レスポンス本体(`APIResponse`、未パース)を保持する。
 *
 * `AiServiceException` は `GlobalExceptionHandler.handleAiServiceException`
 * (services/ai/src/main/java/com/letsblog/ai/config/GlobalExceptionHandler.java)で
 * すべて HTTP 502・`{"error": "<message>"}` に変換される。`type`/`code` 欄は無いため、
 * 3ケースの区別は `error` 文字列の内容だけで行う。
 */

interface DraftFailureBody {
  error: string;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

// --------------------------------------------------------------- 前提: スタブへのエラー注入

Given('LLMが次のリクエストで429を返すよう仕込む', async () => {
  await forceStubStatus('llm', 429, 1);
});

/**
 * `forceStubDelay` はスタブが指定ミリ秒待ってから接続を切る(`res.destroy()`、
 * `infra/e2e-stubs/lib/stub.js`)。ai-serviceの実効タイムアウトは
 * `LLM_REQUEST_TIMEOUT_SECONDS`(このスタックでは120秒、docker-compose.e2e-stubs.yml)だが、
 * 実際に120秒待つのは受け入れテストとして非現実的なため、接続が切られること自体で
 * クライアント側の例外(タイムアウトまたはネットワークエラー)を誘発する経路を使う
 * (LlmClient.generateWithOpenAiCompatible/generateWithClaude の catch (Exception e))。
 * 2秒はテスト実行時間を抑えつつ「遅延の後に切断される」ことを確実に再現できる値。
 */
Given('LLMが次のリクエストで応答を遅延させ接続を切るよう仕込む', async () => {
  await forceStubDelay('llm', 2_000, 1);
});

// --------------------------------------------------------------- もし: 下書き生成を試みる(失敗を許容する)

When(/^「(.+)」というお題で下書き生成を試みる$/, async ({ ctx, request }, topic: string) => {
  const token = await adminToken(request);
  const response = await request.post('/api/ai/draft', {
    headers: { Authorization: `Bearer ${token}` },
    data: { mode: 'draft', text: topic },
  });
  ctx.draftFailureResponse = response;
});

When(
  /^「(.+)」というお題でCLAUDEプロバイダーを指定して下書き生成を試みる$/,
  async ({ ctx, request }, topic: string) => {
    // LLM呼び出し前に弾かれることを確かめるため、リクエスト前の受信件数を控えておく
    // (`stubRequestCount` は累積値。同じ`before/after`比較は`media.steps.ts`の慣例)。
    ctx.llmStubCallsBeforeConfigCheck = await stubRequestCount('llm');
    const token = await adminToken(request);
    const response = await request.post('/api/ai/draft', {
      headers: { Authorization: `Bearer ${token}` },
      data: { mode: 'draft', text: topic, provider: 'CLAUDE' },
    });
    ctx.draftFailureResponse = response;
  }
);

// --------------------------------------------------------------- ならば: 失敗の内容を検証する

async function failureBody(response: APIResponse): Promise<DraftFailureBody> {
  return (await response.json()) as DraftFailureBody;
}

Then('下書き生成はレート制限429に起因する失敗として返る', async ({ ctx }) => {
  const response = ctx.draftFailureResponse as APIResponse;
  expect(response.status(), `期待するステータス502に対し実際は${response.status()}だった`).toBe(502);
  const body = await failureBody(response);
  expect(body.error, `失敗メッセージ: ${body.error}`).toContain('429');
});

Then('下書き生成の失敗メッセージに生の例外ログは含まれない', async ({ ctx }) => {
  const response = ctx.draftFailureResponse as APIResponse;
  const body = await failureBody(response);
  expect(body.error, `失敗メッセージ: ${body.error}`).not.toContain('Exception');
  expect(body.error, `失敗メッセージ: ${body.error}`).not.toContain('	at ');
});

Then('下書き生成はタイムアウトまたはネットワークエラーとして速やかに失敗が返る', async ({ ctx }) => {
  const response = ctx.draftFailureResponse as APIResponse;
  expect(response.status(), `期待するステータス502に対し実際は${response.status()}だった`).toBe(502);
  const body = await failureBody(response);
  expect(body.error, `失敗メッセージ: ${body.error}`).toContain('タイムアウトまたはネットワークエラー');
});

Then('下書き生成はAPIキー未設定のエラーとして返る', async ({ ctx }) => {
  const response = ctx.draftFailureResponse as APIResponse;
  expect(response.status(), `期待するステータス502に対し実際は${response.status()}だった`).toBe(502);
  const body = await failureBody(response);
  expect(body.error, `失敗メッセージ: ${body.error}`).toContain('APIキーが設定されていません');
  expect(body.error, `失敗メッセージ: ${body.error}`).toContain('CLAUDE');
});

Then('LLMスタブは一度も呼び出されていない', async ({ ctx }) => {
  const before = ctx.llmStubCallsBeforeConfigCheck as number;
  const after = await stubRequestCount('llm');
  expect(
    after,
    `設定不備チェックの前後でLLMスタブの受信件数が変わった(要求前 ${before} 件 / 要求後 ${after} 件)`
      + '(設定不備チェックがLLM呼び出しより後に行われている)'
  ).toBe(before);
});
