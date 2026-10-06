/**
 * @jest-environment node
 */

/**
 * issue #1212: レビューステップ別(#1210)のLLM設定(#1211のAPI)を扱うServer Actionの検証。
 *
 * `../actions` は他のコンポーネントテスト(LlmModelPanel.test.tsx等)から常にモックされて
 * おり、実体が実行されることが無いため、追加した2関数(fetchReviewStepSettingsAction /
 * updateReviewStepSettingAction)をここで直接(モックせずに)importして検証する。
 * `@/lib/session`の`requireAdminSession()`をモックし、`sites/__tests__/actionsAuthorization.test.ts`
 * と同じ方針(server-only/next/cacheのモック)を踏襲する。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const listReviewStepSettings = jest.fn();
const updateReviewStepSetting = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  listReviewStepSettings: (...args: unknown[]) => listReviewStepSettings(...args),
  updateReviewStepSetting: (...args: unknown[]) => updateReviewStepSetting(...args),
}));

import { fetchReviewStepSettingsAction, updateReviewStepSettingAction } from '../actions';

describe('レビューステップ別LLM設定のServer Action(issue #1212)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('fetchReviewStepSettingsActionは管理者セッションを要求し、一覧をそのまま返す', async () => {
    const data = { steps: [], availableProviders: ['OPENAI'], availableModels: ['gpt-4o-mini'], availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] } };
    listReviewStepSettings.mockResolvedValue(data);

    const result = await fetchReviewStepSettingsAction(7);

    expect(requireAdminSession).toHaveBeenCalled();
    expect(listReviewStepSettings).toHaveBeenCalledWith(7);
    expect(result).toEqual(data);
  });

  it('updateReviewStepSettingActionは成功するとerrorを含まない結果を返す', async () => {
    updateReviewStepSetting.mockResolvedValue({
      steps: [{ stepKey: 'JAPANESE', provider: 'OPENAI', model: 'gpt-4o-mini' }],
      availableProviders: ['OPENAI'],
      availableModels: ['gpt-4o-mini'],
      availableModelsByProvider: { OPENAI: ['gpt-4o-mini'] },
    });

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(requireAdminSession).toHaveBeenCalled();
    expect(updateReviewStepSetting).toHaveBeenCalledWith(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');
    expect(result).toEqual({});
  });

  it('updateReviewStepSettingActionは失敗するとerrorメッセージを返す', async () => {
    updateReviewStepSetting.mockRejectedValue(new Error('保存に失敗しました'));

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(result).toEqual({ error: '保存に失敗しました' });
  });

  it('updateReviewStepSettingActionはError以外で失敗しても文字列化したerrorを返す', async () => {
    // err instanceof Error===falseの分岐を固定するため、意図的にError以外の値で拒否する。
    updateReviewStepSetting.mockRejectedValue('保存に失敗しました(非Error)');

    const result = await updateReviewStepSettingAction(7, 'JAPANESE', 'OPENAI', 'gpt-4o-mini');

    expect(result).toEqual({ error: '保存に失敗しました(非Error)' });
  });
});
