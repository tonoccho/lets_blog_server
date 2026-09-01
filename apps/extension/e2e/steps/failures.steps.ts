/** 障害時の振る舞いのステップ(issue #942 / AT-16)。 */

import { Given, Then } from '../support/gherkin';
import { capturedError } from './common.steps';
import { forceStubStatus, resetStub, STUB_PORTS } from '../support/stubs';

Given('LLMスタブが呼び出しを500で失敗させる', async () => {
  await resetStub(STUB_PORTS.llm);
  // 拡張は取得系の呼び出しを最大3回まで再試行する(errorHandler.withRetry)。
  // 回数を絞ると再試行が成功してしまうため、解除するまで継続して失敗させる。
  await forceStubStatus(STUB_PORTS.llm, 500);
});

Then('サーバー側エラーとして失敗する', async (world) => {
  await resetStub(STUB_PORTS.llm);
  const error = capturedError(world);
  const status = (error as { status?: number }).status;
  if (!status || status < 400) {
    throw new Error(`APIエラーとして扱われませんでした: ${error.message}`);
  }
});

Then('エラーに相関IDが含まれる', (world) => {
  const error = capturedError(world);
  const correlationId = (error as { correlationId?: string }).correlationId;
  if (!correlationId) {
    throw new Error(`相関IDがエラーに含まれていません: ${error.message}`);
  }
});
