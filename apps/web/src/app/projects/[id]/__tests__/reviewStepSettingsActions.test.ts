/**
 * @jest-environment node
 */

/**
 * issue #1212: レビューステップ別(#1210)のLLM設定(#1211のAPI)を扱うServer Actionの検証。
 * `../actions` の実体を直接importし、`@/lib/apiClient`・`@/lib/session` も実物を通す。
 * 差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ(#1836)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { fetchReviewStepSettingsAction, updateReviewStepSettingAction } from '../actions';

const LIST = 'GET /api/projects/7/ai-models/llm/review-steps';
const UPDATE = 'PUT /api/projects/7/ai-models/llm/review-steps/JAPANESE';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe('レビューステップ別LLM設定のServer Action(issue #1212)', () => {
  it('fetchReviewStepSettingsActionは管理者セッションを要求し、一覧をそのまま返す', async () => {
    const data = { steps: [], availableProviders: ['OPENAI'], availableModels: ['gpt-4o-mini'], availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] } };
    installBackend({ [LIST]: () => jsonResponse(data) });

    const result = await fetchReviewStepSettingsAction(7);

    expect(result).toEqual({ data });
  });

  it('updateReviewStepSettingActionは成功するとerrorを含まない結果を返す', async () => {
    backend = installBackend({
      [UPDATE]: () =>
        jsonResponse({
          steps: [{ stepKey: 'JAPANESE', provider: 'OPENAI', model: 'gpt-4o-mini' }],
          availableProviders: ['OPENAI'],
          availableModels: ['gpt-4o-mini'],
          availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] },
        }),
    });

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(bodies(backend, UPDATE)).toEqual([{ provider: 'OPENAI', model: 'gpt-4o-mini' }]);
    expect(result).toEqual({});
  });

  it('updateReviewStepSettingActionは空の指定を null で送る(既定へ戻す)', async () => {
    backend = installBackend({ [UPDATE]: () => jsonResponse({}) });

    await updateReviewStepSettingAction(7, 'JAPANESE', '', '');

    expect(bodies(backend, UPDATE)).toEqual([{ provider: null, model: null }]);
  });

  it('updateReviewStepSettingActionは失敗するとerrorメッセージを返す', async () => {
    installBackend({ [UPDATE]: failWith(500, '保存に失敗しました') });

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(result).toEqual({ error: 'APIエラー (500): 保存に失敗しました' });
  });

  it('updateReviewStepSettingActionはError以外で失敗しても文字列化したerrorを返す', async () => {
    failFetchWith('保存に失敗しました(非Error)');

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(result).toEqual({ error: '保存に失敗しました(非Error)' });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    backend = installBackend();
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'm')).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
