/**
 * @jest-environment node
 */

/**
 * issue #1232: AdSense連携のServer Action(設定保存 / パブリッシャーID選択)の検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここでは実体を直接importする
 * (googleAnalyticsActions.test.ts と同じ方針)。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const setSettings = jest.fn();
const setClientSecret = jest.fn();
const selectAccount = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  setProjectAdSenseSettings: (...args: unknown[]) => setSettings(...args),
  setProjectAdSenseClientSecret: (...args: unknown[]) => setClientSecret(...args),
  selectProjectAdSenseAccount: (...args: unknown[]) => selectAccount(...args),
}));

import { selectProjectAdSenseAccountAction, setProjectAdSenseSettingsAction } from '../actions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

describe('AdSense連携のServer Action(issue #1232)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  describe('setProjectAdSenseSettingsAction', () => {
    it('パブリッシャーIDが空でもクライアントIDがあれば保存できる', async () => {
      setSettings.mockResolvedValue(undefined);

      const result = await setProjectAdSenseSettingsAction(
        7, {}, form({ accountId: '  ', clientId: ' cid ', clientSecret: '' })
      );

      expect(setSettings).toHaveBeenCalledWith(7, { accountId: '', clientId: 'cid' });
      expect(setClientSecret).not.toHaveBeenCalled();
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/adsense');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('パブリッシャーIDを入力したときはそのまま送り、シークレットも保存する', async () => {
      setSettings.mockResolvedValue(undefined);
      setClientSecret.mockResolvedValue(undefined);

      await setProjectAdSenseSettingsAction(
        7, {}, form({ accountId: ' pub-1 ', clientId: 'cid', clientSecret: ' secret ' })
      );

      expect(setSettings).toHaveBeenCalledWith(7, { accountId: 'pub-1', clientId: 'cid' });
      expect(setClientSecret).toHaveBeenCalledWith(7, 'secret');
    });

    it('フォームにaccountIdが無くても保存できる', async () => {
      setSettings.mockResolvedValue(undefined);

      const result = await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }));

      expect(setSettings).toHaveBeenCalledWith(7, { accountId: '', clientId: 'cid' });
      expect(result).toEqual({ success: true });
    });

    it('クライアントIDが空ならAPIを呼ばずエラーを返す', async () => {
      const result = await setProjectAdSenseSettingsAction(7, {}, form({ accountId: 'pub-1', clientId: ' ' }));

      expect(setSettings).not.toHaveBeenCalled();
      expect(result.error).toContain('クライアントID');
    });

    it('APIが失敗したらエラーメッセージを返す(Errorでない例外も文字列化する)', async () => {
      setSettings.mockRejectedValueOnce(new Error('APIエラー'));
      expect(await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: 'APIエラー',
      });

      setSettings.mockRejectedValueOnce('失敗');
      expect(await setProjectAdSenseSettingsAction(7, {}, form({ clientId: 'cid' }))).toEqual({
        error: '失敗',
      });
    });
  });

  describe('selectProjectAdSenseAccountAction', () => {
    it('選んだパブリッシャーIDを保存し、設定画面とダッシュボードを再検証する', async () => {
      selectAccount.mockResolvedValue(undefined);

      const result = await selectProjectAdSenseAccountAction(
        7, {}, form({ selectedAccountId: ' pub-2222222222222222 ' })
      );

      expect(requireAdminSession).toHaveBeenCalled();
      expect(selectAccount).toHaveBeenCalledWith(7, 'pub-2222222222222222');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/adsense');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
      expect(result).toEqual({ success: true });
    });

    it('未選択ならAPIを呼ばずエラーを返す', async () => {
      const result = await selectProjectAdSenseAccountAction(7, {}, form({}));

      expect(selectAccount).not.toHaveBeenCalled();
      expect(result.error).toContain('選択');
    });

    it('APIが失敗したらエラーメッセージを返す(Errorでない例外も文字列化する)', async () => {
      selectAccount.mockRejectedValueOnce(new Error('APIエラー'));
      expect(await selectProjectAdSenseAccountAction(7, {}, form({ selectedAccountId: 'pub-1' }))).toEqual({
        error: 'APIエラー',
      });

      selectAccount.mockRejectedValueOnce('失敗');
      expect(await selectProjectAdSenseAccountAction(7, {}, form({ selectedAccountId: 'pub-1' }))).toEqual({
        error: '失敗',
      });
    });
  });
});
