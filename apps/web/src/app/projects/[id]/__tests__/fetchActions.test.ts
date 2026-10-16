/**
 * @jest-environment node
 */

/**
 * issue #1715: タブ表示用の取得系Server Actionは、失敗を例外として投げず { data?, error? } で返す。
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
import { failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
} from '../actions';

const cases = [
  ['fetchLlmModelsAction', fetchLlmModelsAction, 'GET /api/projects/7/ai-models/llm/models'],
  ['fetchLlmProviderAction', fetchLlmProviderAction, 'GET /api/projects/7/ai-models/llm/provider'],
  ['fetchReviewStepSettingsAction', fetchReviewStepSettingsAction, 'GET /api/projects/7/ai-models/llm/review-steps'],
  ['fetchImageProviderAction', fetchImageProviderAction, 'GET /api/projects/7/ai-models/image/provider'],
  ['fetchComfyUiCheckpointsAction', fetchComfyUiCheckpointsAction, 'GET /api/projects/7/ai-models/comfyui/checkpoints'],
] as const;

describe.each(cases)('%s(issue #1715)', (_name, action, route) => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetServerRuntime(ADMIN_SESSION);
  });

  it('成功すると data を返す', async () => {
    installBackend({ [route]: () => jsonResponse({ marker: 1 }) });

    const result = await action(7);

    expect(result).toEqual({ data: { marker: 1 } });
  });

  it('API が失敗しても投げず、失敗の理由を error で返す', async () => {
    installBackend({ [route]: failWith(502, 'ComfyUIに接続できません') });

    await expect(action(7)).resolves.toEqual({ error: 'APIエラー (502): ComfyUIに接続できません' });
  });

  it('Error 以外で失敗しても文字列化して error で返す', async () => {
    failFetchWith('文字列の失敗');

    await expect(action(7)).resolves.toEqual({ error: '文字列の失敗' });
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    const backend = installBackend({ [route]: () => jsonResponse({}) });
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });

    await expect(action(7)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
