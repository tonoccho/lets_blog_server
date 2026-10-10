/**
 * @jest-environment node
 */

/**
 * issue #1715: タブ表示用の取得系Server Actionは、失敗を例外として投げず `{ data?, error? }` で返す。
 * Next.jsの本番ビルドはServer Actionが投げた例外のメッセージをクライアントへ渡さないため、
 * 失敗の理由を画面に出すには戻り値で運ぶ必要がある。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const api = {
  listLlmModels: jest.fn(),
  listLlmProvider: jest.fn(),
  listReviewStepSettings: jest.fn(),
  listImageProvider: jest.fn(),
  listComfyUiCheckpoints: jest.fn(),
};
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  listLlmModels: (...a: unknown[]) => api.listLlmModels(...a),
  listLlmProvider: (...a: unknown[]) => api.listLlmProvider(...a),
  listReviewStepSettings: (...a: unknown[]) => api.listReviewStepSettings(...a),
  listImageProvider: (...a: unknown[]) => api.listImageProvider(...a),
  listComfyUiCheckpoints: (...a: unknown[]) => api.listComfyUiCheckpoints(...a),
}));

import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
} from '../actions';

const cases = [
  ['fetchLlmModelsAction', fetchLlmModelsAction, api.listLlmModels],
  ['fetchLlmProviderAction', fetchLlmProviderAction, api.listLlmProvider],
  ['fetchReviewStepSettingsAction', fetchReviewStepSettingsAction, api.listReviewStepSettings],
  ['fetchImageProviderAction', fetchImageProviderAction, api.listImageProvider],
  ['fetchComfyUiCheckpointsAction', fetchComfyUiCheckpointsAction, api.listComfyUiCheckpoints],
] as const;

describe.each(cases)('%s(issue #1715)', (_name, action, apiFn) => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('成功すると data を返す', async () => {
    apiFn.mockResolvedValue({ marker: 1 });

    const result = await action(7);

    expect(requireAdminSession).toHaveBeenCalled();
    expect(apiFn).toHaveBeenCalledWith(7);
    expect(result).toEqual({ data: { marker: 1 } });
  });

  it('API が失敗しても投げず、失敗の理由を error で返す', async () => {
    apiFn.mockRejectedValue(new Error('ComfyUIに接続できません'));

    await expect(action(7)).resolves.toEqual({ error: 'ComfyUIに接続できません' });
  });

  it('Error 以外で失敗しても文字列化して error で返す', async () => {
    apiFn.mockRejectedValue('文字列の失敗');

    await expect(action(7)).resolves.toEqual({ error: '文字列の失敗' });
  });
});
