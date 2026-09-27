/**
 * @jest-environment node
 */

/**
 * issue #1231: GA連携のServer Action(OAuthクライアント保存 / プロパティ選択 / 解除)の検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここでは実体を直接importする
 * (reviewStepSettingsActions.test.ts と同じ方針)。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const saveClient = jest.fn();
const selectProperty = jest.fn();
const clearCredentials = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  saveProjectGoogleAnalyticsClient: (...args: unknown[]) => saveClient(...args),
  selectProjectGoogleAnalyticsProperty: (...args: unknown[]) => selectProperty(...args),
  clearProjectGoogleAnalyticsCredentials: (...args: unknown[]) => clearCredentials(...args),
}));

import {
  clearProjectGoogleAnalyticsCredentialsAction,
  selectProjectGoogleAnalyticsPropertyAction,
  setProjectGoogleAnalyticsClientAction,
} from '../actions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('GA連携のServer Action(issue #1231)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  describe('setProjectGoogleAnalyticsClientAction', () => {
    it('クライアントIDとシークレットを保存し、設定画面を再検証する', async () => {
      saveClient.mockResolvedValue(undefined);

      const result = await setProjectGoogleAnalyticsClientAction(
        7, {}, form({ clientId: ' cid ', clientSecret: ' secret ' })
      );

      expect(requireAdminSession).toHaveBeenCalled();
      expect(saveClient).toHaveBeenCalledWith(7, { clientId: 'cid', clientSecret: 'secret' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
      expect(result).toEqual({ success: true });
    });

    it('シークレット欄が空ならシークレットを送らない(既存を維持)', async () => {
      saveClient.mockResolvedValue(undefined);

      await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid', clientSecret: '' }));

      expect(saveClient).toHaveBeenCalledWith(7, { clientId: 'cid', clientSecret: undefined });
    });

    it('クライアントIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: ' ' }));

      expect(saveClient).not.toHaveBeenCalled();
      expect(result.error).toContain('クライアントID');
    });

    it('APIが失敗したらエラーメッセージを返す(Errorでない例外も文字列化する)', async () => {
      saveClient.mockRejectedValueOnce(new Error('APIエラー'));
      expect(await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: 'APIエラー',
      });

      saveClient.mockRejectedValueOnce('文字列の失敗');
      expect(await setProjectGoogleAnalyticsClientAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: '文字列の失敗',
      });
    });
  });

  describe('selectProjectGoogleAnalyticsPropertyAction', () => {
    it('選択したプロパティを保存し、ダッシュボードも再検証する', async () => {
      selectProperty.mockResolvedValue(undefined);

      const result = await selectProjectGoogleAnalyticsPropertyAction(
        7, {}, form({ propertyId: '987654321' })
      );

      expect(selectProperty).toHaveBeenCalledWith(7, '987654321');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('プロパティが選ばれていなければAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '' }));

      expect(selectProperty).not.toHaveBeenCalled();
      expect(result.error).toContain('プロパティ');
    });

    it('APIが失敗したらエラーメッセージを返す', async () => {
      selectProperty.mockRejectedValueOnce(new Error('連携していません'));
      expect(await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '1' }))).toEqual({
        error: '連携していません',
      });

      selectProperty.mockRejectedValueOnce('失敗');
      expect(await selectProjectGoogleAnalyticsPropertyAction(7, {}, form({ propertyId: '1' }))).toEqual({
        error: '失敗',
      });
    });
  });

  it('clearProjectGoogleAnalyticsCredentialsActionは連携を解除して画面を再検証する', async () => {
    clearCredentials.mockResolvedValue(undefined);

    await clearProjectGoogleAnalyticsCredentialsAction(7);

    expect(clearCredentials).toHaveBeenCalledWith(7);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/google-analytics');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });
});
